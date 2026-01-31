package com.jinn.talktothehand.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.jinn.talktothehand.presentation.theme.TalkToTheHandTheme
import kotlinx.coroutines.delay

/**
 * Main Activity for Wear OS voice recorder.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "Microphone permission is required.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WearApp(
                intent = intent,
                checkPermission = { checkPermission() },
                requestPermission = { requestPermission() },
                onNavigateToSettings = {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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

    companion object {
        const val ACTION_QUICK_RECORD = "com.jinn.talktothehand.action.QUICK_RECORD"
    }
}

@Composable
fun WearApp(
    intent: Intent?,
    viewModel: RecorderViewModel = viewModel(),
    checkPermission: () -> Boolean,
    requestPermission: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    LaunchedEffect(intent?.action) {
        if (intent?.action == MainActivity.ACTION_QUICK_RECORD) {
            intent.action = null
            delay(500)
            if (viewModel.isRecording) {
                viewModel.stopRecording()
            } else {
                if (checkPermission()) viewModel.startRecording() else requestPermission()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshState()
                if (viewModel.isRecording) viewModel.startUiUpdates()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopUiUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val requiresRestart = intent.getBooleanExtra(ConfigListenerService.EXTRA_REQUIRES_RESTART, false)
                if (requiresRestart && viewModel.isRecording) {
                    viewModel.restartRecordingSession()
                }
            }
        }
        val filter = IntentFilter(ConfigListenerService.ACTION_CONFIG_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
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
                        isBusy = viewModel.isBusy,
                        onStartClick = {
                            if (checkPermission()) viewModel.startRecording() else requestPermission()
                        },
                        onSettingsClick = onNavigateToSettings
                    )
                } else {
                    RecordingScreen(
                        isBusy = viewModel.isBusy,
                        formattedTime = viewModel.getFormattedTime(),
                        fileSize = viewModel.fileSizeString,
                        chunkCount = viewModel.sessionChunkCount,
                        isPaused = viewModel.isPaused,
                        onPauseClick = { viewModel.pauseRecording() },
                        onResumeClick = { viewModel.resumeRecording() },
                        onStopClick = { viewModel.stopRecording() }
                    )
                }
            }
            
            viewModel.errorMessage?.let { msg ->
                ErrorOverlay(message = msg, onDismiss = { viewModel.dismissError() })
            }
        }
    }
}

@Composable
fun StartRecordingScreen(isBusy: Boolean, onStartClick: () -> Unit, onSettingsClick: () -> Unit) {
    Button(
        onClick = onStartClick,
        enabled = !isBusy,
        modifier = Modifier.size(64.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Start Recording",
            modifier = Modifier.size(32.dp)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = "Quick Record", style = MaterialTheme.typography.caption1)
    
    Spacer(modifier = Modifier.height(12.dp))
    
    Button(
        onClick = onSettingsClick,
        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
        colors = ButtonDefaults.secondaryButtonColors()
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "Settings",
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun RecordingScreen(
    isBusy: Boolean,
    formattedTime: String,
    fileSize: String,
    chunkCount: Int,
    isPaused: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Text(text = formattedTime, style = MaterialTheme.typography.display3, textAlign = TextAlign.Center)
    
    // Chunk Count Display
    Text(
        text = "Completed Chunks: $chunkCount", 
        style = MaterialTheme.typography.caption2, 
        color = MaterialTheme.colors.primary
    )
    
    Text(text = fileSize, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.secondary)
    Spacer(modifier = Modifier.height(12.dp))
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        if (isPaused) {
            Button(onClick = onResumeClick, enabled = !isBusy) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
            }
        } else {
            Button(onClick = onPauseClick, enabled = !isBusy) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(onClick = onStopClick, enabled = !isBusy) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }
    }
}

@Composable
fun ErrorOverlay(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background.copy(alpha = 0.9f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Error", color = MaterialTheme.colors.error, style = MaterialTheme.typography.title2)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
