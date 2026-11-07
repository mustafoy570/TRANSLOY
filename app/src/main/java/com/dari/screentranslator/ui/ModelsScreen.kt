package com.dari.screentranslator.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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

    var query by rememberSaveable { mutableStateOf("") }

    val languages = remember {
        ModelManager.LANGUAGES.sortedWith(
            compareByDescending<ModelManager.LanguageEntry> { it.isTarget }
                .thenBy { it.englishName }
        )
    }
    val readyCount = states.count { it.value == MainViewModel.ModelStatus.READY }
    val progress = if (languages.isEmpty()) 0f else readyCount.toFloat() / languages.size

    val filtered = if (query.isBlank()) {
        languages
    } else {
        languages.filter { entry ->
            entry.englishName.contains(query, ignoreCase = true) ||
                entry.nativeName.contains(query) ||
                entry.code.contains(query, ignoreCase = true)
        }
    }

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
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.model_count_summary, readyCount, languages.size
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.models_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { viewModel.downloadAllMissing() }) {
                            Text(stringResource(R.string.model_download_all))
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = stringResource(R.string.models_update_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.models_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.code }) { language ->
                        LanguageRow(
                            language = language,
                            status = states[language.code]
                                ?: MainViewModel.ModelStatus.NOT_DOWNLOADED,
                            onDownload = { viewModel.downloadLanguage(language.code) },
                            onDelete = { viewModel.deleteLanguage(language.code) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (language.isTarget) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Text(
                    text = language.nativeName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (language.isTarget) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.englishName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = language.nativeName + "  ·  " + language.code,
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
                    LinearProgressIndicator(
                        modifier = Modifier
                            .width(84.dp)
                            .height(4.dp),
                        strokeCap = StrokeCap.Round
                    )
                }
                MainViewModel.ModelStatus.READY -> {
                    if (language.isTarget) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
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
