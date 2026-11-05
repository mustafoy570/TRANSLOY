package com.dari.screentranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dari.screentranslator.MainViewModel
import com.dari.screentranslator.R
import com.dari.screentranslator.engine.ModelManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val states by viewModel.modelStates.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    val languages = remember {
        ModelManager.LANGUAGES.sortedWith(
            compareByDescending<ModelManager.LanguageEntry> { it.isTarget }
                .thenBy { it.englishName }
        )
    }
    val readyCount = states.count { it.value == MainViewModel.ModelStatus.READY }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.models_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.models_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.downloadAllMissing() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.model_download_all))
                }
                Text(
                    text = stringResource(R.string.model_count_summary, readyCount, languages.size),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(languages, key = { it.code }) { language ->
                    LanguageRow(
                        language = language,
                        status = states[language.code] ?: MainViewModel.ModelStatus.NOT_DOWNLOADED,
                        onDownload = { viewModel.downloadLanguage(language.code) },
                        onDelete = { viewModel.deleteLanguage(language.code) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    language: ModelManager.LanguageEntry,
    status: MainViewModel.ModelStatus,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.englishName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (language.isTarget) {
                    Text(
                        text = stringResource(R.string.model_target_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = statusLabel(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(status)
                    )
                }
            }
            when (status) {
                MainViewModel.ModelStatus.DOWNLOADING -> {
                    LinearProgressIndicator(modifier = Modifier.width(84.dp))
                }
                MainViewModel.ModelStatus.READY -> {
                    if (!language.isTarget) {
                        TextButton(onClick = onDelete) {
                            Text(stringResource(R.string.model_delete))
                        }
                    }
                }
                else -> {
                    Button(onClick = onDownload) {
                        Text(stringResource(R.string.model_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun statusLabel(status: MainViewModel.ModelStatus): String = when (status) {
    MainViewModel.ModelStatus.DOWNLOADING -> stringResource(R.string.model_downloading)
    MainViewModel.ModelStatus.READY -> stringResource(R.string.model_ready)
    MainViewModel.ModelStatus.ERROR -> stringResource(R.string.model_error)
    MainViewModel.ModelStatus.NOT_DOWNLOADED -> stringResource(R.string.model_not_ready)
}

@Composable
private fun statusColor(status: MainViewModel.ModelStatus) = when (status) {
    MainViewModel.ModelStatus.READY -> MaterialTheme.colorScheme.primary
    MainViewModel.ModelStatus.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
