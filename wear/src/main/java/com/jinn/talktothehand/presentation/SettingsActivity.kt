package com.jinn.talktothehand.presentation

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Dialog
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ultra-performance Settings Activity.
 * Disables all expensive scaling and alpha animations to match system app smoothness.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TalkToTheHandTheme {
                SettingsScreen(onDismiss = { finish() })
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    
    var showChunkDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showVadDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showSamplingRateDialog by remember { mutableStateOf(false) }

    // Optimization: Stable string values to prevent re-calculation during scroll
    val bitrateText = remember(viewModel.bitrate) { "${viewModel.bitrate / 1000} kbps" }
    val samplingRateText = remember(viewModel.samplingRate) { "${viewModel.samplingRate / 1000} kHz" }
    val storageText = remember(viewModel.storageSize) { 
        if (viewModel.storageSize >= 1024) "1 GB" else "${viewModel.storageSize} MB" 
    }
    val chunkText = remember(viewModel.chunkSize) { "${viewModel.chunkSize} MB" }
    val vadSensitivityText = remember(viewModel.silenceThreshold) { "${viewModel.silenceThreshold}" }
    
    // Boolean state labels
    val autoStartText = remember(viewModel.autoStart) { if (viewModel.autoStart) "Enabled" else "Disabled" }
    val telemetryText = remember(viewModel.telemetry) { if (viewModel.telemetry) "Enabled" else "Disabled" }
    val aggressiveVadText = remember(viewModel.aggressiveVad) { if (viewModel.aggressiveVad) "ON" else "OFF" }

    // Optimization: Pre-calculate handlers to keep lambdas stable
    val onAutoToggle = remember { { viewModel.autoStart = !viewModel.autoStart } }
    val onTeleToggle = remember { { viewModel.telemetry = !viewModel.telemetry } }
    val onVadToggle = remember { { viewModel.aggressiveVad = !viewModel.aggressiveVad } }
    val onShowChunkDialog = remember { { showChunkDialog = true } }
    val onShowStorageDialog = remember { { showStorageDialog = true } }
    val onShowVadDialog = remember { { showVadDialog = true } }
    val onShowBitrateDialog = remember { { showBitrateDialog = true } }
    val onShowSamplingRateDialog = remember { { showSamplingRateDialog = true } }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        timeText = { TimeText() }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    listState.dispatchRawDelta(it.verticalScrollPixels)
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            // PERFORMANCE CORE: Disable scaling math (Scale 1.0, Alpha 1.0)
            scalingParams = ScalingLazyColumnDefaults.scalingParams(
                edgeScale = 1.0f,
                edgeAlpha = 1.0f,
                minElementHeight = 1.0f,
                maxElementHeight = 1.0f
            ),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item { ListHeader { Text("Settings") } }

            // Standard Item: Auto Start
            item(key = "as") {
                Chip(
                    onClick = onAutoToggle,
                    label = { Text("Auto Start") },
                    secondaryLabel = { Text(autoStartText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Telemetry Log
            item(key = "tl") {
                Chip(
                    onClick = onTeleToggle,
                    label = { Text("Telemetry Log") },
                    secondaryLabel = { Text(telemetryText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Audio Bitrate
            item(key = "br") {
                Chip(
                    onClick = onShowBitrateDialog,
                    label = { Text("Bitrate") },
                    secondaryLabel = { Text(bitrateText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Sampling Rate
            item(key = "sr") {
                Chip(
                    onClick = onShowSamplingRateDialog,
                    label = { Text("Sampling Rate") },
                    secondaryLabel = { Text(samplingRateText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Chunk Limit
            item(key = "cl") {
                Chip(
                    onClick = onShowChunkDialog,
                    label = { Text("Chunk Limit") },
                    secondaryLabel = { Text(chunkText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Total Quota
            item(key = "sq") {
                Chip(
                    onClick = onShowStorageDialog,
                    label = { Text("Total Quota") },
                    secondaryLabel = { Text(storageText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Aggressive VAD
            item(key = "av") {
                Chip(
                    onClick = onVadToggle,
                    label = { Text("Aggressive VAD") },
                    secondaryLabel = { Text(aggressiveVadText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: VAD Sensitivity
            item(key = "vs") {
                Chip(
                    onClick = onShowVadDialog,
                    label = { Text("VAD Sensitivity") },
                    secondaryLabel = { Text(vadSensitivityText) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Action: Apply
            item(key = "app") {
                Button(
                    onClick = {
                        scope.launch {
                            // Offload blocking IO to background dispatcher
                            withContext(Dispatchers.IO) {
                                viewModel.applySettings()
                            }
                            Toast.makeText(context, "Applied", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text("Apply Changes")
                }
            }
            
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Picker Dialogs - Moved list rememberings here to isolate scope
    if (showBitrateDialog) {
        val options = remember { listOf(16000, 32000, 64000, 128000) }
        OptionPicker("Bitrate", options, viewModel.bitrate,
            { viewModel.bitrate = it; showBitrateDialog = false }, { showBitrateDialog = false },
            { "${it / 1000} kbps" })
    }

    if (showSamplingRateDialog) {
        val options = remember { listOf(8000, 16000, 44100, 48000) }
        OptionPicker("Sampling Rate", options, viewModel.samplingRate,
            { viewModel.samplingRate = it; showSamplingRateDialog = false }, { showSamplingRateDialog = false },
            { "${it / 1000} kHz" })
    }

    if (showChunkDialog) {
        val options = remember { listOf(1, 2, 5, 10, 15) }
        OptionPicker("Chunk Size", options, viewModel.chunkSize, 
            { viewModel.chunkSize = it; showChunkDialog = false }, { showChunkDialog = false })
    }

    if (showStorageDialog) {
        val options = remember { listOf(100, 200, 500, 1024) }
        OptionPicker("Max Storage", options, viewModel.storageSize,
            { viewModel.storageSize = it; showStorageDialog = false }, { showStorageDialog = false },
            { if (it >= 1024) "1 GB" else "$it MB" })
    }

    if (showVadDialog) {
        Dialog(showDialog = true, onDismissRequest = { showVadDialog = false }) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("${viewModel.silenceThreshold}", style = MaterialTheme.typography.display3)
                InlineSlider(
                    value = viewModel.silenceThreshold,
                    onValueChange = { viewModel.silenceThreshold = it },
                    valueProgression = 500..2000 step 100,
                    decreaseIcon = { Text("-", style = MaterialTheme.typography.display1) },
                    increaseIcon = { Text("+", style = MaterialTheme.typography.display1) },
                    segmented = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { showVadDialog = false }) { Text("OK") }
            }
        }
    }
}

@Composable
private fun OptionPicker(
    title: String,
    options: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    formatter: (Int) -> String = { "$it MB" }
) {
    Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val state = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 1.0f, edgeAlpha = 1.0f),
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item { ListHeader { Text(title) } }
            items(options) { opt ->
                val label = remember(opt) { formatter(opt) }
                val isSelected = opt == selected
                Chip(
                    onClick = { onSelect(opt) },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isSelected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
