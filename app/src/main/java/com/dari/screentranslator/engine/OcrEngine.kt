package com.dari.screentranslator.engine

import android.graphics.Bitmap
import android.graphics.Rect
import com.dari.screentranslator.model.OcrScript
import com.dari.screentranslator.model.ScreenTextItem
import com.dari.screentranslator.model.TextSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device OCR over a screen bitmap using ML Kit text recognition.
 * Bundled scripts: Latin, Chinese, Japanese, Korean and Devanagari.
 * AUTO tries Latin first and falls through to the other scripts until
 * it finds enough characters.
 *
 * v1.5.0 garbage filter (28-screenshot field feedback):
 *  - Lines clipped by the status bar / navigation bar (top & bottom 3.5% of
 *    the screen) are dropped — they produced junk like "Ann badaes".
 *  - Low-confidence lines (< 0.58) are dropped — a music note ♪ misread as
 *    a word, a ✓ misread as "0".
 *  - Square icon-sized blobs with ≤ 2 characters are dropped — app icons and
 *    emoji that OCR "reads" but are not text.
 *  - Roman-numeral noise (a string of pure I/V/X/L/C/D/M letters) is dropped —
 *    Arabic calligraphy is frequently hallucinated as "XVIII"-style numerals.
 */
object OcrEngine {

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap, script: OcrScript): List<ScreenTextItem> =
        withContext(Dispatchers.Default) {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizers: List<TextRecognizer> = when (script) {
                OcrScript.LATIN -> listOf(latinRecognizer)
                OcrScript.DEVANAGARI -> listOf(devanagariRecognizer)
                OcrScript.CHINESE -> listOf(chineseRecognizer)
                OcrScript.JAPANESE -> listOf(japaneseRecognizer)
                OcrScript.KOREAN -> listOf(koreanRecognizer)
                OcrScript.AUTO -> listOf(
                    latinRecognizer,
                    devanagariRecognizer,
                    chineseRecognizer,
                    japaneseRecognizer,
                    koreanRecognizer
                )
            }

            var bestLines: List<Text.Line> = emptyList()
            for (recognizer in recognizers) {
                val result = try {
                    recognizer.process(image).await()
                } catch (t: Throwable) {
                    null
                }
                val lines = result?.blocks?.flatMap { block -> block.lines } ?: emptyList()
                if (lines.size > bestLines.size) bestLines = lines
                if (script != OcrScript.AUTO) break
                if (recognizer === latinRecognizer) {
                    // Trust the Latin pass as final only when it found real
                    // Latin words. Clock digits, battery numbers or Latin
                    // misreads of CJK/Devanagari must not stop the fallback
                    // chain before the right recognizer has run.
                    val joined = bestLines.joinToString("") { it.text }
                    val letters = joined.count { it.isLetter() }
                    if (letters >= 8 && letters * 5 >= joined.length * 3) break
                } else if (bestLines.sumOf { it.text.length } >= 8) {
                    break
                }
            }

            var id = 0L
            val valid = bestLines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.length < 2 || box.width() < 8 || box.height() < 10) {
                    return@mapNotNull null
                }
                // 1. Clipped by the status bar or the gesture navigation bar.
                if (box.top < bitmap.height * EDGE_CUTOFF_RATIO ||
                    box.bottom > bitmap.height * (1f - EDGE_CUTOFF_RATIO)
                ) {
                    return@mapNotNull null
                }
                // 2. Low confidence — ML Kit reports confidence in [0,1],
                //    or a negative value when it was not computed (keep those).
                val confidence = line.confidence
                if (confidence in 0.001f..MIN_CONFIDENCE) {
                    return@mapNotNull null
                }
                // 3. Square icon/emoji blobs with almost no text inside.
                val aspect = box.width().toFloat() / box.height().coerceAtLeast(1)
                if (aspect in 0.75f..1.35f && text.length <= 2) {
                    return@mapNotNull null
                }
                // 4. Roman-numeral noise from calligraphy and decorations.
                if (isRomanNumeralNoise(text)) {
                    return@mapNotNull null
                }
                LineInfo(text, Rect(box))
            }
            mergeIntoParagraphs(valid, id)
        }

    /** Pure I/V/X/L/C/D/M (+ separators) with at least 3 numeral letters. */
    private fun isRomanNumeralNoise(text: String): Boolean {
        var numerals = 0
        for (c in text) {
            when (c) {
                'I', 'V', 'X', 'L', 'C', 'D', 'M', 'i', 'v', 'x', 'l', 'c', 'm' -> numerals++
                'W', 'w', 'E', 'e', ' ', '|', '.', '-' -> { /* tolerated filler */ }
                else -> return false
            }
        }
        return numerals >= 3
    }

    /** One OCR line worth keeping, with its bounds. */
    private class LineInfo(val text: String, val box: Rect)

    /** A paragraph being assembled from consecutive lines. */
    private class Paragraph {
        val sb = StringBuilder()
        var bounds = Rect(0, 0, 0, 0)
        var lastLineHeight = 0
        var initialized = false

        fun append(line: LineInfo) {
            if (initialized) sb.append(' ') else initialized = true
            sb.append(line.text)
            if (bounds.isEmpty) {
                bounds = Rect(line.box)
            } else {
                bounds.union(line.box)
            }
            lastLineHeight = line.box.height()
        }
    }

    /**
     * THE "COMBINING OR RUINING" FIX (v1.5).
     *
     * OCR returns the screen as dozens of single LINES. Translating line by
     * line breaks sentences in half, and the old Y-sorted "merge with the
     * previous paragraph" glued SIDE-BY-SIDE columns, buttons and app-icon
     * labels into one nonsense string ("Reply Archive Notify Delete" as one
     * sentence).
     *
     * So lines are first merged into paragraphs: a line belongs to a paragraph
     * when it sits strictly BELOW it in the SAME COLUMN — never next to it —
     * with a small vertical gap, a similar height, an aligned left edge and
     * real horizontal overlap. Only whole paragraphs go to the translator.
     */
    private fun mergeIntoParagraphs(lines: List<LineInfo>, startId: Long): List<ScreenTextItem> {
        val sorted = lines.sortedWith(
            compareBy({ it.box.top }, { it.box.left })
        )
        val paragraphs = ArrayList<Paragraph>()
        for (line in sorted) {
            // Match the most RECENT paragraph of the same column, not simply
            // the last one created — two columns interleave while sorting by
            // height, and each line must continue its own column.
            val target = paragraphs.lastOrNull { belongsTogether(it, line) }
            if (target != null) {
                target.append(line)
            } else {
                paragraphs.add(Paragraph().apply { append(line) })
            }
        }
        var id = startId
        return paragraphs.mapNotNull { p ->
            val text = p.sb.toString().trim()
            if (text.length < 2) null
            else ScreenTextItem(id++, text, p.bounds, TextSource.OCR)
        }
    }

    /**
     * Column-safe "same paragraph" heuristic for two consecutive lines.
     *
     * SIDE-BY-SIDE REJECTION (the v1.5.0 bug): when the new line overlaps the
     * previous line vertically by more than 40% of the shorter height, the two
     * sit NEXT TO each other (two columns, two buttons, icon labels) — they
     * can never be one paragraph, whatever their horizontal overlap says.
     */
    private fun belongsTogether(paragraph: Paragraph, line: LineInfo): Boolean {
        val prev = paragraph.bounds

        // 1. Vertical overlap → side-by-side content → different paragraphs.
        val verticalOverlap =
            minOf(prev.bottom, line.box.bottom) - maxOf(prev.top, line.box.top)
        val minHeight = minOf(prev.height(), line.box.height()).coerceAtLeast(1)
        if (verticalOverlap > minHeight * 0.4f) return false

        // 2. Vertical gap: small (possibly slightly negative) keeps the
        //    paragraph together; a large gap starts a new block.
        val gap = line.box.top - prev.bottom
        val refHeight = maxOf(paragraph.lastLineHeight, line.box.height, 1)
        if (gap < -(refHeight * 0.2f)) return false // heavy overlap: other column
        if (gap > refHeight * 0.55f) return false   // clearly a new block

        // 3. Similar text height (a caption next to a title is not the title).
        val heightRatio = line.box.height().toFloat() /
            paragraph.lastLineHeight.coerceAtLeast(1).toFloat()
        if (heightRatio !in 0.72f..1.38f) return false

        // 4. Same column: aligned left edge AND at least half of the narrower
        //    line horizontally inside the previous one.
        val leftEdgeDiff = kotlin.math.abs(prev.left - line.box.left)
        val overlapLeft = maxOf(prev.left, line.box.left)
        val overlapRight = minOf(prev.right, line.box.right)
        val overlap = (overlapRight - overlapLeft).coerceAtLeast(0)
        val narrower = minOf(prev.width(), line.box.width()).coerceAtLeast(1)
        return leftEdgeDiff <= refHeight * 1.5f && overlap >= narrower * 0.5f
    }

    private companion object {
        /** Status-bar / navigation-bar clip zone. */
        const val EDGE_CUTOFF_RATIO = 0.035f

        /** Below this confidence an OCR line is noise, not text. */
        const val MIN_CONFIDENCE = 0.58f
    }
}
