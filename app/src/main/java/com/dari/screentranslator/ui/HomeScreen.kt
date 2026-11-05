package com.dari.screentranslator.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dari.screentranslator.MainViewModel
import com.dari.screentranslator.R
import com.dari.screentranslator.model.OcrScript
import com.dari.screentranslator.service.OverlayService
import com.dari.screentranslator.service.ScreenCaptureService
import com.dari.screentranslator.util.SystemUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenModels: () -> Unit,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()

    var captureRunning by remember { mutableStateOf(ScreenCaptureService.isRunning) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        captureRunning = ScreenCaptureService.isRunning
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Continue even when denied: the service still runs, only its
        // notification stays hidden.
        viewModel.turnOn(context)
    }

    // Self-heal: when translation is enabled but the service was killed
    // (reboot, system cleanup) restart it.
    LaunchedEffect(settings.enabled, permissions.overlay) {
        if (settings.enabled && permissions.overlay && !OverlayService.isRunning) {
            viewModel.turnOn(context)
        }
    }

    fun requestTurnOn() {
        when {
            !permissions.overlay -> {
                Toast.makeText(context, R.string.toast_overlay_permission, Toast.LENGTH_LONG).show()
                SystemUtils.openOverlaySettings(context)
            }
            Build.VERSION.SDK_INT >= 33 && !permissions.notifications -> {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> viewModel.turnOn(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            BigToggleCard(
                enabled = settings.enabled,
                onToggle = { on -> if (on) requestTurnOn() else viewModel.turnOff(context) }
            )

            CaptureStatusCard(captureRunning = captureRunning)

            PermissionsCard(
                permissions = permissions,
                onOverlayClick = { SystemUtils.openOverlaySettings(context) },
                onNotificationsClick = { SystemUtils.openNotificationSettings(context) },
                onAccessibilityClick = { SystemUtils.openAccessibilitySettings(context) }
            )

            SettingsCard(
                selectedScript = settings.ocrScript,
                onScriptSelected = { viewModel.setOcrScript(it) },
                autoHideMs = settings.autoHideMs,
                onAutoHideSelected = { viewModel.setAutoHideMs(it) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onOpenModels, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.open_models))
                }
                OutlinedButton(onClick = onOpenGuide, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.open_guide))
                }
            }

            HowToCard()
        }
    }
}

@Composable
private fun BigToggleCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.toggle_section_title),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .padding(vertical = 20.dp)
                    .scale(2.1f),
                thumbContent = {
                    if (enabled) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                }
            )
            Text(
                text = stringResource(if (enabled) R.string.toggle_on else R.string.toggle_off),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.toggle_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CaptureStatusCard(captureRunning: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (captureRunning) R.string.capture_status_ready else R.string.capture_status_off
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PermissionsCard(
    permissions: MainViewModel.PermissionsState,
    onOverlayClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onAccessibilityClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            PermissionRow(
                label = stringResource(R.string.perm_overlay),
                granted = permissions.overlay,
                onClick = onOverlayClick
            )
            HorizontalDivider()
            PermissionRow(
                label = stringResource(R.string.perm_notifications),
                granted = permissions.notifications,
                onClick = onNotificationsClick
            )
            HorizontalDivider()
            PermissionRow(
                label = stringResource(R.string.perm_accessibility),
                granted = permissions.accessibility,
                onClick = onAccessibilityClick
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(if (granted) R.string.status_granted else R.string.status_missing),
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(Modifier.size(8.dp))
        if (granted) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    selectedScript: OcrScript,
    onScriptSelected: (OcrScript) -> Unit,
    autoHideMs: Long,
    onAutoHideSelected: (Long) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_section),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_ocr_script),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val scripts = listOf(
                    OcrScript.AUTO to R.string.script_auto,
                    OcrScript.LATIN to R.string.script_latin,
                    OcrScript.DEVANAGARI to R.string.script_devanagari,
                    OcrScript.CHINESE to R.string.script_chinese,
                    OcrScript.JAPANESE to R.string.script_japanese,
                    OcrScript.KOREAN to R.string.script_korean
                )
                for ((script, label) in scripts) {
                    FilterChip(
                        selected = selectedScript == script,
                        onClick = { onScriptSelected(script) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_auto_hide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    0L to R.string.auto_hide_never,
                    10_000L to R.string.auto_hide_10,
                    30_000L to R.string.auto_hide_30,
                    60_000L to R.string.auto_hide_60
                )
                for ((ms, label) in options) {
                    FilterChip(
                        selected = autoHideMs == ms,
                        onClick = { onAutoHideSelected(ms) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HowToCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.how_to_title),
                style = MaterialTheme.typography.titleMedium
            )
            val steps = listOf(
                R.string.how_to_1,
                R.string.how_to_2,
                R.string.how_to_3,
                R.string.how_to_4,
                R.string.how_to_5,
                R.string.how_to_6
            )
            for (step in steps) {
                Text(
                    text = "• " + stringResource(step),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
