package com.saiesh.tele.presentation.library

import java.text.Normalizer

/** Series name, episode number and quality pulled out of a video's caption or file name. */
object VideoLabels {
    private val episodePatterns =
        listOf(
            Regex("""(?<!\p{L})(?:الحلقة|حلقة|Episode|Ep\.?|ح)\s*[:#-]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE),
            Regex("""(?<!\p{L})S\d{1,2}\s*E(\d{1,4})""", RegexOption.IGNORE_CASE),
            Regex("""(?<!\d)(\d{1,4})\.?\s*Bölüm""", RegexOption.IGNORE_CASE),
        )
    private val qualityPattern = Regex("""(?<![A-Za-z0-9])(4K|2160p|1080p|720p|480p|360p)(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

    // Words around a series name that differ between uploads of the same show.
    private val noiseWords =
        Regex(
            """(?<![\p{L}\p{N}])(4K|2160p|1080p|720p|480p|360p|FHD|HD|مترجم[ةه]?|مدبلج[ةه]?|كامل[ةه]?|مسلسل|انمي|أنمي|فيلم|بجودة|جودة)(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        )
    // Trailers, teasers and promo scenes channels post alongside episodes ("الإعلان الثاني للحلقة 35").
    private val promoWords =
        Regex(
            """(?<!\p{L})(?:ال)?(?:إعلان|اعلان|تشويقي|برومو|promo|trailer|teaser|fragman)(?!\p{L})""",
            RegexOption.IGNORE_CASE,
        )
    private val symbols = Regex("""[\p{So}\p{Cs}#_|\-–—:()\[\]{}.,!؟?«»"'*~]+""")
    private val spaces = Regex("""\s+""")

    // Kashida (letter stretching) and vowel marks, which hide words like "مسـلسل" from the patterns above.
    private val arabicDecoration = Regex("""[\u0640\u064B-\u065F\u0670]""")

    fun isPromo(text: String): Boolean = promoWords.containsMatchIn(plain(text))

    fun episode(text: String): Int? = firstEpisodeMatch(plain(text))?.groupValues?.get(1)?.toIntOrNull()

    /** The text before the episode marker, without quality tags and filler words; the whole caption when there is none. */
    fun seriesName(text: String): String {
        val plainText = plain(text)
        val match = firstEpisodeMatch(plainText)
        val raw = if (match != null && match.range.first > 0) plainText.substring(0, match.range.first) else plainText
        val name = clean(raw)
        return name.ifEmpty { clean(plainText) }
    }

    fun quality(text: String): String? {
        val match = qualityPattern.find(text)?.value?.lowercase() ?: return null
        return if (match == "4k" || match == "2160p") "4K" else match
    }

    /** Higher is better; unknown quality sorts last. */
    fun qualityRank(quality: String?): Int =
        when (quality) {
            "4K" -> 5
            "1080p" -> 4
            "720p" -> 3
            "480p" -> 2
            "360p" -> 1
            else -> 0
        }

    fun duration(totalSeconds: Int): String? {
        if (totalSeconds <= 0) return null
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun firstEpisodeMatch(text: String): MatchResult? = episodePatterns.mapNotNull { it.find(text) }.minByOrNull { it.range.first }

    /** Folds stylised letters (Arabic presentation forms, full-width digits) to plain ones and drops decoration. */
    private fun plain(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(arabicDecoration, "")

    private fun clean(text: String): String =
        text
            .replace(noiseWords, " ")
            .replace(symbols, " ")
            .replace(spaces, " ")
            .trim()
}
