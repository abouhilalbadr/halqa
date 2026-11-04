package com.saiesh.tele.core.mac

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.saiesh.tele.domain.model.MacInfo
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.domain.model.MacVideo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.EnumSet

/**
 * Turns the shared folder into titles: each folder with videos is a series (several videos) or a movie
 * (one video), with the folder's image as its poster. Videos lying loose in the shared folder are movies
 * of their own. Newest folders come first. An `info.json` in a folder adds its name, year, cast and summary.
 */
object MacLibraryScanner {
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "avi", "webm", "ts", "wmv", "flv", "3gp")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val POSTER_NAMES = listOf("poster", "cover", "folder")
    private const val INFO_FILE = "info.json"
    private const val MAX_INFO_BYTES = 64 * 1024L

    fun scan(share: DiskShare): List<MacTitle> {
        val root = visible(share.list(""))
        val folders =
            root
                .filter { it.isDirectory() }
                .mapNotNull { folder -> scanFolder(share, folder) }
        val looseMovies =
            root
                .filter { !it.isDirectory() && extension(it.fileName) in VIDEO_EXTENSIONS }
                .map { file ->
                    MacTitle(
                        folder = file.fileName,
                        name = cleanName(file.fileName),
                        videos = listOf(MacVideo(file.fileName, file.fileName, null, file.endOfFile)),
                        posterPath = null,
                        modifiedAt = file.lastWriteTime.toEpochMillis(),
                    )
                }
        return (folders + looseMovies).sortedByDescending { it.modifiedAt }
    }

    private fun scanFolder(
        share: DiskShare,
        folder: FileIdBothDirectoryInformation,
    ): MacTitle? {
        val entries = visible(share.list(folder.fileName))
        val videos =
            entries
                .filter { !it.isDirectory() && extension(it.fileName) in VIDEO_EXTENSIONS }
                .map { MacVideo(MacPaths.join(folder.fileName, it.fileName), it.fileName, episodeNumber(it.fileName), it.endOfFile) }
                .sortedWith(compareBy<MacVideo>({ it.number == null }, { it.number }, { it.fileName.lowercase() }))
        if (videos.isEmpty()) return null
        val info =
            entries
                .firstOrNull { !it.isDirectory() && it.fileName.equals(INFO_FILE, ignoreCase = true) }
                ?.let { readInfo(share, MacPaths.join(folder.fileName, it.fileName)) }
        val images = entries.filter { !it.isDirectory() && extension(it.fileName) in IMAGE_EXTENSIONS }
        val poster =
            images.firstOrNull { it.fileName == info?.poster }
                ?: images.firstOrNull { image -> POSTER_NAMES.any { image.fileName.lowercase().startsWith(it) } }
                ?: images.maxByOrNull { it.endOfFile }
        val newest = (entries.map { it.lastWriteTime.toEpochMillis() } + folder.lastWriteTime.toEpochMillis()).max()
        return MacTitle(
            folder = folder.fileName,
            name = info?.title ?: folder.fileName,
            videos = videos,
            posterPath = poster?.let { MacPaths.join(folder.fileName, it.fileName) },
            posterVersion = poster?.let { "${it.endOfFile}-${it.lastWriteTime.toEpochMillis()}" }.orEmpty(),
            modifiedAt = newest,
            info = info,
        )
    }

    /** Reads a folder's `info.json`; a missing, huge or broken file just means no details. */
    private fun readInfo(
        share: DiskShare,
        path: String,
    ): MacInfo? =
        try {
            val bytes = ByteArrayOutputStream()
            share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { file ->
                if (file.fileInformation.standardInformation.endOfFile > MAX_INFO_BYTES) return null
                file.read(bytes)
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF"))
            MacInfo(
                title = json.text("title"),
                year = json.optInt("year").takeIf { it > 0 },
                genres = json.optJSONArray("genres").texts(),
                network = json.text("network"),
                country = json.text("country"),
                director = json.text("director"),
                cast = json.optJSONArray("cast").texts(),
                summary = json.text("summary"),
                poster = json.text("poster"),
            )
        } catch (e: Exception) {
            Log.w("MacLibraryScanner", "Skipping unreadable $path", e)
            null
        }

    private fun JSONObject.text(key: String): String? = optString(key, "").trim().takeIf { it.isNotEmpty() }

    private fun JSONArray?.texts(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, "").trim().takeIf(String::isNotEmpty) }

    /** "S01E02", "E04", "Ep05", "Episode 6" and "الحلقة 7" all give the episode number. */
    fun episodeNumber(fileName: String): Int? {
        val name = toWesternDigits(fileName.substringBeforeLast('.'))
        val patterns =
            listOf(
                Regex("""(?i)S\d{1,2}[ ._-]?E(\d{1,4})"""),
                Regex("""(?i)(?:^|[^a-z])E(?:p(?:isode)?)?[ ._-]?(\d{1,4})(?!\d)"""),
                Regex("""الحلقة\s*(\d{1,4})"""),
            )
        for (pattern in patterns) {
            pattern.find(name)?.let { return it.groupValues[1].toInt() }
        }
        return null
    }

    /** "The.Movie.2024.1080p.mp4" → "The Movie 2024 1080p". */
    private fun cleanName(fileName: String): String =
        fileName
            .substringBeforeLast('.')
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()

    private fun toWesternDigits(text: String): String =
        buildString {
            text.forEach { char ->
                append(
                    when (char) {
                        in '٠'..'٩' -> '0' + (char - '٠')
                        in '۰'..'۹' -> '0' + (char - '۰')
                        else -> char
                    },
                )
            }
        }

    // macOS leaves "._name" and ".DS_Store" files on shared drives; they are never videos or posters.
    private fun visible(entries: List<FileIdBothDirectoryInformation>) =
        entries.filter { !it.fileName.startsWith(".") && !it.fileName.startsWith("~") }

    private fun FileIdBothDirectoryInformation.isDirectory(): Boolean =
        fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

    private fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
}
