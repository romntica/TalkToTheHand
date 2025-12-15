package com.jinn.talktothehand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    
    // Permission launcher for notification permission (Android 13+)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, 
                    "Notifications disabled. You won't receive updates about recordings.", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var transferStatusText: TextView
    
    // Protocol Version 1
    private val PROTOCOL_VERSION = 1

    private val transferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VoiceRecordingListenerService.ACTION_TRANSFER_STATUS) {
                val status = intent.getStringExtra(VoiceRecordingListenerService.EXTRA_STATUS)
                val filename = intent.getStringExtra(VoiceRecordingListenerService.EXTRA_FILENAME)
                
                runOnUiThread {
                    when (status) {
                        VoiceRecordingListenerService.STATUS_STARTED -> {
                            transferStatusText.text = "Receiving: $filename..."
                            transferStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_dark))
                        }
                        VoiceRecordingListenerService.STATUS_COMPLETED -> {
                            transferStatusText.text = "Saved: $filename"
                            transferStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                        }
                        VoiceRecordingListenerService.STATUS_FAILED -> {
                            transferStatusText.text = "Failed to receive: $filename"
                            transferStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        findViewById<TextView>(R.id.status_text).text = getString(R.string.status_ready)
        transferStatusText = findViewById(R.id.transfer_status_text)
        
        findViewById<Button>(R.id.settings_button).setOnClickListener {
            showSettingsDialog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(VoiceRecordingListenerService.ACTION_TRANSFER_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transferReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(transferReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
    
    private fun showSettingsDialog() {
        Toast.makeText(this, "Fetching settings...", Toast.LENGTH_SHORT).show()
        
        requestConfigFromWatch { currentChunkKb, currentStorageMb, currentBitrate, currentSampleRate, autoStart, telemetryEnabled ->
            runOnUiThread {
                @Suppress("InflateParams")
                val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
                val chunkInput = dialogView.findViewById<EditText>(R.id.chunk_size_input)
                val storageInput = dialogView.findViewById<EditText>(R.id.storage_size_input)
                val bitrateSpinner = dialogView.findViewById<Spinner>(R.id.bitrate_spinner)
                val sampleRateSpinner = dialogView.findViewById<Spinner>(R.id.sample_rate_spinner)
                val autoStartCheckbox = dialogView.findViewById<CheckBox>(R.id.auto_start_checkbox)
                val telemetryCheckbox = dialogView.findViewById<CheckBox>(R.id.telemetry_checkbox)
                
                val chunkMb = currentChunkKb / 1024.0
                chunkInput.setText(chunkMb.toString())
                storageInput.setText(currentStorageMb.toString())
                autoStartCheckbox.isChecked = autoStart
                telemetryCheckbox.isChecked = telemetryEnabled
                
                val bitrates = arrayOf(16000, 32000, 64000, 128000)
                val bitrateLabels = arrayOf("16 KBps", "32 KBps", "64 KBps", "128 KBps")
                val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateLabels)
                bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                bitrateSpinner.adapter = bitrateAdapter
                
                val currentBitrateIndex = bitrates.indexOf(currentBitrate)
                if (currentBitrateIndex >= 0) {
                    bitrateSpinner.setSelection(currentBitrateIndex)
                } else {
                    bitrateSpinner.setSelection(1) 
                }
                
                val sampleRates = arrayOf(16000, 44100, 48000)
                val sampleRateLabels = arrayOf("16 kHz", "44.1 kHz", "48 kHz")
                val sampleRateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sampleRateLabels)
                sampleRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                sampleRateSpinner.adapter = sampleRateAdapter
                
                val currentSampleRateIndex = sampleRates.indexOf(currentSampleRate)
                if (currentSampleRateIndex >= 0) {
                    sampleRateSpinner.setSelection(currentSampleRateIndex)
                } else {
                    sampleRateSpinner.setSelection(0) 
                }
                
                AlertDialog.Builder(this)
                    .setTitle("Wear App Recorder Setting")
                    .setView(dialogView)
                    .setPositiveButton("Save") { _, _ ->
                        val newChunkMbStr = chunkInput.text.toString()
                        val newChunkMb = newChunkMbStr.toDoubleOrNull() ?: 1.0
                        val newChunkKb = (newChunkMb * 1024).toInt()
                        val constrainedChunkKb = newChunkKb.coerceIn(512, 10240)
                        
                        val newStorageMbStr = storageInput.text.toString()
                        val newStorageMb = newStorageMbStr.toIntOrNull() ?: 2048
                        val constrainedStorageMb = newStorageMb.coerceIn(100, 10240)
                        
                        val selectedBitrate = bitrates[bitrateSpinner.selectedItemPosition]
                        val selectedSampleRate = sampleRates[sampleRateSpinner.selectedItemPosition]
                        val selectedAutoStart = autoStartCheckbox.isChecked
                        val selectedTelemetry = telemetryCheckbox.isChecked
                        
                        sendConfigToWatch(constrainedChunkKb, constrainedStorageMb, selectedBitrate, selectedSampleRate, selectedAutoStart, selectedTelemetry)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    // Callback: Chunk(KB), Storage(MB), Bitrate(bps), SampleRate(Hz), AutoStart(Boolean), Telemetry(Boolean)
    private fun requestConfigFromWatch(callback: (Int, Int, Int, Int, Boolean, Boolean) -> Unit) {
        val messageClient = Wearable.getMessageClient(this)
        val isCallbackInvoked = AtomicBoolean(false)
        
        val listener = object : com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener {
            override fun onMessageReceived(messageEvent: com.google.android.gms.wearable.MessageEvent) {
                // Check for v2 response only
                if (messageEvent.path == "/config/current_v2") {
                    if (isCallbackInvoked.compareAndSet(false, true)) {
                        try {
                            val buffer = ByteBuffer.wrap(messageEvent.data)
                            val version = buffer.int
                            if (version == PROTOCOL_VERSION) {
                                val chunkKb = buffer.int
                                val storageMb = buffer.int
                                val bitrate = buffer.int
                                val sampleRate = buffer.int
                                val autoStartInt = buffer.int
                                val telemetryInt = buffer.int
                                callback(chunkKb, storageMb, bitrate, sampleRate, autoStartInt == 1, telemetryInt == 1)
                            } else {
                                // Unknown version, fallback to default or try to parse best effort
                                callback(1024, 2048, 32000, 16000, false, false)
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error parsing v2 config", e)
                            callback(1024, 2048, 32000, 16000, false, false)
                        }
                        messageClient.removeListener(this)
                    }
                }
            }
        }
        messageClient.addListener(listener)
        
        executor.execute {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                for (node in nodes) {
                    // Request v2 config only
                    messageClient.sendMessage(node.id, "/config/request_v2", null)
                        .addOnFailureListener { e -> Log.w("MainActivity", "Failed to send request", e) }
                }
                
                if (nodes.isEmpty()) {
                    if (isCallbackInvoked.compareAndSet(false, true)) {
                        runOnUiThread { 
                            Toast.makeText(this, "No Watch Connected", Toast.LENGTH_SHORT).show()
                            callback(1024, 2048, 32000, 16000, false, false) 
                            messageClient.removeListener(listener)
                        }
                    }
                    return@execute
                }
                
                try {
                    Thread.sleep(2000) 
                } catch (_: InterruptedException) {}
                
                if (isCallbackInvoked.compareAndSet(false, true)) {
                    runOnUiThread {
                        Toast.makeText(this, "Watch not responding, using defaults", Toast.LENGTH_SHORT).show()
                        callback(1024, 2048, 32000, 16000, false, false)
                        messageClient.removeListener(listener)
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                if (isCallbackInvoked.compareAndSet(false, true)) {
                    runOnUiThread {
                        callback(1024, 2048, 32000, 16000, false, false)
                        messageClient.removeListener(listener)
                    }
                }
            }
        }
    }
    
    private fun sendConfigToWatch(chunkKb: Int, storageMb: Int, bitrate: Int, sampleRate: Int, autoStart: Boolean, telemetryEnabled: Boolean) {
        // v2 Packet: [VERSION][CHUNK][STORAGE][BITRATE][RATE][AUTOSTART][TELEMETRY]
        val buffer = ByteBuffer.allocate(28) // 7 ints * 4 bytes
        buffer.putInt(PROTOCOL_VERSION)
        buffer.putInt(chunkKb)
        buffer.putInt(storageMb)
        buffer.putInt(bitrate)
        buffer.putInt(sampleRate)
        buffer.putInt(if (autoStart) 1 else 0)
        buffer.putInt(if (telemetryEnabled) 1 else 0)
        
        executor.execute {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                for (node in nodes) {
                    // Send to v2 path
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, "/config/update_v2", buffer.array())
                        .addOnFailureListener { e -> Log.w("MainActivity", "Failed to send config", e) }
                }
                runOnUiThread {
                    Toast.makeText(this, "Settings Saved to Watch", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to send config", e)
            }
        }
    }
}
