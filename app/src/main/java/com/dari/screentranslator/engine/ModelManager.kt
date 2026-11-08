package com.dari.screentranslator.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.tasks.await

/**
 * Lists, downloads and deletes ML Kit on-device translation models.
 * Models are downloaded once and then used fully offline.
 */
object ModelManager {

    /** The translation target is always Persian, which covers Dari. */
    val TARGET_LANGUAGE: String = TranslateLanguage.PERSIAN

    data class LanguageEntry(
        val code: String,
        val englishName: String,
        val nativeName: String,
        val isTarget: Boolean = false
    )

    val LANGUAGES: List<LanguageEntry> = listOf(
        LanguageEntry(TranslateLanguage.PERSIAN, "Persian (Dari)", "فارسی دری", isTarget = true),
        LanguageEntry(TranslateLanguage.ENGLISH, "English", "انگلیسی"),
        LanguageEntry(TranslateLanguage.ARABIC, "Arabic", "عربی"),
        LanguageEntry(TranslateLanguage.TURKISH, "Turkish", "ترکی"),
        LanguageEntry(TranslateLanguage.HINDI, "Hindi", "هندی"),
        LanguageEntry(TranslateLanguage.URDU, "Urdu", "اردو"),
        LanguageEntry(TranslateLanguage.RUSSIAN, "Russian", "روسی"),
        LanguageEntry(TranslateLanguage.CHINESE, "Chinese", "چینی"),
        LanguageEntry(TranslateLanguage.JAPANESE, "Japanese", "ژاپنی"),
        LanguageEntry(TranslateLanguage.KOREAN, "Korean", "کره‌ای"),
        LanguageEntry(TranslateLanguage.FRENCH, "French", "فرانسوی"),
        LanguageEntry(TranslateLanguage.GERMAN, "German", "آلمانی"),
        LanguageEntry(TranslateLanguage.SPANISH, "Spanish", "اسپانیایی"),
        LanguageEntry(TranslateLanguage.ITALIAN, "Italian", "ایتالیایی"),
        LanguageEntry(TranslateLanguage.PORTUGUESE, "Portuguese", "پرتغالی"),
        LanguageEntry(TranslateLanguage.DUTCH, "Dutch", "هلندی")
    )

    fun displayName(code: String): String =
        LANGUAGES.firstOrNull { it.code == code }?.englishName ?: code

    /**
     * Whether the on-device model for [code] is already on this phone.
     * NOTE: RemoteModelManager.isModelDownloaded() returns a Task<Boolean>,
     * NOT a Boolean — it must be awaited. Returning the Task directly from a
     * Boolean function is a compile error and was the very first bug fixed
     * for offline support (v1.3.0).
     */
    suspend fun isDownloaded(code: String): Boolean = try {
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(code).build())
            .await()
    } catch (t: Throwable) {
        false
    }

    /**
     * Downloads the model for [code]. When [requireWifi] is true the download
     * only runs on Wi-Fi (ML Kit DownloadConditions — the model-update policy
     * "Auto (Wi-Fi only)" uses this).
     *
     * Calling this for a model that is ALREADY downloaded re-checks Google's
     * manifest and pulls a newer version only if one exists — that is the
     * whole "check for updates" mechanism used by the update policy and the
     * manual "Check for updates" button.
     */
    suspend fun downloadModel(code: String, requireWifi: Boolean = false) {
        val conditions = if (requireWifi) {
            DownloadConditions.Builder().requireWifi().build()
        } else {
            DownloadConditions.Builder().build()
        }
        RemoteModelManager.getInstance()
            .download(
                TranslateRemoteModel.Builder(code).build(),
                conditions
            )
            .await()
    }

    suspend fun deleteModel(code: String) {
        RemoteModelManager.getInstance()
            .deleteDownloadedModel(TranslateRemoteModel.Builder(code).build())
            .await()
    }

    suspend fun downloadedCodes(): Set<String> = try {
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .map { it.language }
            .toSet()
    } catch (t: Throwable) {
        emptySet()
    }
}
