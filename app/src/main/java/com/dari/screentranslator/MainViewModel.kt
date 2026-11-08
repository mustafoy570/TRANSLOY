package com.dari.screentranslator

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dari.screentranslator.data.AppSettings
import com.dari.screentranslator.data.SettingsRepository
import com.dari.screentranslator.engine.ModelManager
import com.dari.screentranslator.engine.ModelShareManager
import com.dari.screentranslator.model.ModelUpdatePolicy
import com.dari.screentranslator.model.OcrScript
import com.dari.screentranslator.model.ScrollBehavior
import com.dari.screentranslator.model.TranslateScope
import com.dari.screentranslator.service.OverlayService
import com.dari.screentranslator.service.ScreenCaptureService
import com.dari.screentranslator.util.NetworkMonitor
import com.dari.screentranslator.util.SystemUtils
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

    /** Result of a model-update run, consumed by the UI (toast). */
    enum class UpdateResult { DONE, FAILED }

    data class PermissionsState(
        val overlay: Boolean = false,
        val notifications: Boolean = false,
        val accessibility: Boolean = false
    )

    private val repo: SettingsRepository = ServiceLocator.settingsRepository
    private val modelMutex = Mutex()

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppSettings()
    )

    val permissions = MutableStateFlow(PermissionsState())

    val modelStates = MutableStateFlow<Map<String, ModelStatus>>(emptyMap())

    /** Live connectivity, used to auto-start the one-time offline setup. */
    val isOnline: StateFlow<Boolean> = NetworkMonitor.isOnline

    // ------------------------------------------------------ language & updates

    /** Current UI language code ("en" / "fa") for the settings toggle. */
    val appLanguage = MutableStateFlow(repo.getAppLanguage())

    /** True when policy = ASK_FIRST and a daily update check is due → the
     *  Home screen shows the approval dialog (updates NEVER download
     *  without the user saying yes). */
    val updatePromptVisible = MutableStateFlow(false)

    val updateRunning = MutableStateFlow(false)

    /** Consumed by the UI to show a “models are up to date” toast. */
    val updateResult = MutableStateFlow<UpdateResult?>(null)

    // ------------------------------------------------------------- zapya share

    val shareBusy = MutableStateFlow(false)

    /** Non-null once a share pack is built → UI fires the share intent. */
    val shareEvent = MutableStateFlow<ModelShareManager.SharePack?>(null)

    /** Number of files restored by an import, or -1 on failure. */
    val importResult = MutableStateFlow<Int?>(null)

    init {
        NetworkMonitor.start(application)
        refreshPermissions()
        refreshModels()
    }

    // ---------------------------------------------------------------- permissions

    fun refreshPermissions() {
        val context = getApplication<Application>()
        permissions.value = PermissionsState(
            overlay = SystemUtils.canDrawOverlays(context),
            notifications = SystemUtils.areNotificationsEnabled(context),
            accessibility = SystemUtils.isAccessibilityEnabled(context)
        )
    }

    // ---------------------------------------------------------------- models

    fun refreshModels() {
        viewModelScope.launch {
            val downloaded = ModelManager.downloadedCodes()
            modelStates.value = ModelManager.LANGUAGES.associate { entry ->
                entry.code to if (downloaded.contains(entry.code)) {
                    ModelStatus.READY
                } else {
                    ModelStatus.NOT_DOWNLOADED
                }
            }
        }
    }

    fun downloadLanguage(code: String) {
        viewModelScope.launch {
            modelMutex.withLock {
                val states = modelStates.value.toMutableMap()
                if (states[code] == ModelStatus.READY) return@withLock
                states[code] = ModelStatus.DOWNLOADING
                modelStates.value = states
                try {
                    ModelManager.downloadModel(code)
                    states[code] = ModelStatus.READY
                } catch (t: Throwable) {
                    states[code] = ModelStatus.ERROR
                }
                modelStates.value = states
            }
        }
    }

    fun deleteLanguage(code: String) {
        viewModelScope.launch {
            modelMutex.withLock {
                val states = modelStates.value.toMutableMap()
                if (states[code] != ModelStatus.READY) return@withLock
                states[code] = ModelStatus.DOWNLOADING
                modelStates.value = states
                try {
                    ModelManager.deleteModel(code)
                    states[code] = ModelStatus.NOT_DOWNLOADED
                } catch (t: Throwable) {
                    states[code] = ModelStatus.ERROR
                }
                modelStates.value = states
            }
        }
    }

    fun downloadAllMissing() {
        viewModelScope.launch {
            for (entry in ModelManager.LANGUAGES) {
                val status = modelStates.value[entry.code]
                if (status == null ||
                    status == ModelStatus.NOT_DOWNLOADED ||
                    status == ModelStatus.ERROR
                ) {
                    downloadLanguage(entry.code)
                }
            }
        }
    }

    /**
     * One-time setup that makes the app work offline: downloads the Persian
     * target model plus the English source model (the most common case).
     * Auto-triggered from the home screen as soon as internet appears.
     */
    fun downloadCoreModels() {
        viewModelScope.launch {
            modelMutex.withLock {
                for (code in listOf(ModelManager.TARGET_LANGUAGE, TranslateLanguage.ENGLISH)) {
                    val states = modelStates.value.toMutableMap()
                    if (states[code] == ModelStatus.READY) continue
                    states[code] = ModelStatus.DOWNLOADING
                    modelStates.value = states
                    try {
                        ModelManager.downloadModel(code)
                        states[code] = ModelStatus.READY
                    } catch (t: Throwable) {
                        states[code] = ModelStatus.ERROR
                    }
                    modelStates.value = states
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings

    fun setOcrScript(script: OcrScript) {
        viewModelScope.launch { repo.setOcrScript(script) }
    }

    fun setScope(scope: TranslateScope) {
        viewModelScope.launch { repo.setScope(scope) }
    }

    fun setScrollBehavior(behavior: ScrollBehavior) {
        viewModelScope.launch { repo.setScrollBehavior(behavior) }
    }

    fun setAutoTranslateOnScroll(value: Boolean) {
        viewModelScope.launch { repo.setAutoTranslateOnScroll(value) }
    }

    fun setAutoHideMs(ms: Long) {
        viewModelScope.launch { repo.setAutoHideMs(ms) }
    }

    fun setGuideShown() {
        viewModelScope.launch { repo.setGuideShown() }
    }

    /**
     * Switches the whole app between English and Dari instantly. The choice
     * is stored synchronously (SharedPreferences) and applied through
     * AppCompatDelegate per-app locales — on Android 13+ it even shows up in
     * the system per-app language settings.
     */
    fun setAppLanguage(code: String) {
        val normalized =
            if (code == SettingsRepository.Companion.LANGUAGE_DARI) {
                SettingsRepository.Companion.LANGUAGE_DARI
            } else {
                SettingsRepository.Companion.LANGUAGE_ENGLISH
            }
        repo.setAppLanguage(normalized)
        appLanguage.value = normalized
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(normalized)
        )
    }

    fun setModelUpdatePolicy(policy: ModelUpdatePolicy) {
        viewModelScope.launch { repo.setModelUpdatePolicy(policy) }
    }

    /**
     * Runs at most once a day when the app opens. Nothing ever downloads
     * without either the user's approval (ASK_FIRST → dialog) or an explicit
     * opt-in (AUTO_WIFI → silent, Wi-Fi only). NEVER skips entirely.
     */
    fun maybeCheckForModelUpdates() {
        viewModelScope.launch {
            val settings = repo.settings.first()
            if (settings.modelUpdatePolicy == ModelUpdatePolicy.NEVER) return@launch
            val now = System.currentTimeMillis()
            if (now - settings.lastUpdateCheckMs < UPDATE_CHECK_INTERVAL_MS) return@launch
            val downloaded = ModelManager.downloadedCodes()
            if (downloaded.isEmpty()) return@launch
            repo.setLastUpdateCheck(now)
            when (settings.modelUpdatePolicy) {
                ModelUpdatePolicy.ASK_FIRST -> updatePromptVisible.value = true
                ModelUpdatePolicy.AUTO_WIFI -> runModelUpdates(requireWifi = true)
                ModelUpdatePolicy.NEVER -> Unit
            }
        }
    }

    /** Re-downloads every installed model (ML Kit pulls a newer version only
     *  if one exists). Visible progress + result toast. */
    fun runModelUpdates(requireWifi: Boolean = false) {
        if (updateRunning.value) return
        viewModelScope.launch {
            updateRunning.value = true
            var allOk = true
            val downloaded = ModelManager.downloadedCodes()
            for (code in downloaded) {
                try {
                    ModelManager.downloadModel(code, requireWifi = requireWifi)
                } catch (t: Throwable) {
                    allOk = false
                }
            }
            refreshModels()
            updateRunning.value = false
            updateResult.value = if (allOk) UpdateResult.DONE else UpdateResult.FAILED
        }
    }

    fun dismissUpdatePrompt() {
        updatePromptVisible.value = false
    }

    fun consumeUpdateResult() {
        updateResult.value = null
    }

    // ------------------------------------------------------------- zapya share

    /** Builds the offline share pack (APK + models zip) on a background
     *  dispatcher; the UI picks up [shareEvent] when it is ready. */
    fun prepareSharePack(context: Context) {
        if (shareBusy.value) return
        viewModelScope.launch {
            shareBusy.value = true
            val pack = ModelShareManager.buildSharePack(context)
            shareBusy.value = false
            shareEvent.value = pack
        }
    }

    fun consumeShareEvent() {
        shareEvent.value = null
    }

    fun importModels(context: Context, uri: Uri) {
        if (shareBusy.value) return
        viewModelScope.launch {
            shareBusy.value = true
            val count = ModelShareManager.importModelsZip(context, uri)
            shareBusy.value = false
            importResult.value = count
            if (count > 0) refreshModels()
        }
    }

    fun consumeImportResult() {
        importResult.value = null
    }

    // ---------------------------------------------------------------- toggle

    fun turnOn(context: Context) {
        viewModelScope.launch { repo.setEnabled(true) }
        val intent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun turnOff(context: Context) {
        viewModelScope.launch { repo.setEnabled(false) }
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.stop(context)
        }
        // Plain startService (not startForegroundService): the service is
        // already running or will simply stop itself immediately.
        runCatching {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_STOP)
            )
        }
    }

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
