package com.jinn.talktothehand.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme

class MainActivity : ComponentActivity() {

    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your app.
            } else {
                // Explain to the user that the feature is unavailable
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
    // --- Lifecycle-aware UI Updates ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (viewModel.isRecording) {
                    viewModel.startUiUpdates()
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopUiUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // --- Config Change Notification ---
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val requiresRestart = intent.getBooleanExtra(ConfigListenerService.EXTRA_REQUIRES_RESTART, false)
                if (requiresRestart) {
                    if (viewModel.isRecording) {
                        Toast.makeText(context, "Applying new audio settings...", Toast.LENGTH_SHORT).show()
                        viewModel.restartRecordingSession()
                    } else {
                        Toast.makeText(context, "Audio settings updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val filter = IntentFilter(ConfigListenerService.ACTION_CONFIG_CHANGED)
        
        // Android 14+ requires specifying RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

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
            
            // Error Handling Overlay
            val errorMessage = viewModel.errorMessage
            if (errorMessage != null) {
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
