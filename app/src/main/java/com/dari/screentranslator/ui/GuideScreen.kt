package com.dari.screentranslator.ui

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dari.screentranslator.MainViewModel
import com.dari.screentranslator.R
import com.dari.screentranslator.engine.ModelManager
import com.dari.screentranslator.service.ProjectionConsentActivity
import com.dari.screentranslator.service.ScreenCaptureService
import com.dari.screentranslator.util.SystemUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    viewModel: MainViewModel,
    onOpenModels: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val modelStates by viewModel.modelStates.collectAsStateWithLifecycle()

    var captureRunning by remember { mutableStateOf(ScreenCaptureService.isRunning) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        captureRunning = ScreenCaptureService.isRunning
    }

    val targetReady = modelStates[ModelManager.TARGET_LANGUAGE] ==
        MainViewModel.ModelStatus.READY

    val stepsDone = listOf(
        permissions.overlay,
        permissions.notifications,
        permissions.accessibility,
        captureRunning,
        targetReady
    )
    val doneCount = stepsDone.count { it }
    val progress = if (stepsDone.isEmpty()) 0f else doneCount.toFloat() / stepsDone.size

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.guide_title)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.guide_intro),
                style = MaterialTheme.typography.bodyMedium
            )

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                strokeCap = StrokeCap.Round
            )
            Text(
                text = stringResource(R.string.guide_progress, doneCount, stepsDone.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            GuideStepCard(
                number = "1",
                title = stringResource(R.string.guide_step_overlay_title),
                description = stringResource(R.string.guide_step_overlay_desc),
                done = permissions.overlay
            ) {
                SystemUtils.openOverlaySettings(context)
            }
            GuideStepCard(
                number = "2",
                title = stringResource(R.string.guide_step_notifications_title),
                description = stringResource(R.string.guide_step_notifications_desc),
                done = permissions.notifications
            ) {
                SystemUtils.openNotificationSettings(context)
            }
            GuideStepCard(
                number = "3",
                title = stringResource(R.string.guide_step_accessibility_title),
                description = stringResource(R.string.guide_step_accessibility_desc),
                done = permissions.accessibility
            ) {
                SystemUtils.openAccessibilitySettings(context)
            }
            GuideStepCard(
                number = "4",
                title = stringResource(R.string.guide_step_capture_title),
                description = stringResource(R.string.guide_step_capture_desc),
                done = captureRunning
            ) {
                context.startActivity(Intent(context, ProjectionConsentActivity::class.java))
            }
            GuideStepCard(
                number = "5",
                title = stringResource(R.string.guide_step_models_title),
                description = stringResource(R.string.guide_step_models_desc),
                done = targetReady,
                actionLabel = stringResource(R.string.guide_open_models),
                onAction = onOpenModels
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.guide_done))
            }
        }
    }
}

@Composable
private fun GuideStepCard(
    number: String,
    title: String,
    description: String,
    done: Boolean?,
    clickable: Boolean = true,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onClick: () -> Unit = {}
) {
    val container = when (done) {
        true -> MaterialTheme.colorScheme.primaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(if (clickable && actionLabel == null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.elevatedCardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                shape = CircleShape,
                color = when (done) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.outline
                }
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall
                )
                if (actionLabel != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
            if (done == true) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
