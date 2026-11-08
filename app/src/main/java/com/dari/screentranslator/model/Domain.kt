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

/**
 * What part of the screen is translated (v1.5).
 * WHOLE_SCREEN: every visible text at once.
 * NEAR_BUTTON: only the text close to the button, so the user can point the
 * button at one paragraph/sentence at a time. Pointing is done with a
 * LONG-PRESS next to the text (dragging only moves the button).
 */
enum class TranslateScope {
    WHOLE_SCREEN,
    NEAR_BUTTON
}

/**
 * What happens to translations that are currently visible when the user
 * scrolls the underlying page (feeds like Telegram move the text away).
 */
enum class ScrollBehavior {
    HIDE_ON_SCROLL,
    KEEP
}

/**
 * v1.5.0 — who decides when Google's ML Kit language models get updated:
 *  ASK_FIRST  ask the user first (a small dialog, at most once a day);
 *  AUTO_WIFI  check and update silently, Wi-Fi only;
 *  NEVER      never touch the network for updates (manual button only).
 */
enum class ModelUpdatePolicy {
    ASK_FIRST,
    AUTO_WIFI,
    NEVER
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
