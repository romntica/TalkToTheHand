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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Dialog
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme

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
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    
    var showChunkDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showVadDialog by remember { mutableStateOf(false) }

    // Optimization: Pre-calculate handlers to keep lambdas stable
    val onAutoToggle = remember { { viewModel.autoStart = !viewModel.autoStart } }
    val onTeleToggle = remember { { viewModel.telemetry = !viewModel.telemetry } }
    val onVadToggle = remember { { viewModel.aggressiveVad = !viewModel.aggressiveVad } }

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
                    secondaryLabel = { Text(if (viewModel.autoStart) "Enabled" else "Disabled") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Telemetry Log
            item(key = "tl") {
                Chip(
                    onClick = onTeleToggle,
                    label = { Text("Telemetry Log") },
                    secondaryLabel = { Text(if (viewModel.telemetry) "Enabled" else "Disabled") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Chunk Limit
            item(key = "cl") {
                Chip(
                    onClick = { showChunkDialog = true },
                    label = { Text("Chunk Limit") },
                    secondaryLabel = { Text("${viewModel.chunkSize} MB") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Total Quota
            item(key = "sq") {
                Chip(
                    onClick = { showStorageDialog = true },
                    label = { Text("Total Quota") },
                    secondaryLabel = { 
                        Text(if (viewModel.storageSize >= 1024) "1 GB" else "${viewModel.storageSize} MB")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: Aggressive VAD
            item(key = "av") {
                Chip(
                    onClick = onVadToggle,
                    label = { Text("Aggressive VAD") },
                    secondaryLabel = { Text(if (viewModel.aggressiveVad) "ON" else "OFF") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Item: VAD Sensitivity
            item(key = "vs") {
                Chip(
                    onClick = { showVadDialog = true },
                    label = { Text("VAD Sensitivity") },
                    secondaryLabel = { Text("${viewModel.silenceThreshold}") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }

            // Action: Apply
            item(key = "app") {
                Button(
                    onClick = {
                        viewModel.applySettings()
                        Toast.makeText(viewModel.getApplication(), "Applied", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text("Apply Changes")
                }
            }
            
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // Picker Dialogs
    if (showChunkDialog) {
        OptionPicker("Chunk Size", remember { listOf(1, 2, 5, 10, 15) }, viewModel.chunkSize, 
            { viewModel.chunkSize = it; showChunkDialog = false }, { showChunkDialog = false })
    }

    if (showStorageDialog) {
        OptionPicker("Max Storage", remember { listOf(100, 200, 500, 1024) }, viewModel.storageSize,
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
                Chip(
                    onClick = { onSelect(opt) },
                    label = { Text(formatter(opt)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (opt == selected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
