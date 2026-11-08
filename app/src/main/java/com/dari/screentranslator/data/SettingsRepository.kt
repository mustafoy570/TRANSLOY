package com.dari.screentranslator.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dari.screentranslator.model.ModelUpdatePolicy
import com.dari.screentranslator.model.OcrScript
import com.dari.screentranslator.model.ScrollBehavior
import com.dari.screentranslator.model.TranslateScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "translator_settings")

data class AppSettings(
    val enabled: Boolean = false,
    val bubbleX: Int = Int.MIN_VALUE,
    val bubbleY: Int = Int.MIN_VALUE,
    val ocrScript: OcrScript = OcrScript.AUTO,
    val autoHideMs: Long = 0L,
    val guideShown: Boolean = false,
    val scope: TranslateScope = TranslateScope.WHOLE_SCREEN,
    val scrollBehavior: ScrollBehavior = ScrollBehavior.HIDE_ON_SCROLL,
    // v1.5: while the translation panel is open, re-translate automatically
    // after the page scrolls (no need to tap again). On by default — it is
    // the behaviour users expect from "auto scroll translation".
    val autoTranslateOnScroll: Boolean = true,
    // v1.5.0: who approves ML Kit model updates, and when the last daily
    // check happened (throttle).
    val modelUpdatePolicy: ModelUpdatePolicy = ModelUpdatePolicy.ASK_FIRST,
    val lastUpdateCheckMs: Long = 0L
)

/**
 * Single source of truth for persisted settings, backed by Preferences DataStore.
 * The instance is a process-wide singleton keyed on the application context.
 */
class SettingsRepository private constructor(private val context: Context) {

    companion object {
        private val ENABLED = booleanPreferencesKey("enabled")
        private val BUBBLE_X = intPreferencesKey("bubble_x")
        private val BUBBLE_Y = intPreferencesKey("bubble_y")
        private val OCR_SCRIPT = stringPreferencesKey("ocr_script")
        private val AUTO_HIDE_MS = longPreferencesKey("auto_hide_ms")
        private val GUIDE_SHOWN = booleanPreferencesKey("guide_shown")
        private val SCOPE = stringPreferencesKey("translate_scope")
        private val SCROLL_BEHAVIOR = stringPreferencesKey("scroll_behavior")
        private val AUTO_TRANSLATE_ON_SCROLL = booleanPreferencesKey("auto_translate_on_scroll")
        private val MODEL_UPDATE_POLICY = stringPreferencesKey("model_update_policy")
        private val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ms")

        private const val LANGUAGE_PREFS = "app_config"
        private const val KEY_APP_LANGUAGE = "app_language"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_DARI = "fa"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }

    /** Plain SharedPreferences for the app language: it must be readable
     *  SYNCHRONOUSLY in MainActivity.onCreate (before the first frame) so
     *  the Dari UI never flashes in English first. Everything else stays in
     *  DataStore. */
    private val languagePrefs =
        context.getSharedPreferences(LANGUAGE_PREFS, Context.MODE_PRIVATE)

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                enabled = prefs[ENABLED] ?: false,
                bubbleX = prefs[BUBBLE_X] ?: Int.MIN_VALUE,
                bubbleY = prefs[BUBBLE_Y] ?: Int.MIN_VALUE,
                ocrScript = runCatching {
                    OcrScript.valueOf(prefs[OCR_SCRIPT] ?: OcrScript.AUTO.name)
                }.getOrDefault(OcrScript.AUTO),
                autoHideMs = prefs[AUTO_HIDE_MS] ?: 0L,
                guideShown = prefs[GUIDE_SHOWN] ?: false,
                scope = runCatching {
                    TranslateScope.valueOf(prefs[SCOPE] ?: TranslateScope.WHOLE_SCREEN.name)
                }.getOrDefault(TranslateScope.WHOLE_SCREEN),
                scrollBehavior = runCatching {
                    ScrollBehavior.valueOf(
                        prefs[SCROLL_BEHAVIOR] ?: ScrollBehavior.HIDE_ON_SCROLL.name
                    )
                }.getOrDefault(ScrollBehavior.HIDE_ON_SCROLL),
                autoTranslateOnScroll = prefs[AUTO_TRANSLATE_ON_SCROLL] ?: true,
                modelUpdatePolicy = runCatching {
                    ModelUpdatePolicy.valueOf(
                        prefs[MODEL_UPDATE_POLICY] ?: ModelUpdatePolicy.ASK_FIRST.name
                    )
                }.getOrDefault(ModelUpdatePolicy.ASK_FIRST),
                lastUpdateCheckMs = prefs[LAST_UPDATE_CHECK] ?: 0L
            )
        }

    suspend fun setEnabled(value: Boolean) {
        context.settingsDataStore.edit { it[ENABLED] = value }
    }

    suspend fun saveBubblePosition(x: Int, y: Int) {
        context.settingsDataStore.edit {
            it[BUBBLE_X] = x
            it[BUBBLE_Y] = y
        }
    }

    suspend fun setOcrScript(script: OcrScript) {
        context.settingsDataStore.edit { it[OCR_SCRIPT] = script.name }
    }

    suspend fun setAutoHideMs(ms: Long) {
        context.settingsDataStore.edit { it[AUTO_HIDE_MS] = ms }
    }

    suspend fun setGuideShown() {
        context.settingsDataStore.edit { it[GUIDE_SHOWN] = true }
    }

    suspend fun setScope(scope: TranslateScope) {
        context.settingsDataStore.edit { it[SCOPE] = scope.name }
    }

    suspend fun setScrollBehavior(behavior: ScrollBehavior) {
        context.settingsDataStore.edit { it[SCROLL_BEHAVIOR] = behavior.name }
    }

    suspend fun setAutoTranslateOnScroll(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_TRANSLATE_ON_SCROLL] = value }
    }

    suspend fun setModelUpdatePolicy(policy: ModelUpdatePolicy) {
        context.settingsDataStore.edit { it[MODEL_UPDATE_POLICY] = policy.name }
    }

    suspend fun setLastUpdateCheck(timestampMs: Long) {
        context.settingsDataStore.edit { it[LAST_UPDATE_CHECK] = timestampMs }
    }

    // ------------------------------------------------------------- app language

    /** Current UI language code: "en" or "fa" (readable without coroutines). */
    fun getAppLanguage(): String =
        languagePrefs.getString(KEY_APP_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH

    fun isDari(): Boolean = getAppLanguage() == LANGUAGE_DARI

    /** Persists the language choice synchronously (small write, main-safe). */
    fun setAppLanguage(code: String) {
        languagePrefs.edit()
            .putString(KEY_APP_LANGUAGE, if (code == LANGUAGE_DARI) LANGUAGE_DARI else LANGUAGE_ENGLISH)
            .apply()
    }
}
