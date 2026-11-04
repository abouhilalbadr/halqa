package com.saiesh.tele.core.mac

import android.content.Context
import android.util.Log
import com.saiesh.tele.domain.model.MacInfo
import com.saiesh.tele.domain.model.MacTitle
import com.saiesh.tele.domain.model.MacVideo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last list read from the Mac, kept on the TV so home can show it straight away when the app is reopened,
 * while a fresh scan runs. It is tied to the Mac and shared folder it came from; no password is stored.
 */
object MacLibraryCache {
    private const val TAG = "MacLibraryCache"
    private const val FILE_NAME = "mac_library.json"
    private const val VERSION = 1

    fun load(
        context: Context,
        login: MacLogin,
    ): List<MacTitle>? =
        runCatching {
            val file = file(context)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optInt("version") != VERSION || json.optString("source") != source(login)) return null
            val titles = json.getJSONArray("titles")
            (0 until titles.length()).map { readTitle(titles.getJSONObject(it)) }
        }.onFailure { Log.w(TAG, "Couldn't read the saved library", it) }.getOrNull()

    fun save(
        context: Context,
        login: MacLogin,
        titles: List<MacTitle>,
    ) {
        runCatching {
            val json =
                JSONObject()
                    .put("version", VERSION)
                    .put("source", source(login))
                    .put("titles", JSONArray(titles.map(::writeTitle)))
            val target = file(context)
            val part = File(target.path + ".part")
            part.writeText(json.toString())
            part.renameTo(target)
        }.onFailure { Log.w(TAG, "Couldn't save the library", it) }
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun source(login: MacLogin) = "${login.host}|${login.share}|${login.user}"

    private fun writeTitle(title: MacTitle): JSONObject =
        JSONObject()
            .put("folder", title.folder)
            .put("name", title.name)
            .put("posterPath", title.posterPath)
            .put("posterVersion", title.posterVersion)
            .put("modifiedAt", title.modifiedAt)
            .put("info", title.info?.let(::writeInfo))
            .put(
                "videos",
                JSONArray(
                    title.videos.map { video ->
                        JSONObject()
                            .put("path", video.path)
                            .put("fileName", video.fileName)
                            .put("number", video.number)
                            .put("sizeBytes", video.sizeBytes)
                    },
                ),
            )

    private fun readTitle(json: JSONObject): MacTitle {
        val videos = json.getJSONArray("videos")
        return MacTitle(
            folder = json.getString("folder"),
            name = json.getString("name"),
            videos =
                (0 until videos.length()).map {
                    val video = videos.getJSONObject(it)
                    MacVideo(
                        path = video.getString("path"),
                        fileName = video.getString("fileName"),
                        number = if (video.has("number")) video.getInt("number") else null,
                        sizeBytes = video.getLong("sizeBytes"),
                    )
                },
            posterPath = json.text("posterPath"),
            posterVersion = json.optString("posterVersion"),
            modifiedAt = json.optLong("modifiedAt"),
            info = json.optJSONObject("info")?.let(::readInfo),
        )
    }

    private fun writeInfo(info: MacInfo): JSONObject =
        JSONObject()
            .put("title", info.title)
            .put("year", info.year)
            .put("genres", JSONArray(info.genres))
            .put("network", info.network)
            .put("country", info.country)
            .put("director", info.director)
            .put("cast", JSONArray(info.cast))
            .put("summary", info.summary)
            .put("poster", info.poster)

    private fun readInfo(json: JSONObject) =
        MacInfo(
            title = json.text("title"),
            year = if (json.has("year")) json.getInt("year") else null,
            genres = json.strings("genres"),
            network = json.text("network"),
            country = json.text("country"),
            director = json.text("director"),
            cast = json.strings("cast"),
            summary = json.text("summary"),
            poster = json.text("poster"),
        )

    /** org.json leaves out null values when writing, so a missing key reads back as null. */
    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
}
