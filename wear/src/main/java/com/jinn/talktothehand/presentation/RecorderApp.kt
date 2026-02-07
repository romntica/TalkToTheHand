package com.jinn.talktothehand.presentation

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.*
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme

/**
 * Main UI for the Wear OS Recorder.
 * Dynamically switches between Control mode (during recording) and Config mode (standby).
 */
@Composable
fun RecorderApp(
    guardian: RecordingGuardian,
    viewModel: RecorderViewModel = viewModel()
) {
    val context = LocalContext.current
    
    TalkToTheHandTheme {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Status Header
                Text(
                    text = when {
                        viewModel.isPaused -> "PAUSED"
                        viewModel.isRecording -> "RECORDING"
                        else -> "STANDBY"
                    },
                    style = MaterialTheme.typography.caption1,
                    color = when {
                        viewModel.isPaused -> Color.Yellow
                        viewModel.isRecording -> Color.Red
                        else -> Color.Gray
                    },
                    textAlign = TextAlign.Center
                )

                // Timer display
                Text(
                    text = viewModel.getFormattedTime(),
                    style = MaterialTheme.typography.display2,
                    textAlign = TextAlign.Center
                )
                
                if (viewModel.isRecording) {
                    Text(
                        text = "Chunk #${viewModel.sessionChunkCount} (${viewModel.fileSizeString})",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dynamic Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (!viewModel.isRecording) {
                        // --- STANDBY MODE ---
                        Button(
                            onClick = { viewModel.startRecording() },
                            colors = ButtonDefaults.primaryButtonColors(),
                            enabled = !viewModel.isBusy
                        ) {
                            Text("START")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                context.startActivity(Intent(context, SettingsActivity::class.java))
                            },
                            colors = ButtonDefaults.secondaryButtonColors(),
                            enabled = !viewModel.isBusy
                        ) {
                            Text("SET")
                        }
                    } else {
                        // --- RECORDING MODE ---
                        // Pause / Resume Button
                        Button(
                            onClick = {
                                if (viewModel.isPaused) viewModel.resumeRecording() 
                                else viewModel.pauseRecording()
                            },
                            colors = ButtonDefaults.secondaryButtonColors(),
                            enabled = !viewModel.isBusy
                        ) {
                            Text(if (viewModel.isPaused) "RESUME" else "PAUSE")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Stop Button
                        Button(
                            onClick = { viewModel.stopRecording() },
                            colors = ButtonDefaults.secondaryButtonColors(),
                            enabled = !viewModel.isBusy
                        ) {
                            Text("STOP")
                        }
                    }
                }
                
                // Error Display with Auto-dismiss hint
                viewModel.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.caption3,
                        color = Color.Yellow,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
            }
        }
    }
}
