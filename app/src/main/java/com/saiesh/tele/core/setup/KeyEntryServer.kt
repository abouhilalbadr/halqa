package com.saiesh.tele.core.setup

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** The words on the phone page, in the TV's language. */
data class KeyEntryPage(
    val language: String,
    val rtl: Boolean,
    val title: String,
    val intro: String,
    val apiIdLabel: String,
    val apiHashLabel: String,
    val send: String,
    val invalid: String,
    val sent: String,
)

/**
 * A one-page web server on the home network so the Telegram API ID and hash can be pasted on a phone instead
 * of typed with the remote. It runs only while the key step is on screen, answers only under a random path
 * that is shown in the QR code, and stops after the first valid key.
 */
class KeyEntryServer(
    private val page: KeyEntryPage,
    private val onKeys: (apiId: String, apiHash: String) -> Unit,
) {
    private val token = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var server: ServerSocket? = null

    /** Starts listening and returns the address for the QR code, or null when the TV has no home-network address. */
    fun start(): String? {
        val address = lanAddress() ?: return null
        val socket =
            runCatching { ServerSocket(0, BACKLOG, InetAddress.getByName("0.0.0.0")) }
                .onFailure { Log.w(TAG, "Couldn't open the key page", it) }
                .getOrNull() ?: return null
        server = socket
        thread(name = "key-entry", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
        return "http://${address.hostAddress}:${socket.localPort}/$token"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        var contentLength = 0
        var headerBytes = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            headerBytes += header.length
            if (headerBytes > MAX_HEADERS) return
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1)?.substringBefore('?')
        val output = socket.getOutputStream()
        val (status, html) =
            when {
                path != "/$token" -> "404 Not Found" to ""
                method == "GET" -> "200 OK" to form(error = false)
                method == "POST" && contentLength in 1..MAX_BODY -> "200 OK" to submit(readBody(input, contentLength))
                else -> "400 Bad Request" to ""
            }
        val body = html.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun submit(body: String): String {
        val fields =
            body.split('&').associate { pair ->
                val key = pair.substringBefore('=')
                val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
                key to value.trim()
            }
        val apiId = fields["api_id"].orEmpty()
        val apiHash = fields["api_hash"].orEmpty().lowercase()
        if (!isValidApiId(apiId) || !isValidApiHash(apiHash)) return form(error = true)
        main.post { onKeys(apiId, apiHash) }
        stop()
        return document("<p class=\"done\">${escape(page.sent)}</p>")
    }

    private fun form(error: Boolean): String =
        document(
            """
            <p>${escape(page.intro)}</p>
            ${if (error) "<p class=\"err\">${escape(page.invalid)}</p>" else ""}
            <form method="post" action="/$token">
              <label>${escape(page.apiIdLabel)}<input name="api_id" inputmode="numeric" autocomplete="off" dir="ltr" required></label>
              <label>${escape(page.apiHashLabel)}<input name="api_hash" autocomplete="off" autocapitalize="off" spellcheck="false" dir="ltr" required></label>
              <button type="submit">${escape(page.send)}</button>
            </form>
            """.trimIndent(),
        )

    private fun document(content: String): String =
        """
        <!doctype html><html lang="${page.language}" dir="${if (page.rtl) "rtl" else "ltr"}"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>Halqa</title><style>
        body{margin:0;padding:24px 18px;background:#262A30;color:#fff;font:16px/1.5 system-ui,-apple-system,"Segoe UI",Tahoma,sans-serif}
        h1{font-size:22px;margin:0 0 6px}p{color:#C3C8D1;margin:0 0 16px}label{display:block;margin:0 0 14px;color:#B6BCC6;font-size:14px}
        input{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:12px;border-radius:10px;border:1px solid #5A606B;background:#2E3239;color:#fff;font-size:16px}
        button{width:100%;padding:13px;border:0;border-radius:10px;background:#F26419;color:#fff;font-size:16px;font-weight:600}
        .err{color:#FF8A80}.done{color:#fff;font-size:18px}
        </style></head><body><h1>${escape(page.title)}</h1>$content</body></html>
        """.trimIndent()

    private fun readBody(
        input: InputStream,
        length: Int,
    ): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
        return null
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val TAG = "KeyEntryServer"
        private const val TOKEN_BYTES = 12
        private const val BACKLOG = 4
        private const val TIMEOUT_MS = 15_000
        private const val MAX_LINE = 4096
        private const val MAX_HEADERS = 16_384
        private const val MAX_BODY = 2048

        /** An API ID is a number; Telegram's are up to about 10 digits. */
        fun isValidApiId(value: String): Boolean = value.length in 1..12 && value.all { it.isDigit() }

        /** An API hash is 32 hexadecimal characters. */
        fun isValidApiHash(value: String): Boolean =
            value.length == API_HASH_LENGTH && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

        private const val API_HASH_LENGTH = 32

        /** The TV's IPv4 address on the home network, over Wi-Fi or Ethernet. */
        private fun lanAddress(): InetAddress? =
            runCatching {
                NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            }.getOrNull()
    }
}
