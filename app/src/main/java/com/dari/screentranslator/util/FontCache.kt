package com.dari.screentranslator.util

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Loads the bundled Vazirmatn font once and caches it for the whole process.
 * If the TTFs are missing from assets the app silently falls back to the
 * system default font, so nothing crashes.
 */
object FontCache {

    private const val REGULAR_ASSET = "fonts/Vazirmatn-Regular.ttf"
    private const val BOLD_ASSET = "fonts/Vazirmatn-Bold.ttf"

    @Volatile
    private var regular: Typeface? = null

    @Volatile
    private var bold: Typeface? = null

    @Volatile
    private var composeFamily: FontFamily? = null

    fun regular(context: Context): Typeface? {
        regular?.let { return it }
        synchronized(this) {
            regular?.let { return it }
            val loaded = try {
                Typeface.createFromAsset(context.applicationContext.assets, REGULAR_ASSET)
            } catch (t: Throwable) {
                null
            }
            if (loaded != null) regular = loaded
            return loaded
        }
    }

    fun bold(context: Context): Typeface? {
        bold?.let { return it }
        synchronized(this) {
            bold?.let { return it }
            val loaded = try {
                Typeface.createFromAsset(context.applicationContext.assets, BOLD_ASSET)
            } catch (t: Throwable) {
                null
            }
            if (loaded != null) bold = loaded
            return loaded
        }
    }

    /** A Compose [FontFamily] built from the bundled typeface, or null when unavailable. */
    fun composeFontFamily(context: Context): FontFamily? {
        composeFamily?.let { return it }
        val typeface = regular(context) ?: return null
        val family = FontFamily(typeface)
        composeFamily = family
        return family
    }
}
