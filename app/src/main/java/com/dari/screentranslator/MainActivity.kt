package com.dari.screentranslator

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dari.screentranslator.ui.GuideScreen
import com.dari.screentranslator.ui.HomeScreen
import com.dari.screentranslator.ui.ModelsScreen
import com.dari.screentranslator.ui.theme.TranslatorTheme

/**
 * AppCompatActivity (v1.5.0): required by AppCompatDelegate per-app locales,
 * which powers the in-app language switch (English ⇄ دری) on Android 8–12 —
 * on Android 13+ the choice also appears in the system per-app language
 * settings.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the stored language BEFORE the first frame so the Dari UI
        // never flashes in English first. Below API 33 AppCompat does not
        // persist locales itself, so the stored choice is re-applied here.
        applyStoredLanguage()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TranslatorTheme {
                TranslatorAppRoot()
            }
        }
    }

    private fun applyStoredLanguage() {
        val stored = ServiceLocator.settingsRepository.getAppLanguage()
        val applied = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (!applied.startsWith(stored)) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(stored)
            )
        }
    }
}

private object Screens {
    const val HOME = "home"
    const val MODELS = "models"
    const val GUIDE = "guide"
}

@Composable
private fun TranslatorAppRoot(viewModel: MainViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var currentScreen by rememberSaveable { mutableStateOf(Screens.HOME) }

    // First run: always show the setup guide until it is completed once.
    LaunchedEffect(settings.guideShown) {
        if (!settings.guideShown) currentScreen = Screens.GUIDE
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    when (currentScreen) {
        Screens.MODELS -> ModelsScreen(
            viewModel = viewModel,
            onBack = { currentScreen = Screens.HOME }
        )
        Screens.GUIDE -> GuideScreen(
            viewModel = viewModel,
            onOpenModels = { currentScreen = Screens.MODELS },
            onDone = {
                viewModel.setGuideShown()
                currentScreen = Screens.HOME
            }
        )
        else -> HomeScreen(
            viewModel = viewModel,
            onOpenModels = { currentScreen = Screens.MODELS },
            onOpenGuide = { currentScreen = Screens.GUIDE }
        )
    }
}
