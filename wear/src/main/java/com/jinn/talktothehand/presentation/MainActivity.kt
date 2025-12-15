package com.jinn.talktothehand.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearApp(
                checkPermission = { checkPermission() },
                requestPermission = { requestPermission() }
            )
        }
    }
    
    private fun checkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
fun WearApp(
    viewModel: RecorderViewModel = viewModel(),
    checkPermission: () -> Boolean,
    requestPermission: () -> Unit
) {
    TalkToTheHandTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (!viewModel.isRecording) {
                    StartRecordingScreen(
                        onStartClick = {
                            if (checkPermission()) {
                                viewModel.startRecording()
                            } else {
                                requestPermission()
                            }
                        }
                    )
                } else {
                    RecordingScreen(
                        formattedTime = viewModel.getFormattedTime(),
                        fileSize = viewModel.fileSizeString,
                        isPaused = viewModel.isPaused,
                        onPauseClick = { viewModel.pauseRecording() },
                        onResumeClick = { viewModel.resumeRecording() },
                        onStopClick = { viewModel.stopRecording() }
                    )
                }
            }
            
            // Error Handling
            val errorMessage = viewModel.errorMessage
            if (errorMessage != null) {
                // You can replace this with a Dialog or better UI
                // For now, using a simple Text overlay or rely on Toast in the effect
                LaunchedEffect(errorMessage) {
                     // We can't show Toast from here directly without Context, 
                     // but usually better to have a UI element.
                }
                
                // Simple Error Overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background.copy(alpha = 0.8f))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.title2,
                            color = MaterialTheme.colors.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StartRecordingScreen(onStartClick: () -> Unit) {
    Button(
        onClick = onStartClick,
        modifier = Modifier.size(64.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Start Recording",
            modifier = Modifier.size(32.dp)
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = "Tap to Record")
}

@Composable
fun RecordingScreen(
    formattedTime: String,
    fileSize: String,
    isPaused: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Text(
        text = formattedTime,
        style = MaterialTheme.typography.display3,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = fileSize,
        style = MaterialTheme.typography.body1,
        color = MaterialTheme.colors.secondary
    )
    Spacer(modifier = Modifier.height(16.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (isPaused) {
            Button(onClick = onResumeClick) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
            }
        } else {
            Button(onClick = onPauseClick) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Button(onClick = onStopClick) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }
    }
}
