package com.dari.screentranslator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dari.screentranslator.ui.GuideScreen
import com.dari.screentranslator.ui.HomeScreen
import com.dari.screentranslator.ui.ModelsScreen
import com.dari.screentranslator.ui.theme.TranslatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TranslatorTheme {
                TranslatorAppRoot()
            }
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
