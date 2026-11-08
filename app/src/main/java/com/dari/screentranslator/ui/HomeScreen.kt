package com.dari.screentranslator.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dari.screentranslator.MainViewModel
import com.dari.screentranslator.R
import com.dari.screentranslator.engine.ModelManager
import com.dari.screentranslator.engine.ModelShareManager
import com.dari.screentranslator.model.ModelUpdatePolicy
import com.dari.screentranslator.model.OcrScript
import com.dari.screentranslator.model.ScrollBehavior
import com.dari.screentranslator.model.TranslateScope
import com.dari.screentranslator.service.OverlayService
import com.dari.screentranslator.service.ProjectionConsentActivity
import com.dari.screentranslator.service.ScreenCaptureService
import com.dari.screentranslator.util.SystemUtils
import com.google.mlkit.nl.translate.TranslateLanguage

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
    val modelStates by viewModel.modelStates.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val updatePromptVisible by viewModel.updatePromptVisible.collectAsStateWithLifecycle()
    val updateRunning by viewModel.updateRunning.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val shareBusy by viewModel.shareBusy.collectAsStateWithLifecycle()
    val shareEvent by viewModel.shareEvent.collectAsStateWithLifecycle()
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    // Offline translation needs BOTH the Persian (fa) target model and the
    // English (en) source model — the setup card and the auto-download
    // must therefore track both, not just the target.
    val faStatus = modelStates[ModelManager.TARGET_LANGUAGE]
    val enStatus = modelStates[TranslateLanguage.ENGLISH]
    val coreReady = faStatus == MainViewModel.ModelStatus.READY &&
        enStatus == MainViewModel.ModelStatus.READY
    val coreNeedsDownload =
        faStatus == MainViewModel.ModelStatus.NOT_DOWNLOADED ||
        enStatus == MainViewModel.ModelStatus.NOT_DOWNLOADED ||
        faStatus == MainViewModel.ModelStatus.ERROR ||
        enStatus == MainViewModel.ModelStatus.ERROR

    LaunchedEffect(Unit) { viewModel.refreshModels() }

    var captureRunning by remember { mutableStateOf(ScreenCaptureService.isRunning) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        captureRunning = ScreenCaptureService.isRunning
        // Daily model-update check — respects the update policy setting and
        // never downloads without the user's approval.
        viewModel.maybeCheckForModelUpdates()
    }

    // Picks the received models ZIP (Zapya → Downloads) and restores it.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importModels(context, it) }
    }

    // Fires the Zapya share sheet once the share pack is built.
    LaunchedEffect(shareEvent) {
        shareEvent?.let { pack ->
            when {
                pack.apkFile == null && pack.modelsZip == null ->
                    Toast.makeText(context, R.string.share_nothing, Toast.LENGTH_LONG).show()
                !pack.hasModels ->
                    Toast.makeText(context, R.string.share_no_models_note, Toast.LENGTH_LONG).show()
            }
            if (pack.apkFile != null || pack.modelsZip != null) {
                runCatching {
                    context.startActivity(ModelShareManager.shareIntent(context, pack))
                }
            }
            viewModel.consumeShareEvent()
        }
    }

    LaunchedEffect(importResult) {
        importResult?.let { count ->
            val message = if (count > 0) {
                context.getString(R.string.import_success, count)
            } else {
                context.getString(R.string.import_failed)
            }
            Toast.makeText(
                context,
                message,
                if (count > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
            viewModel.consumeImportResult()
        }
    }

    LaunchedEffect(updateResult) {
        updateResult?.let { result ->
            Toast.makeText(
                context,
                if (result == MainViewModel.UpdateResult.DONE) {
                    R.string.update_toast_done
                } else {
                    R.string.update_toast_failed
                },
                Toast.LENGTH_SHORT
            ).show()
            viewModel.consumeUpdateResult()
        }
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

    // Offline magic: the moment there is internet, download the core
    // language packs once (Persian AND English) so that every translation
    // after that works in airplane mode.
    LaunchedEffect(faStatus, enStatus, isOnline) {
        if (isOnline && coreNeedsDownload) {
            viewModel.downloadCoreModels()
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
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { StatusPill(enabled = settings.enabled) }
            )
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
                onToggle = { on -> if (on) requestTurnOn() else viewModel.turnOff(context) },
                modelsReady = modelStates.count { it.value == MainViewModel.ModelStatus.READY },
                modelsTotal = ModelManager.LANGUAGES.size,
                modeLabel = stringResource(
                    if (settings.scope == TranslateScope.NEAR_BUTTON) {
                        R.string.scope_near
                    } else {
                        R.string.scope_whole
                    }
                ),
                scrollLabel = stringResource(
                    if (settings.scrollBehavior == ScrollBehavior.KEEP) {
                        R.string.scroll_keep
                    } else {
                        R.string.scroll_hide
                    }
                )
            )

            if (!coreReady) {
                OfflineSetupCard(
                    faStatus = faStatus,
                    enStatus = enStatus,
                    isOnline = isOnline,
                    onRetry = { viewModel.downloadCoreModels() }
                )
            }

            TranslationModeCard(
                scope = settings.scope,
                onScopeSelected = { viewModel.setScope(it) },
                scrollBehavior = settings.scrollBehavior,
                onScrollBehaviorSelected = { viewModel.setScrollBehavior(it) },
                autoTranslateOnScroll = settings.autoTranslateOnScroll,
                onAutoTranslateOnScroll = { viewModel.setAutoTranslateOnScroll(it) }
            )

            GeneralSettingsCard(
                appLanguage = appLanguage,
                onLanguageSelected = { viewModel.setAppLanguage(it) },
                updatePolicy = settings.modelUpdatePolicy,
                onUpdatePolicySelected = { viewModel.setModelUpdatePolicy(it) },
                updateRunning = updateRunning,
                onCheckNow = { viewModel.runModelUpdates() }
            )

            PermissionsCard(
                permissions = permissions,
                captureRunning = captureRunning,
                onOverlayClick = { SystemUtils.openOverlaySettings(context) },
                onNotificationsClick = { SystemUtils.openNotificationSettings(context) },
                onAccessibilityClick = { SystemUtils.openAccessibilitySettings(context) },
                onCaptureClick = {
                    context.startActivity(Intent(context, ProjectionConsentActivity::class.java))
                }
            )

            SettingsCard(
                selectedScript = settings.ocrScript,
                onScriptSelected = { viewModel.setOcrScript(it) },
                autoHideMs = settings.autoHideMs,
                onAutoHideSelected = { viewModel.setAutoHideMs(it) }
            )

            ShareCard(
                busy = shareBusy,
                onShare = { viewModel.prepareSharePack(context) },
                onImport = { importPicker.launch(arrayOf("*/*")) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NavCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.List,
                    title = stringResource(R.string.open_models),
                    description = stringResource(R.string.nav_models_desc),
                    onClick = onOpenModels
                )
                NavCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.open_guide),
                    description = stringResource(R.string.nav_guide_desc),
                    onClick = onOpenGuide
                )
            }

            HowToCard()

            PrivacyCard()
        }
    }

    // Model-update approval (policy “Ask me first”): nothing downloads
    // until the user taps “Update now”.
    if (updatePromptVisible) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdatePrompt() },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = { Text(stringResource(R.string.update_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissUpdatePrompt()
                    viewModel.runModelUpdates()
                }) {
                    Text(stringResource(R.string.update_dialog_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdatePrompt() }) {
                    Text(stringResource(R.string.update_dialog_later))
                }
            }
        )
    }
}

