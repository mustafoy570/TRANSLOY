package com.dari.screentranslator.engine

import com.dari.screentranslator.model.ScreenTextItem
import com.dari.screentranslator.model.TranslatedBox
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Detects the source language of every unique text on the screen with ML Kit
 * Language ID, then translates into Persian with ML Kit on-device Translate.
 * Repeated strings are served from an in-memory LRU-style cache.
 */
object TranslationEngine {

    sealed interface Event {
        data class ModelDownloadStarted(val languageName: String) : Event
        data object NothingToTranslate : Event
        data class Failed(val message: String) : Event
    }

    private const val CACHE_LIMIT = 500
    private val cache = LinkedHashMap<String, String>(64, 0.75f, true)

    @Synchronized
    private fun cacheGet(key: String): String? = cache[key]

    @Synchronized
    private fun cachePut(key: String, value: String) {
        if (cache.size >= CACHE_LIMIT) {
            val iterator = cache.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        cache[key] = value
    }

    suspend fun translate(
        items: List<ScreenTextItem>,
        onEvent: (Event) -> Unit
    ): List<TranslatedBox> = withContext(Dispatchers.Default) {
        val cleaned = items.asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.length >= 2 }
            .toList()
        if (cleaned.isEmpty()) return@withContext emptyList()

        val uniqueTexts = cleaned.map { it.text }.distinct()

        // 1. Detect the language of every unique string.
        val tagByText = HashMap<String, String?>()
        try {
            val identifier = LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder()
                    .setConfidenceThreshold(0.35f)
                    .build()
            )
            try {
                for (text in uniqueTexts) {
                    tagByText[text] = identifier.identifyLanguage(text).await()
                }
            } finally {
                identifier.close()
            }
        } catch (t: Throwable) {
            // Language identification failed; nothing can be translated.
        }

        val sourceLangByText = HashMap<String, String>()
        for (text in uniqueTexts) {
            val tag = tagByText[text]
            val language = if (tag != null) TranslateLanguage.fromLanguageTag(tag) else null
            if (language != null && language != TranslateLanguage.PERSIAN) {
                sourceLangByText[text] = language
            }
        }
        if (sourceLangByText.isEmpty()) {
            onEvent(Event.NothingToTranslate)
            return@withContext emptyList()
        }

        // 2. Translate each language group with a dedicated translator.
        val textsByLanguage = HashMap<String, MutableList<String>>()
        for ((text, language) in sourceLangByText) {
            textsByLanguage.getOrPut(language) { mutableListOf() }.add(text)
        }

        var lastFailure: String? = null
        for ((language, texts) in textsByLanguage) {
            val uncached = texts.filter { cacheGet("$language|$it") == null }
            if (uncached.isEmpty()) continue

            if (!ModelManager.isDownloaded(language) ||
                !ModelManager.isDownloaded(ModelManager.TARGET_LANGUAGE)
            ) {
                onEvent(Event.ModelDownloadStarted(ModelManager.displayName(language)))
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(language)
                .setTargetLanguage(ModelManager.TARGET_LANGUAGE)
                .build()
            val translator = Translation.getClient(options)
            try {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                for (text in uncached) {
                    cachePut("$language|$text", translator.translate(text).await())
                }
            } catch (t: Throwable) {
                lastFailure = t.message ?: t.javaClass.simpleName
            } finally {
                translator.close()
            }
        }

        // 3. Build overlay boxes for every item that has a translation.
        val boxes = ArrayList<TranslatedBox>(cleaned.size)
        for (item in cleaned) {
            val language = sourceLangByText[item.text] ?: continue
            val translated = cacheGet("${language}|${item.text}") ?: continue
            if (translated.isBlank()) continue
            boxes.add(TranslatedBox(item.id, item.text, translated, item.bounds, item.source))
        }
        if (boxes.isEmpty() && lastFailure != null) {
            onEvent(Event.Failed(lastFailure))
        }
        boxes
    }
}
