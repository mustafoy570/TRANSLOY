package com.dari.screentranslator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dari.screentranslator.data.SettingsRepository

/** Process-wide holder for the settings repository singleton. */
object ServiceLocator {

    @Volatile
    lateinit var settingsRepository: SettingsRepository
        private set

    fun init(context: android.content.Context) {
        if (!::settingsRepository.isInitialized) {
            settingsRepository = SettingsRepository.get(context)
        }
    }
}

class TranslatorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val CHANNEL_OVERLAY = "overlay"
        const val CHANNEL_CAPTURE = "capture"
    }
}