// ---------------------------------------------------------------- offline setup

/**
 * Shown while the core offline language packs are not ready. Explains the
 * one-time internet requirement instead of letting offline translation
 * fail silently — the single most confusing failure of v1.2.
 */
@Composable
private fun OfflineSetupCard(
    faStatus: MainViewModel.ModelStatus?,
    enStatus: MainViewModel.ModelStatus?,
    isOnline: Boolean,
    onRetry: () -> Unit
) {
    val downloading =
        faStatus == MainViewModel.ModelStatus.DOWNLOADING ||
            enStatus == MainViewModel.ModelStatus.DOWNLOADING ||
            ((faStatus == null || faStatus == MainViewModel.ModelStatus.NOT_DOWNLOADED ||
                enStatus == null || enStatus == MainViewModel.ModelStatus.NOT_DOWNLOADED) &&
                isOnline)
    val failed = !downloading
    val containerColor = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (downloading) Icons.Filled.Refresh else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = contentColor
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.setup_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when {
                        downloading -> R.string.setup_card_body_downloading
                        !isOnline -> R.string.setup_card_body_missing_offline
                        else -> R.string.setup_card_body_error
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = contentColor
            )
            if (downloading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (isOnline) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.setup_card_retry))
                }
            }
        }
    }
}

// --------------------------------------------------------------------- top bar

