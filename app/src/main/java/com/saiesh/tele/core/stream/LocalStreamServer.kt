package com.saiesh.tele.core.stream

import android.util.Log
import com.saiesh.tele.core.tdlib.TdLibClient
import org.drinkless.tdlib.TdApi
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Serves Telegram files to a video player over http://127.0.0.1 while TDLib downloads them.
 *
 * Each request names a TDLib file id and an optional byte range. The server asks TDLib to download
 * from the requested offset and writes bytes to the player as soon as they are on disk, so playback
 * starts early and seeking only fetches the part that is needed. Only this device can connect.
 */
object LocalStreamServer {
    private const val TAG = "LocalStreamServer"
    private const val DOWNLOAD_PRIORITY = 32
    private const val CHUNK_SIZE = 512 * 1024
    private const val POLL_MS = 100L
    private const val RESTART_DOWNLOAD_AFTER_MS = 500L
    private const val STALL_TIMEOUT_MS = 60_000L
    private const val TDLIB_TIMEOUT_S = 15L

    private val client get() = TdLibClient.client
    private val workers = Executors.newCachedThreadPool()
    private val lastFileId = AtomicReference<Int?>(null)
    private val activeConnections = AtomicInteger(0)

    @Volatile private var lastActivityAt = System.currentTimeMillis()

    @Volatile private var serverSocket: ServerSocket? = null

    @Synchronized
    fun urlFor(fileId: Int): String {
        val socket = serverSocket ?: start()
        switchTo(fileId)
        return "http://127.0.0.1:${socket.localPort}/video/$fileId"
    }

