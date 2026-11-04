package com.saiesh.tele.presentation.mac

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saiesh.tele.core.mac.MacException
import com.saiesh.tele.core.mac.MacLibraryCache
import com.saiesh.tele.core.mac.MacLibraryScanner
import com.saiesh.tele.core.mac.MacLogin
import com.saiesh.tele.core.mac.MacSettings
import com.saiesh.tele.core.mac.MacShare
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.domain.model.MacVideo
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Collections

data class MacLibraryState(
    val login: MacLogin? = null,
    val titles: List<MacTitle> = emptyList(),
    val isScanning: Boolean = false,
    val hasScanned: Boolean = false,
    val error: String? = null,
)

/** What is in the Mac's shared folder, kept for the whole activity so home and the title page agree. */
class MacLibraryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MacLibraryState(login = MacSettings.login.value))
    val state: StateFlow<MacLibraryState> = _state

    /** Video length in milliseconds by path, filled in as the title page opens. */
    private val _durations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val durations: StateFlow<Map<String, Long>> = _durations

    /** A still image file on the TV for each video path, filled in as the title page opens. */
    private val _stills = MutableStateFlow<Map<String, String>>(emptyMap())
    val stills: StateFlow<Map<String, String>> = _stills

    private val durationPrefs = application.getSharedPreferences(DURATION_PREFS, Context.MODE_PRIVATE)
    private val posterDir = File(application.filesDir, "mac_posters").apply { mkdirs() }
    private val stillDir = File(application.filesDir, "mac_stills").apply { mkdirs() }
    private var lastScanAt = 0L
    private val probing = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Show the last list right away (the TV often rebuilds the screen on return); a fresh scan follows.
        _state.value.login?.let { login ->
            MacLibraryCache.load(application, login)?.let { saved ->
                _state.update { it.copy(titles = saved.map(::withCachedPoster), hasScanned = true) }
            }
        }
        viewModelScope.launch {
            MacSettings.login.collect { login ->
                val changed = login != _state.value.login
                _state.update { it.copy(login = login) }
                if (changed) {
                    _state.update { it.copy(titles = emptyList(), hasScanned = false, error = null) }
                    lastScanAt = 0
                    refresh()
                }
            }
        }
    }

    /** Re-reads the shared folder, at most every few seconds unless [force]d. */
    fun refresh(force: Boolean = false) {
        val current = _state.value
        if (current.login == null || current.isScanning) return
        if (!force && SystemClock.elapsedRealtime() - lastScanAt < RESCAN_INTERVAL_MS && current.hasScanned) return
        _state.update { it.copy(isScanning = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { MacShare.use { MacLibraryScanner.scan(it) } }
                }
            lastScanAt = SystemClock.elapsedRealtime()
            result
                .onSuccess { titles ->
                    current.login?.let { MacLibraryCache.save(getApplication(), it, titles) }
                    _state.update {
                        it.copy(titles = titles.map(::withCachedPoster), isScanning = false, hasScanned = true, error = null)
                    }
                    downloadPosters(titles)
                }.onFailure { error ->
                    Log.w(TAG, "Scan failed", error)
                    _state.update { it.copy(isScanning = false, hasScanned = true, error = message(error)) }
                }
        }
    }

    /** Tries [login]; saves it and loads the library when it works, otherwise reports why on the main thread. */
    fun connect(
        login: MacLogin,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val error =
                withContext(Dispatchers.IO) {
                    runCatching { MacShare.test(login) }.exceptionOrNull()
                }
            if (error == null) {
                MacShare.disconnect()
                MacSettings.save(login)
            }
            onResult(error?.let(::message))
        }
    }

    fun forget() {
        MacShare.disconnect()
        MacLibraryCache.clear(getApplication())
        MacSettings.clear()
    }

    /** Removes [title]'s folder (or loose video) from the Mac. It does not go to the Mac's Bin. */
    fun delete(
        title: MacTitle,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val error =
                withContext(Dispatchers.IO) {
                    runCatching {
                        MacShare.use { share ->
                            if (share.folderExists(title.folder)) share.rmdir(title.folder, true) else share.rm(title.folder)
                        }
                    }.exceptionOrNull()
                }
            if (error == null) {
                _state.update { state -> state.copy(titles = state.titles.filterNot { it.key == title.key }) }
                _state.value.login?.let { MacLibraryCache.save(getApplication(), it, _state.value.titles) }
            } else {
                Log.w(TAG, "Delete failed", error)
            }
            onResult(error?.let(::message))
        }
    }

    /**
     * Reads each video's length and saves a still from it for the episode tiles, one video at a time
     * and only for what isn't known yet. Both come from one read of the file over the network.
     */
    fun loadDetails(title: MacTitle) {
        val missing =
            title.videos.filter { video ->
                val saved = durationPrefs.getLong(durationKey(video), 0)
                if (saved > 0) _durations.update { it + (video.path to saved) }
                val still = stillFile(video)
                if (still.exists()) _stills.update { it + (video.path to still.path) }
                (saved <= 0 || !still.exists()) && probing.add(video.path)
            }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            missing.forEach { video ->
                val reading =
                    launch {
                        runCatching { probe(video) }.onFailure { Log.w(TAG, "Couldn't read ${video.path}", it) }
                        probing.remove(video.path)
                    }
                // A file without an index (a TS stream renamed .mp4) can take minutes to read over the
                // network; let it finish in the background instead of holding up the episodes after it.
                if (withTimeoutOrNull(PROBE_WAIT_MS) { reading.join() } == null) {
                    Log.w(TAG, "Still reading ${video.path}; moving on to the next episode")
                }
            }
        }
    }

    private fun probe(video: MacVideo) {
        val file = MacShare.openForReading(video.path)
        if (isTransportStream(file)) {
            // The TV reads these by scanning the whole file, and that stalls every video after it.
            Log.w(TAG, "${video.path} is a TS stream, not MP4; no still or length until it is converted")
            runCatching { file.close() }
            return
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(SmbMediaDataSource(file))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration > 0) {
                durationPrefs.edit().putLong(durationKey(video), duration).apply()
                _durations.update { it + (video.path to duration) }
            }
            val target = stillFile(video)
            if (!target.exists()) {
                // A quarter of the way in skips the opening titles and black frames.
                val atUs = if (duration > 0) duration * 1000 / 4 else STILL_FALLBACK_US
                val frame =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, STILL_WIDTH, STILL_HEIGHT)
                    } else {
                        retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                if (frame != null) {
                    val part = File(target.path + ".part")
                    part.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, STILL_QUALITY, it) }
                    frame.recycle()
                    if (part.renameTo(target)) _stills.update { it + (video.path to target.path) }
                }
            }
        } finally {
            runCatching { retriever.release() }
            runCatching { file.close() }
        }
    }

    /** An MPEG-TS stream starts with the 0x47 sync byte, repeated every 188 bytes. */
    private fun isTransportStream(file: SmbFile): Boolean {
        val head = ByteArray(TS_PACKET + 1)
        val read = runCatching { file.read(head, 0L) }.getOrDefault(0)
        return read > TS_PACKET && head[0] == TS_SYNC && head[TS_PACKET] == TS_SYNC
    }

    private fun stillFile(video: MacVideo): File = File(stillDir, "${sha1("${video.path}|${video.sizeBytes}")}.jpg")

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun downloadPosters(titles: List<MacTitle>) {
        val missing = titles.filter { it.posterPath != null && !posterFile(it).exists() }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            missing.forEach { title ->
                val target = posterFile(title)
                val downloaded =
                    runCatching {
                        val part = File(target.path + ".part")
                        MacShare.openForReading(title.posterPath!!).use { remote ->
                            part.outputStream().use { remote.read(it) }
                        }
                        part.renameTo(target)
                    }.getOrDefault(false)
                if (downloaded) {
                    dropOldPosters(target)
                    _state.update { state ->
                        state.copy(titles = state.titles.map { if (it.key == title.key) it.copy(posterFile = target.path) else it })
                    }
                }
            }
        }
    }

    private fun withCachedPoster(title: MacTitle): MacTitle {
        if (title.posterPath == null) return title
        val file = posterFile(title)
        return if (file.exists()) title.copy(posterFile = file.path) else title
    }

    /** The TV's copy of the poster, named after its path and version, so replacing the image on the Mac shows the new one. */
    private fun posterFile(title: MacTitle): File {
        val name = sha1(title.posterPath.orEmpty())
        val version = sha1(title.posterVersion).take(VERSION_CHARS)
        return File(posterDir, "$name-$version.${title.posterPath.orEmpty().substringAfterLast('.', "jpg").lowercase()}")
    }

    /** Removes older copies of the same poster once its new version has been downloaded. */
    private fun dropOldPosters(current: File) {
        val prefix = current.name.substringBefore('-')
        posterDir.listFiles()?.filter { it.name.startsWith(prefix) && it.name != current.name }?.forEach { it.delete() }
    }

    private fun durationKey(video: MacVideo) = "${video.path}|${video.sizeBytes}"

    private fun message(error: Throwable): String = (error as? MacException)?.message ?: error.message ?: "Something went wrong"

    /** Lets Android's metadata reader see a file on the Mac. */
    private class SmbMediaDataSource(
        private val file: SmbFile,
    ) : MediaDataSource() {
        private val size = file.fileInformation.standardInformation.endOfFile

        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int {
            if (position >= this.size) return -1
            return file.read(buffer, position, offset, size)
        }

        override fun getSize(): Long = size

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "MacLibrary"
        const val DURATION_PREFS = "mac_durations"
        const val RESCAN_INTERVAL_MS = 10_000L
        const val STILL_WIDTH = 480
        const val STILL_HEIGHT = 270
        const val STILL_QUALITY = 82
        const val STILL_FALLBACK_US = 300_000_000L
        private const val PROBE_WAIT_MS = 30_000L
        private const val TS_PACKET = 188
        private const val VERSION_CHARS = 10
        private const val TS_SYNC: Byte = 0x47
    }
}
