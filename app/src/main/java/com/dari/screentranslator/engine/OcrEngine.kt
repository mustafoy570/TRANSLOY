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
            bestLines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.length < 2 || box.width() < 8 || box.height() < 10) {
                    null
                } else {
                    ScreenTextItem(id++, text, Rect(box), TextSource.OCR)
                }
            }
        }
}
