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
 *
 * v1.5.0 DARI POLISH (28-screenshot field feedback):
 *  - Source texts are cleaned before translation (garbage prefixes, soft
 *    line breaks, doubled spaces, " | " separators).
 *  - Clock digits, battery percentages, e-mails, URLs, USSD codes and
 *    no-letter strings are never sent to the translator.
 *  - Every result passes through [polishDariTranslation]: Afghan month
 *    names (فبروری، جنوری، اپریل، آگست), Afghan UI wording (اطلاعیه‌ها not
 *    اطلاع‌رسانی‌ها، هزینه تماس not اتهامات تماس), and plural ها/های glued
 *    with ZWNJ so "ها" never drifts to the wrong side of the sentence.
 *  - Output is wrapped in a Right-to-Left Mark (U+200F) and embedded Latin
 *    fragments (numbers, brands, e-mails) in directional isolates (U+2066…
 *    U+2069), so mixed Dari/Latin lines read in the right order everywhere.
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

    /** Right-to-Left Mark — forces the paragraph direction of the output. */
    private const val RLM = "\u200F"

    /** Zero-Width Non-Joiner — glues Persian plural suffixes to their word. */
    private const val ZWNJ = "\u200C"

    /** LTR isolate start / terminator (U+2066…U+2069). */
    private const val LRI = "\u2066"
    private const val PDI = "\u2069"

    private val EMAIL_OR_URL_REGEX = Regex(
        "^(https?://|www\\.)\\S+$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    )

    /** Screen furniture that must never reach the translator. */
    private val GARBAGE_PREFIX_REGEX = Regex("^[0O•●▪✔✓:]\\s+")

    /**
     * UI strings ML Kit translates with Iranian wording; the app speaks
     * Afghan Dari. Exact source match → exact Dari replacement.
     */
    private val DARI_UI_OVERRIDES = mapOf(
        "unmute" to "لغو بی‌صدا",
        "mute" to "بی‌صدا",
        "pinned message" to "پیام سنجاق‌شده",
        "notifications" to "اطلاعیه‌ها",
        "notification" to "اطلاعیه",
        "call charges may apply" to "ممکن است هزینه تماس دریافت شود",
        "online" to "آنلاین",
        "typing" to "در حال نوشتن…",
        "draft" to "پیش‌نویس",
        "last seen recently" to "آخرین بازدید اخیراً",
        "reply" to "پاسخ",
        "forward" to "ارسال به دیگری",
        "archive" to "بایگانی",
        "delete" to "حذف",
        "settings" to "تنظیمات",
        "search" to "جستجو",
        "share" to "هم‌رسانی",
        "cancel" to "لغو",
        "save to gallery" to "ذخیره در گالری",
        "view profile" to "دیدن پروفایل"
    )

    /** Iranian month names → the Afghan month names used in Dari. */
    private val AFGHAN_MONTH_FIXES = listOf(
        "ژانویه" to "جنوری",
        "فوریه" to "فبروری",
        "آوریل" to "اپریل",
        "ژوئن" to "جون",
        "ژوئیه" to "جولای",
        "اوت" to "آگست"
    )

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
            .map { it.copy(text = cleanSourceText(it.text)) }
            .filter { !shouldSkipTranslation(it.text) }
            .map { it.copy(text = normalizeSourceText(it.text)) }
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

        // 3. Build overlay boxes for every item that has a translation,
        //    each one passed through the Dari polish pass.
        val boxes = ArrayList<TranslatedBox>(cleaned.size)
        for (item in cleaned) {
            val language = sourceLangByText[item.text] ?: continue
            val translated = cacheGet("${language}|${item.text}") ?: continue
            if (translated.isBlank()) continue
            boxes.add(
                TranslatedBox(
                    item.id,
                    item.text,
                    polishDariTranslation(item.text, translated),
                    item.bounds,
                    item.source
                )
            )
        }
        if (boxes.isEmpty()) {
            lastFailure?.let { onEvent(Event.Failed(it)) }
        }
        boxes
    }

    /**
     * Translates [texts] from [language] into Persian. When [downloadFirst]
     * is true the missing model is downloaded first (online path only).
     * Strings containing " | " / " • " separators are split, translated part
     * by part and re-joined — otherwise ML Kit scrambles the pipe-separated
     * pieces into one wrong sentence.
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
                cachePut("$language|$text", translateSingleText(translator, text))
            }
        } catch (t: Throwable) {
            onFail(t.message ?: t.javaClass.simpleName)
        } finally {
            translator.close()
        }
    }

    /** Splits separator-joined strings so each piece is translated on its own. */
    private suspend fun translateSingleText(
        translator: com.google.mlkit.nl.translate.Translator,
        text: String
    ): String {
        val separators = listOf(" | ", " • ", " •")
        val separator = separators.firstOrNull { text.contains(it) } ?: return translator.translate(text).await()
        val parts = text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return translator.translate(text).await()
        val translatedParts = parts.map { part ->
            runCatching { translator.translate(part).await() }.getOrDefault(part)
        }
        return translatedParts.joinToString("$RLM ${separator.trim()} $RLM")
    }

    // ------------------------------------------------------------ text hygiene

    /**
     * Removes OCR/accessibility junk BEFORE language detection: soft line
     * breaks, leading bullet/checkmark/zero noise and doubled spaces.
     * " | " / " • " separators are KEPT — [translateSingleText] needs them to
     * split the string and translate each piece on its own.
     */
    internal fun cleanSourceText(raw: String): String = raw
        .replace('\n', ' ')
        .replace(GARBAGE_PREFIX_REGEX, "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /**
     * Joins the soft line breaks ML Kit left inside one paragraph ("Call
     * charges" read as "Call\ncharges") back into normal spaces. Real list
     * items (starting with •, - or a digit) keep their line structure.
     */
    private fun normalizeSourceText(raw: String): String = raw
        .replace(Regex("(?<![.!?:|•\\n])\\n(?!\\s*[•\\-0-9])"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    /** Clock digits, 87%, *555#, e-mails, URLs, no-letter strings: skip. */
    internal fun shouldSkipTranslation(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return true
        if (EMAIL_OR_URL_REGEX.containsMatchIn(trimmed)) return true
        if (trimmed.none { it.isLetter() }) return true // "12:45", "87%", "*555#"
        if (trimmed.matches(Regex("\\d{1,2}[:/]\\d{2}([,:]\\d{2})?(\\s?[APap][Mm])?"))) return true
        return false
    }

    /**
     * Turns ML Kit's Iranian-flavoured Persian into Afghan Dari and makes the
     * line RTL-safe:
     *  1. Exact-match UI overrides ("unmute" → «لغو بی‌صدا» …).
     *  2. Month-name fixes (فوریه → فبروری …) and wording fixes.
     *  3. Plural ها/های attached to the previous word with ZWNJ.
     *  4. Pure-Latin runs (numbers, brands, e-mails) wrapped in LTR isolates.
     *  5. The whole line prefixed with RLM so any TextView renders it RTL.
     */
    internal fun polishDariTranslation(source: String, translated: String): String {
        DARI_UI_OVERRIDES[source.lowercase().trim()]?.let { return "$RLM$it" }

        var fixed = translated.trim()
        for ((iranian, afghan) in AFGHAN_MONTH_FIXES) {
            fixed = fixed.replace(iranian, afghan)
        }
        fixed = fixed
            .replace("اتهامات تماس", "هزینه تماس")
            .replace("هزینه‌های تماس", "هزینه تماس")
            .replace("اطلاع‌رسانی‌ها", "اطلاعیه‌ها")
            .replace("اطلاع‌رسانی", "اطلاعیه")
            // "ها" / "های" detached from their word by the translator.
            .replace(
                Regex("([\\u0600-\\u06FF])\\s+ها(?=[\\s.!،؟:]|$)"),
                "$1${ZWNJ}ها"
            )
            .replace(
                Regex("([\\u0600-\\u06FF])\\s+های(?=[\\s.!،؟:]|$)"),
                "$1${ZWNJ}های"
            )
            .trim()

        // Wrap Latin/number runs in directional isolates so they keep their
        // own order inside the RTL line ("Play Store", "4K", "v2.1").
        fixed = Regex("[A-Za-z0-9][A-Za-z0-9 .&'/+:-]*[A-Za-z0-9%]|[A-Za-z0-9]")
            .replace(fixed) { match -> "$LRI${match.value}$PDI" }

        return "$RLM$fixed"
    }
}
