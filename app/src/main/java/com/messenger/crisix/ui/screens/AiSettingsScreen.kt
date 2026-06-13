package com.messenger.crisix.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.messenger.crisix.R
import com.messenger.crisix.ai.FileDownloadStatus
import com.messenger.crisix.ai.SpeechManager
import com.messenger.crisix.ai.SpeechState
import com.messenger.crisix.ui.components.SettingsSectionTitle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBackClick: () -> Unit,
    settingsViewModel: SettingsViewModel? = null,
    onClearAllChats: (() -> Unit)? = null,
    onRunBenchmark: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val vm = settingsViewModel ?: viewModel<SettingsViewModel>()
    val gpuLayers by vm.aiGpuLayers.collectAsState()
    val contextSize by vm.aiContextSize.collectAsState()
    val batchSize by vm.aiBatchSize.collectAsState()
    val threads by vm.aiThreads.collectAsState()
    val kvCacheType by vm.aiKvCacheType.collectAsState()
    val vulkanDisabled by vm.aiVulkanDisabled.collectAsState()
    val thinkingEnabled by vm.aiThinkingEnabled.collectAsState()
    val lastBenchmark by vm.aiLastBenchmark.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var isBenchmarking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.ai_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back_button)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionTitle(title = stringResource(R.string.ai_settings_gpu))

            Text(
                text = stringResource(R.string.ai_settings_gpu_layers),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = gpuLayers.toFloat(),
                    onValueChange = { vm.setAiGpuLayers(it.roundToInt()) },
                    valueRange = 0f..99f,
                    steps = 98,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "$gpuLayers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (gpuLayers > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp)
                )
            }

            Text(
                text = if (gpuLayers > 0) {
                    stringResource(R.string.ai_settings_gpu_layers_desc_gpu)
                } else {
                    stringResource(R.string.ai_settings_gpu_layers_desc_cpu)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_settings_vulkan_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.ai_settings_vulkan_toggle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vulkanDisabled,
                    onCheckedChange = { vm.setAiVulkanDisabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.error,
                        checkedTrackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    )
                )
            }
            if (!vulkanDisabled) {
                Text(
                    text = stringResource(R.string.ai_settings_vulkan_shader_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ai_settings_thinking_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.ai_settings_thinking_toggle_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = thinkingEnabled,
                    onCheckedChange = { vm.setAiThinkingEnabled(it) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Section: Model Parameters ---
            SettingsSectionTitle(title = stringResource(R.string.ai_settings_model_params))

            SliderSetting(
                label = stringResource(R.string.ai_settings_context_size),
                desc = stringResource(R.string.ai_settings_context_size_desc),
                value = contextSize,
                valueRange = 512f..32768f,
                steps = 30,
                format = { "$it" },
                onValueChange = { vm.setAiContextSize(it.roundToInt()) }
            )

            SliderSetting(
                label = stringResource(R.string.ai_settings_batch_size),
                desc = stringResource(R.string.ai_settings_batch_size_desc),
                value = batchSize,
                valueRange = 64f..2048f,
                steps = 15,
                format = { "$it" },
                onValueChange = { vm.setAiBatchSize(it.roundToInt()) }
            )

            SliderSetting(
                label = stringResource(R.string.ai_settings_threads),
                desc = stringResource(R.string.ai_settings_threads_desc),
                value = threads,
                valueRange = 1f..8f,
                steps = 6,
                format = { "$it" },
                onValueChange = { vm.setAiThreads(it.roundToInt()) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Section: Benchmark ---
            SettingsSectionTitle(title = stringResource(R.string.ai_settings_benchmark))

            if (lastBenchmark != null) {
                val b = lastBenchmark!!
                val elapsedSec = b.tokens.toFloat() / b.tokensPerSec
                Text(
                    text = stringResource(R.string.ai_settings_benchmark_result, b.tokens, elapsedSec.toDouble(), b.tokensPerSec.toDouble()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                Text(
                    text = "TTFT: ${b.ttftMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                val dateStr = remember(b.timestamp) {
                    java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(b.timestamp))
                }
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.ai_settings_benchmark_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Button(
                onClick = {
                    isBenchmarking = true
                    scope.launch {
                        onRunBenchmark?.invoke()
                        isBenchmarking = false
                    }
                },
                enabled = !isBenchmarking,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                if (isBenchmarking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (isBenchmarking) stringResource(R.string.ai_settings_benchmark_running)
                    else stringResource(R.string.ai_settings_benchmark_button)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Section: Speech Models ---
            SettingsSectionTitle(title = stringResource(R.string.ai_speech_models_title))

            val appCtx = LocalContext.current.applicationContext
            val speechManager = remember { SpeechManager.getInstance(appCtx) }
            val speechModelState by speechManager.state.collectAsState()
            val downloadState by speechManager.downloadState.collectAsState()

            when (speechModelState) {
                is SpeechState.Idle -> {
                    if (speechManager.areModelsDownloaded) {
                        Text(
                            text = stringResource(R.string.ai_speech_models_downloaded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        Button(
                            onClick = { scope.launch { speechManager.load() } },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.ai_speech_models_init_button))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.ai_speech_models_not_downloaded),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        Button(
                            onClick = { scope.launch { speechManager.downloadModels() } },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.ai_speech_models_download_button))
                        }
                    }
                }
                is SpeechState.Downloading -> {
                    val ds = downloadState
                    val activeFile = ds.files.firstOrNull { it.status == FileDownloadStatus.Downloading }

                    // Overall progress text
                    Text(
                        text = stringResource(R.string.ai_speech_models_downloading_overall,
                            ds.totalDownloadedHuman,
                            ds.totalBytesHuman,
                            if (ds.overallEtaSeconds > 0) ds.overallEtaHuman else "\u2026"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    // Overall progress bar
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { ds.overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .height(6.dp),
                    )

                    // Speed
                    if (ds.overallSpeedBytesPerSec > 0) {
                        Text(
                            text = ds.speedHuman,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }

                    // Per-file section
                    if (activeFile != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activeFile.file.filename,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${activeFile.bytesHuman} / ${activeFile.totalSizeHuman}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { activeFile.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(4.dp),
                        )
                    }

                    // Done files count
                    val doneCount = ds.files.count { it.status == FileDownloadStatus.Done }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.ai_speech_models_files_done, doneCount, ds.files.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
                    )

                }
                is SpeechState.Loading -> {
                    Text(
                        text = stringResource(R.string.ai_speech_models_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                is SpeechState.Ready -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ai_speech_models_ready),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                speechManager.unload()
                                speechManager.load()
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_speech_models_reinit_button))
                    }
                }
                is SpeechState.Error -> {
                    Text(
                        text = stringResource(R.string.ai_speech_models_error, (speechModelState as SpeechState.Error).message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                    Button(
                        onClick = { scope.launch { speechManager.downloadModels() } },
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_speech_models_retry_button))
                    }
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Section: Memory & Cache ---
            SettingsSectionTitle(title = stringResource(R.string.ai_settings_memory))

            Text(
                text = stringResource(R.string.ai_settings_kv_cache),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Text(
                text = stringResource(R.string.ai_settings_kv_cache_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            Text(
                text = stringResource(R.string.ai_settings_kv_cache_type_label, kvCacheType),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Clear chat button
            Text(
                text = stringResource(R.string.ai_settings_clear_chat),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Text(
                text = stringResource(R.string.ai_settings_clear_chat_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            Button(
                onClick = { showClearDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_settings_clear_chat_button))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.ai_settings_clear_chat_button)) },
            text = { Text(stringResource(R.string.ai_settings_clear_chat_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch { onClearAllChats?.invoke() }
                }) {
                    Text(
                        stringResource(R.string.ai_settings_clear_chat_button),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SliderSetting(
    label: String,
    desc: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Int) -> String,
    onValueChange: (Float) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = value.toFloat(),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = format(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp)
        )
    }

    Text(
        text = desc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

