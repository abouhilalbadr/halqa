package com.saiesh.tele.core.mac

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.share.File
import kotlin.math.min

/**
 * Reads a video from the Mac's shared folder for the player. Reads go out in [CHUNK]-sized requests and
 * are served from memory in between, because the player asks for a few kilobytes at a time.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false
    private val chunk = ByteArray(CHUNK)
    private var chunkStart = 0L
    private var chunkLength = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = MacPaths.pathOf(dataSpec.uri)
        val opened =
            try {
                MacShare.openForReading(path)
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
        file = opened
        val size = opened.getFileInformation(FileStandardInformation::class.java).endOfFile
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        chunkLength = 0
        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLength) fill()
        if (chunkLength == 0) return C.RESULT_END_OF_INPUT
        val inChunk = (position - chunkStart).toInt()
        val count = min(min(length.toLong(), bytesRemaining), (chunkLength - inChunk).toLong()).toInt()
        System.arraycopy(chunk, inChunk, buffer, offset, count)
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun fill() {
        val current = file ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val read =
            try {
                current.read(chunk, position, 0, CHUNK)
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
        chunkStart = position
        chunkLength = read.coerceAtLeast(0)
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        chunkLength = 0
        runCatching { file?.close() }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val CHUNK = 512 * 1024
    }
}

/** Sends `smb://` addresses to [SmbDataSource] and everything else (Telegram's local stream) to [fallback]. */
@UnstableApi
class MacAwareDataSource(
    private val fallback: DataSource,
) : DataSource {
    private val smb = SmbDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == MacPaths.SCHEME) smb else fallback
        active = source
        return source.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val fallback: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = MacAwareDataSource(fallback.createDataSource())
    }
}
