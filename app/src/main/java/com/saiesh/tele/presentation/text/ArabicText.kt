package com.saiesh.tele.presentation.text

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.saiesh.tele.R
import java.util.Locale

/**
 * Arabic support for text that comes from Telegram or the Mac: Arabic gets the Tajawal font (the system
 * font has no matching Arabic), and pages about an Arabic title read right to left with Arabic labels.
 */
object ArabicText {
    private var tajawal: Typeface? = null

    fun isArabic(text: CharSequence?): Boolean =
        text != null &&
            text.any { char ->
                char in '؀'..'ۿ' ||
                    char in 'ݐ'..'ݿ' ||
                    char in 'ࢠ'..'ࣿ' ||
                    char in 'ﭐ'..'﷿' ||
                    char in 'ﹰ'..'﻿'
            }

    /** Sets [text] on [view], switching to Tajawal when it contains Arabic and back otherwise. */
    fun setText(
        view: TextView,
        text: CharSequence?,
    ) {
        view.text = text
        style(view, isArabic(text))
    }

    /** Gives [view] Tajawal ([arabic]) or the default font, keeping its bold or regular weight. */
    fun style(
        view: TextView,
        arabic: Boolean,
    ) {
        val bold = view.typeface?.isBold == true
        val family = if (arabic) font(view.context) else null
        view.setTypeface(family ?: Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /** Resources in Arabic, for the labels on an Arabic title's page whatever the TV's language. */
    fun arabicResources(context: Context): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(ARABIC) }
        return context.createConfigurationContext(config).resources
    }

    private fun font(context: Context): Typeface? =
        tajawal ?: runCatching { ResourcesCompat.getFont(context, R.font.tajawal) }.getOrNull()?.also { tajawal = it }

    /** Arabic with Western digits (1, 2, 3), matching the episode numbers on the tiles. */
    private val ARABIC: Locale = Locale.forLanguageTag("ar-u-nu-latn")
}
