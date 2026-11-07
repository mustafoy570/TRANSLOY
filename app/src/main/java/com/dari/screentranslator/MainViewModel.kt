package com.dari.screentranslator

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dari.screentranslator.data.AppSettings
import com.dari.screentranslator.data.SettingsRepository
import com.dari.screentranslator.engine.ModelManager
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class ModelStatus { NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

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

    fun setAutoHideMs(ms: Long) {
        viewModelScope.launch { repo.setAutoHideMs(ms) }
    }

    fun setGuideShown() {
        viewModelScope.launch { repo.setGuideShown() }
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
}