    /** True when no player is connected and nothing was served for [millis]. */
    fun isIdleFor(millis: Long): Boolean =
        activeConnections.get() == 0 && System.currentTimeMillis() - lastActivityAt > millis

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val connection = socket.accept()
                    workers.execute { handle(connection) }
                } catch (e: IOException) {
                    if (!socket.isClosed) Log.e(TAG, "Accept failed", e)
                }
            }
        }, "tele-stream-accept").apply { isDaemon = true }.start()
        Log.d(TAG, "Listening on 127.0.0.1:${socket.localPort}")
        return socket
    }

    /** Frees the space taken by the previous video when a different one starts. */
    private fun switchTo(fileId: Int) {
        val previous = lastFileId.getAndSet(fileId)
        if (previous != null && previous != fileId) {
            client.send(TdApi.CancelDownloadFile(previous, false)) { }
            client.send(TdApi.DeleteFile(previous)) { }
        }
    }

    private fun handle(connection: Socket) {
        activeConnections.incrementAndGet()
        lastActivityAt = System.currentTimeMillis()
        try {
            serve(connection)
        } finally {
            activeConnections.decrementAndGet()
            lastActivityAt = System.currentTimeMillis()
        }
    }

    private fun serve(connection: Socket) {
        connection.use { socket ->
            try {
                socket.soTimeout = 30_000
                val request = readRequest(socket) ?: return
                val out = BufferedOutputStream(socket.getOutputStream(), CHUNK_SIZE)
                val fileId = request.path.removePrefix("/video/").toIntOrNull()
                val file = fileId?.let { getFile(it) }
                val total = file?.let { if (it.size > 0) it.size else it.expectedSize } ?: 0L
                if (fileId == null || file == null || total <= 0L) {
                    writeHead(out, "404 Not Found", 0, null)
                    out.flush()
                    return
                }
                val start = (request.rangeStart ?: 0L).coerceAtLeast(0L)
                val end = (request.rangeEnd ?: (total - 1)).coerceAtMost(total - 1)
                if (start > end) {
                    writeHead(out, "416 Range Not Satisfiable", 0, "bytes */$total")
                    out.flush()
                    return
                }
                val partial = request.rangeStart != null
                writeHead(
                    out,
                    if (partial) "206 Partial Content" else "200 OK",
                    end - start + 1,
                    if (partial) "bytes $start-$end/$total" else null,
                )
                if (request.method == "HEAD") {
                    out.flush()
                    return
                }
                stream(fileId, start, end, out)
                out.flush()
            } catch (e: IOException) {
                // The player closed the connection, usually because it seeked; nothing to do.
            } catch (e: Exception) {
                Log.e(TAG, "Stream failed", e)
            }
        }
    }

    private fun stream(
        fileId: Int,
        start: Long,
        end: Long,
        out: OutputStream,
    ) {
        requestDownload(fileId, start)
        val buffer = ByteArray(CHUNK_SIZE)
        var position = start
        var reader: RandomAccessFile? = null
        var readerPath: String? = null
        var waitingSince = 0L
        var lastProgressAt = System.currentTimeMillis()
        try {
            while (position <= end) {
                val available = downloadedPrefix(fileId, position)
                if (available <= 0L) {
                    val now = System.currentTimeMillis()
                    if (waitingSince == 0L) waitingSince = now
                    if (now - lastProgressAt > STALL_TIMEOUT_MS) throw IOException("Download stalled")
                    // Another request may have moved TDLib's download elsewhere; pull it back here.
                    if (now - waitingSince > RESTART_DOWNLOAD_AFTER_MS) {
                        requestDownload(fileId, position)
                        waitingSince = now
                    }
                    Thread.sleep(POLL_MS)
                    continue
                }
                waitingSince = 0L

                // TDLib moves the file when the download finishes, so follow its current path.
                val path = getFile(fileId)?.local?.path.orEmpty()
                if (path.isEmpty()) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                if (path != readerPath) {
                    reader?.close()
                    reader = RandomAccessFile(path, "r")
                    readerPath = path
                }

                val count = minOf(available, end - position + 1, CHUNK_SIZE.toLong()).toInt()
                reader!!.seek(position)
                val read = reader.read(buffer, 0, count)
                if (read <= 0) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                out.write(buffer, 0, read)
                position += read
                lastProgressAt = System.currentTimeMillis()
                lastActivityAt = lastProgressAt
            }
        } finally {
            reader?.close()
        }
    }

    private fun requestDownload(
        fileId: Int,
        offset: Long,
    ) {
        client.send(TdApi.DownloadFile(fileId, DOWNLOAD_PRIORITY, offset, 0, false)) { result ->
            if (result is TdApi.Error) Log.e(TAG, "DownloadFile $fileId@$offset: ${result.message}")
        }
    }

    private fun getFile(fileId: Int): TdApi.File? = call(TdApi.GetFile(fileId)) as? TdApi.File

    private fun downloadedPrefix(
        fileId: Int,
        offset: Long,
    ): Long = (call(TdApi.GetFileDownloadedPrefixSize(fileId, offset)) as? TdApi.FileDownloadedPrefixSize)?.size ?: 0L

    private fun call(function: TdApi.Function<*>): TdApi.Object? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<TdApi.Object?>()
        client.send(function) { response ->
            result.set(response)
            latch.countDown()
        }
        latch.await(TDLIB_TIMEOUT_S, TimeUnit.SECONDS)
        return result.get()
    }

    private class Request(
        val method: String,
        val path: String,
        val rangeStart: Long?,
        val rangeEnd: Long?,
    )

    private fun readRequest(socket: Socket): Request? {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = input.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        var rangeStart: Long? = null
        var rangeEnd: Long? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (!line.substring(0, colon).trim().equals("Range", ignoreCase = true)) continue
            // Only the single "bytes=start-[end]" form that players send is supported.
            val spec = line.substring(colon + 1).trim().removePrefix("bytes=").substringBefore(',')
            rangeStart = spec.substringBefore('-').trim().toLongOrNull()
            rangeEnd = spec.substringAfter('-', "").trim().toLongOrNull()
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), rangeStart, rangeEnd)
    }

    private fun writeHead(
        out: OutputStream,
        status: String,
        length: Long,
        contentRange: String?,
    ) {
        val head =
            buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                if (contentRange != null) append("Content-Range: ").append(contentRange).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }
}