@Composable
private fun StatusPill(enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.padding(end = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(if (enabled) R.string.pill_on else R.string.pill_off),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ----------------------------------------------------------------------- hero

@Composable
private fun BigToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modelsReady: Int,
    modelsTotal: Int,
    modeLabel: String,
    scrollLabel: String
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "toggleContainer"
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
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
                    .padding(vertical = 24.dp)
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
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_stat_models),
                    value = "$modelsReady / $modelsTotal"
                )
                StatCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_stat_mode),
                    value = modeLabel
                )
                StatCell(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.home_stat_scroll),
                    value = scrollLabel
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

// ----------------------------------------------------------------------- mode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslationModeCard(
    scope: TranslateScope,
    onScopeSelected: (TranslateScope) -> Unit,
    scrollBehavior: ScrollBehavior,
    onScrollBehaviorSelected: (ScrollBehavior) -> Unit,
    autoTranslateOnScroll: Boolean,
    onAutoTranslateOnScroll: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.mode_section),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.scope_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = scope == TranslateScope.WHOLE_SCREEN,
                    onClick = { onScopeSelected(TranslateScope.WHOLE_SCREEN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(scope == TranslateScope.WHOLE_SCREEN) }
                ) {
                    Text(stringResource(R.string.scope_whole))
                }
                SegmentedButton(
                    selected = scope == TranslateScope.NEAR_BUTTON,
                    onClick = { onScopeSelected(TranslateScope.NEAR_BUTTON) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(scope == TranslateScope.NEAR_BUTTON) }
                ) {
                    Text(stringResource(R.string.scope_near))
                }
            }
            Text(
                text = stringResource(
                    if (scope == TranslateScope.NEAR_BUTTON) {
                        R.string.scope_desc_near
                    } else {
                        R.string.scope_desc_whole
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            Text(
                text = stringResource(R.string.scroll_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = scrollBehavior == ScrollBehavior.HIDE_ON_SCROLL,
                    onClick = { onScrollBehaviorSelected(ScrollBehavior.HIDE_ON_SCROLL) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        SegmentedButtonDefaults.Icon(
                            scrollBehavior == ScrollBehavior.HIDE_ON_SCROLL
                        )
                    }
                ) {
                    Text(stringResource(R.string.scroll_hide))
                }
                SegmentedButton(
                    selected = scrollBehavior == ScrollBehavior.KEEP,
                    onClick = { onScrollBehaviorSelected(ScrollBehavior.KEEP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(scrollBehavior == ScrollBehavior.KEEP) }
                ) {
                    Text(stringResource(R.string.scroll_keep))
                }
            }
            Text(
                text = stringResource(
                    if (scrollBehavior == ScrollBehavior.HIDE_ON_SCROLL) {
                        R.string.scroll_desc_hide
                    } else {
                        R.string.scroll_desc_keep
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            // v1.5: auto-refresh the open panel when the page scrolls.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setting_auto_scroll_title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.setting_auto_scroll_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoTranslateOnScroll,
                    onCheckedChange = onAutoTranslateOnScroll
                )
            }
        }
    }
}

// ---------------------------------------------------------------- permissions

@Composable
private fun PermissionsCard(
    permissions: MainViewModel.PermissionsState,
    captureRunning: Boolean,
    onOverlayClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onCaptureClick: () -> Unit
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
                icon = Icons.Filled.Lock,
                label = stringResource(R.string.perm_overlay),
                granted = permissions.overlay,
                onClick = onOverlayClick
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Filled.Notifications,
                label = stringResource(R.string.perm_notifications),
                granted = permissions.notifications,
                onClick = onNotificationsClick
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.perm_accessibility),
                granted = permissions.accessibility,
                onClick = onAccessibilityClick
            )
            HorizontalDivider()
            PermissionRow(
                icon = Icons.Filled.PlayArrow,
                label = stringResource(R.string.perm_capture),
                granted = captureRunning,
                onClick = onCaptureClick
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.permissions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = if (granted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(8.dp)
                    .size(20.dp)
            )
        }
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
        Spacer(Modifier.size(4.dp))
        if (granted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
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

// ------------------------------------------------------------------- settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(
    selectedScript: OcrScript,
    onScriptSelected: (OcrScript) -> Unit,
    autoHideMs: Long,
    onAutoHideSelected: (Long) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
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

// ---------------------------------------------------------------- navigation

@Composable
private fun NavCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --------------------------------------------------------------------- how to

@Composable
private fun HowToCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                R.string.how_to_6,
                R.string.how_to_7
            )
            for ((index, step) in steps.withIndex()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                    Text(
                        text = stringResource(step),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- privacy

/**
 * Trust statement: spells out exactly what this app does with the user's
 * data — everything on-device, nothing uploaded, no history. It lives in
 * the UI (not only in a store listing) so the promise is visible to users
 * who never read privacy policies, and it mirrors what the code actually
 * enforces (FLAG_SECURE overlays, password/input skipping, disabled
 * backups, HTTPS-only model downloads).
 */
@Composable
private fun PrivacyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.privacy_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.privacy_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val bullets = listOf(
                R.string.privacy_bullet_offline,
                R.string.privacy_bullet_no_upload,
                R.string.privacy_bullet_secure_overlay,
                R.string.privacy_bullet_no_inputs,
                R.string.privacy_bullet_network
            )
            for (bullet in bullets) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(bullet),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------- general settings

/**
 * v1.5.0 — the SIMPLE settings card the users asked for: two choices and a
 * button, in plain language.
 *  - App language: English ⇄ دری, applied instantly (whole UI, toasts,
 *    overlay menus included — everything localized).
 *  - Model updates: who approves Google's language-model updates
 *    (Ask me first / Auto on Wi-Fi / Never) + a manual "Check now" button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralSettingsCard(
    appLanguage: String,
    onLanguageSelected: (String) -> Unit,
    updatePolicy: ModelUpdatePolicy,
    onUpdatePolicySelected: (ModelUpdatePolicy) -> Unit,
    updateRunning: Boolean,
    onCheckNow: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_general_section),
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = appLanguage != "fa",
                    onClick = { onLanguageSelected("en") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(appLanguage != "fa") }
                ) {
                    Text(stringResource(R.string.lang_english))
                }
                SegmentedButton(
                    selected = appLanguage == "fa",
                    onClick = { onLanguageSelected("fa") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { SegmentedButtonDefaults.Icon(appLanguage == "fa") }
                ) {
                    Text(stringResource(R.string.lang_dari))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            Text(
                text = stringResource(R.string.settings_update_policy),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val policies = listOf(
                    ModelUpdatePolicy.ASK_FIRST to R.string.update_policy_ask,
                    ModelUpdatePolicy.AUTO_WIFI to R.string.update_policy_auto,
                    ModelUpdatePolicy.NEVER to R.string.update_policy_never
                )
                for ((policy, label) in policies) {
                    FilterChip(
                        selected = updatePolicy == policy,
                        onClick = { onUpdatePolicySelected(policy) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
            Text(
                text = stringResource(
                    when (updatePolicy) {
                        ModelUpdatePolicy.ASK_FIRST -> R.string.update_desc_ask
                        ModelUpdatePolicy.AUTO_WIFI -> R.string.update_desc_auto
                        ModelUpdatePolicy.NEVER -> R.string.update_desc_never
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onCheckNow, enabled = !updateRunning) {
                    Text(stringResource(R.string.update_check_now))
                }
                if (updateRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- zapya share

/**
 * ZAPYA OFFLINE SHARING (v1.5.0): send the app AND its downloaded language
 * models to a friend phone-to-phone. The friend installs the APK, taps
 * "Import models" and picks the received ZIP — full offline translation
 * without ever touching the internet.
 */
@Composable
private fun ShareCard(
    busy: Boolean,
    onShare: () -> Unit,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.share_section),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.share_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onShare, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share_preparing))
                    } else {
                        Text(stringResource(R.string.share_button))
                    }
                }
                TextButton(onClick = onImport, enabled = !busy) {
                    Text(stringResource(R.string.share_import))
                }
            }
            Text(
                text = stringResource(R.string.share_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
