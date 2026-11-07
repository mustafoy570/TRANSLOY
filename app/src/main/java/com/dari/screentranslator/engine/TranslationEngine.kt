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
 *
 * OFFLINE-FIRST CONTRACT (v1.3.0):
 *  - When both the source model and the Persian model are already on the
 *    device, translation goes through a pure local path and never touches
 *    the network layer. This is instant and works in airplane mode.
 *  - When a model is missing and the device is ONLINE, it is downloaded on
 *    the spot (one time only) and the translation then completes.
 *  - When a model is missing and the device is OFFLINE, the group is skipped
 *    IMMEDIATELY with a clear, actionable event — no 60-second network
 *    timeout and no cryptic ML Kit exception.
 */
object TranslationEngine {

    sealed interface Event {
        data class ModelDownloadStarted(val languageName: String) : Event
        data object NothingToTranslate : Event

        /** Everything readable on the screen is already Persian/Dari. */
        data object AllTextAlreadyPersian : Event

        /** Pashto was detected, but ML Kit has no offline Pashto model. */
        data object PashtoNotSupported : Event

        /** Translation was skipped because packs are missing and we are offline. */
        data class ModelsMissingOffline(val languageNames: List<String>) : Event

        data class Failed(val message: String) : Event
    }

    private const val CACHE_LIMIT = 500
    private val cache = LinkedHashMap<String, String>(64, 0.75f, true)

    /** Pashto-only letters: ML Kit has no Pashto model, so text containing
     *  these is reported honestly instead of as "nothing to translate". */
    private val PASHTO_ONLY_LETTERS = "ټځڅډړږښګڼۍ"

    /** Arabic/Persian script block: unidentified text in this range is
     *  treated as Persian/Dari (nothing to translate). */
    private val PERSIAN_SCRIPT_RANGE = '\u0600'..'\u06FF'

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
        isOnline: Boolean,
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
            // Language identification failed; fall back to script sniffing.
        }

        // 2. Dominant confident language on the screen — the fallback for
        //    short texts that ML Kit reports as "und" (buttons, labels).
        val dominant = tagByText.values.asSequence()
            .mapNotNull { it }
            .mapNotNull { TranslateLanguage.fromLanguageTag(it) }
            .filter { it != TranslateLanguage.PERSIAN }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: TranslateLanguage.ENGLISH

        // 3. Assign each text a source language. Short unidentified texts
        //    ("und" — ML Kit cannot classify 1-2 word labels) get a
        //    script-based best guess instead of being skipped silently.
        var sawPersian = false
        var sawPashto = false
        val sourceLangByText = HashMap<String, String>()
        for (text in uniqueTexts) {
            val tag = tagByText[text]
            val language = tag?.let { TranslateLanguage.fromLanguageTag(it) }
            when {
                language != null && language != TranslateLanguage.PERSIAN ->
                    sourceLangByText[text] = language
                language == TranslateLanguage.PERSIAN -> sawPersian = true
                else -> when {
                    // Pashto ("ps") is not part of ML Kit Translate — tell
                    // the user instead of silently reporting "nothing".
                    tag != null && tag.startsWith("ps", ignoreCase = true) ->
                        sawPashto = true
                    text.any { it in PASHTO_ONLY_LETTERS } -> sawPashto = true
                    text.any { it in PERSIAN_SCRIPT_RANGE } -> sawPersian = true
                    text.any { it in 'A'..'Z' || it in 'a'..'z' } ->
                        sourceLangByText[text] = dominant
                    // Digits and symbols only — nothing to translate.
                }
            }
        }
        if (sourceLangByText.isEmpty()) {
            onEvent(
                when {
                    sawPashto -> Event.PashtoNotSupported
                    sawPersian -> Event.AllTextAlreadyPersian
                    else -> Event.NothingToTranslate
                }
            )
            return@withContext emptyList()
        }

        // 2. Translate each language group with a dedicated translator.
        val textsByLanguage = HashMap<String, MutableList<String>>()
        for ((text, language) in sourceLangByText) {
            textsByLanguage.getOrPut(language) { mutableListOf() }.add(text)
        }

        val missingOffline = LinkedHashSet<String>()
        var lastFailure: String? = null
        for ((language, texts) in textsByLanguage) {
            val uncached = texts.filter { cacheGet("$language|$it") == null }
            if (uncached.isEmpty()) continue

            val sourceReady = ModelManager.isDownloaded(language)
            val targetReady = ModelManager.isDownloaded(ModelManager.TARGET_LANGUAGE)

            when {
                sourceReady && targetReady ->
                    // Pure offline path — the models live on this phone, so
                    // never call downloadModelIfNeeded (which would let the
                    // network layer stall an airplane-mode translation).
                    translateWith(language, uncached, downloadFirst = false) {
                        lastFailure = it
                    }

                !isOnline -> {
                    // Fail FAST with a clear reason instead of hanging on a
                    // network timeout that can never succeed offline.
                    if (!sourceReady) missingOffline.add(ModelManager.displayName(language))
                    if (!targetReady) {
                        missingOffline.add(ModelManager.displayName(ModelManager.TARGET_LANGUAGE))
                    }
                }

                else -> {
                    onEvent(Event.ModelDownloadStarted(ModelManager.displayName(language)))
                    translateWith(language, uncached, downloadFirst = true) {
                        lastFailure = it
                    }
                }
            }
        }

        // Surface skipped languages even when some boxes were produced, so a
        // half-translated screen is never mistaken for a complete one.
        if (missingOffline.isNotEmpty()) {
            onEvent(Event.ModelsMissingOffline(missingOffline.toList()))
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

    /**
     * Translates [texts] from [language] into Persian. When [downloadFirst]
     * is true the missing model is downloaded first (online path only).
     */
    private suspend fun translateWith(
        language: String,
        texts: List<String>,
        downloadFirst: Boolean,
        onFail: (String) -> Unit
    ) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(language)
            .setTargetLanguage(ModelManager.TARGET_LANGUAGE)
            .build()
        val translator = Translation.getClient(options)
        try {
            if (downloadFirst) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            }
            for (text in texts) {
                cachePut("$language|$text", translator.translate(text).await())
            }
        } catch (t: Throwable) {
            onFail(t.message ?: t.javaClass.simpleName)
        } finally {
            translator.close()
        }
    }
}
