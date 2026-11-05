package com.dari.screentranslator.model

import android.graphics.Rect

/** Where a piece of on-screen text came from. */
enum class TextSource {
    ACCESSIBILITY,
    OCR
}

/**
 * OCR script used by ML Kit text recognition when the accessibility tree
 * has no usable text (images, games, videos).
 */
enum class OcrScript {
    AUTO,
    LATIN,
    DEVANAGARI,
    CHINESE,
    JAPANESE,
    KOREAN
}

/** A readable piece of text with its position on the screen. */
data class ScreenTextItem(
    val id: Long,
    val text: String,
    val bounds: Rect,
    val source: TextSource
)

/** A translated piece of text, ready to be drawn over the original. */
data class TranslatedBox(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val bounds: Rect,
    val source: TextSource
)
