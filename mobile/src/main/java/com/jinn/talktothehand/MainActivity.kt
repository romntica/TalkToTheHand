package com.jinn.talktothehand

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
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

class MainActivity : AppCompatActivity() {
    
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(
                    this, 
                    "Notifications disabled. You won\'t receive updates about recordings.", 
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var transferStatusText: TextView
    
    private val PROTOCOL_VERSION = 3

    // --- Broadcast Receivers ---
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

    private var configReceiver: BroadcastReceiver? = null
    private var settingsDialog: AlertDialog? = null
    private val settingsTimeoutHandler = Handler(Looper.getMainLooper())
    private var settingsTimeoutRunnable: Runnable? = null

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

        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Register transfer status receiver
        val transferFilter = IntentFilter(VoiceRecordingListenerService.ACTION_TRANSFER_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferReceiver, transferFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(transferReceiver, transferFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(transferReceiver)
        cleanupConfigReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
    
    private fun requestNotificationPermission() {
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

    private fun cleanupConfigReceiver() {
        settingsTimeoutRunnable?.let { settingsTimeoutHandler.removeCallbacks(it) }
        settingsTimeoutRunnable = null
        configReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver might have already been unregistered, ignore
            }
        }
        configReceiver = null
    }
    
    private fun showSettingsDialog() {
        cleanupConfigReceiver() // Clean up any previous attempts
        
        Toast.makeText(this, "Fetching settings from watch...", Toast.LENGTH_SHORT).show()
        requestConfigFromWatch()

        // Set up a new receiver to handle the config response
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == VoiceRecordingListenerService.ACTION_CONFIG_RECEIVED) {
                    settingsTimeoutRunnable?.let { settingsTimeoutHandler.removeCallbacks(it) } // Cancel timeout
                    val configData = intent.getByteArrayExtra(VoiceRecordingListenerService.EXTRA_CONFIG_DATA)
                    if (configData != null) {
                        parseAndShowDialog(configData)
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to get settings from watch", Toast.LENGTH_SHORT).show()
                    }
                    cleanupConfigReceiver() // Clean up immediately after use
                }
            }
        }
        configReceiver = receiver
        
        val configFilter = IntentFilter(VoiceRecordingListenerService.ACTION_CONFIG_RECEIVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, configFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, configFilter)
        }

        // Set a timeout for the watch to respond
        settingsTimeoutRunnable = Runnable {
            runOnUiThread {
                Toast.makeText(this, "Watch not responding", Toast.LENGTH_SHORT).show()
                cleanupConfigReceiver()
            }
        }
        settingsTimeoutHandler.postDelayed(settingsTimeoutRunnable!!, 3000) // 3-second timeout
    }

    private fun parseAndShowDialog(configData: ByteArray) {
        val buffer = ByteBuffer.wrap(configData)
        val version = buffer.int
        if (version < 3) { 
            Toast.makeText(this, "Watch app is outdated, please update", Toast.LENGTH_SHORT).show()
            return
        }

        val chunkMb = buffer.int
        val storageMb = buffer.int
        val bitrate = buffer.int
        val sampleRate = buffer.int
        val autoStart = buffer.int == 1
        val telemetry = buffer.int == 1
        val silenceThreshold = buffer.int
        val silenceStrategy = buffer.int

        @Suppress("InflateParams")
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val chunkSeekbar = dialogView.findViewById<SeekBar>(R.id.chunk_size_seekbar)
        val chunkValueText = dialogView.findViewById<TextView>(R.id.chunk_size_value_text)
        val storageInput = dialogView.findViewById<EditText>(R.id.storage_size_input)
        val bitrateSpinner = dialogView.findViewById<Spinner>(R.id.bitrate_spinner)
        val sampleRateSpinner = dialogView.findViewById<Spinner>(R.id.sample_rate_spinner)
        val autoStartCheckbox = dialogView.findViewById<CheckBox>(R.id.auto_start_checkbox)
        val telemetryCheckbox = dialogView.findViewById<CheckBox>(R.id.telemetry_checkbox)

        // VAD Sensitivity (Slider)
        val silenceThresholdSeekbar = dialogView.findViewById<SeekBar>(R.id.silence_threshold_seekbar)
        val silenceThresholdValueText = dialogView.findViewById<TextView>(R.id.silence_threshold_value_text)
        val silenceStrategySpinner = dialogView.findViewById<Spinner>(R.id.silence_strategy_spinner)

        // --- Setup Chunk Size SeekBar ---
        chunkValueText.text = "$chunkMb MB"
        chunkSeekbar.progress = chunkMb - 1 
        chunkSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                chunkValueText.text = "${progress + 1} MB"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Setup VAD Sensitivity Slider ---
        // Range: 500 - 2000, Step: 100. (2000-500)/100 = 15 steps.
        silenceThresholdValueText.text = silenceThreshold.toString()
        val thresholdProgress = ((silenceThreshold - 500) / 100).coerceIn(0, 15)
        silenceThresholdSeekbar.progress = thresholdProgress
        silenceThresholdSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress * 100) + 500
                silenceThresholdValueText.text = value.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        storageInput.setText(storageMb.toString())
        autoStartCheckbox.isChecked = autoStart
        telemetryCheckbox.isChecked = telemetry

        val bitrates = arrayOf(16000, 32000, 64000, 128000)
        val bitrateLabels = arrayOf("16 KBps", "32 KBps", "64 KBps", "128 KBps")
        val bitrateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bitrateLabels)
        bitrateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        bitrateSpinner.adapter = bitrateAdapter
        
        val currentBitrateIndex = bitrates.indexOf(bitrate)
        bitrateSpinner.setSelection(if(currentBitrateIndex >= 0) currentBitrateIndex else 1)
        
        val sampleRates = arrayOf(16000, 44100, 48000)
        val sampleRateLabels = arrayOf("16 kHz", "44.1 kHz", "48 kHz")
        val sampleRateAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sampleRateLabels)
        sampleRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sampleRateSpinner.adapter = sampleRateAdapter
        
        val currentSampleRateIndex = sampleRates.indexOf(sampleRate)
        sampleRateSpinner.setSelection(if(currentSampleRateIndex >= 0) currentSampleRateIndex else 0)
        
        val silenceStrategies = arrayOf(0, 1)
        val silenceStrategyLabels = arrayOf("Standard (Reliable)", "Aggressive (Battery Saver)")
        val silenceStrategyAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, silenceStrategyLabels)
        silenceStrategyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        silenceStrategySpinner.adapter = silenceStrategyAdapter
        silenceStrategySpinner.setSelection(silenceStrategy.coerceIn(0,1))
        
        settingsDialog?.dismiss()
        settingsDialog = AlertDialog.Builder(this)
            .setTitle("Wear App Recorder Setting")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newChunkMb = chunkSeekbar.progress + 1
                val newStorageMb = storageInput.text.toString().toIntOrNull() ?: 2048
                val selectedBitrate = bitrates[bitrateSpinner.selectedItemPosition]
                val selectedSampleRate = sampleRates[sampleRateSpinner.selectedItemPosition]
                val selectedAutoStart = autoStartCheckbox.isChecked
                val selectedTelemetry = telemetryCheckbox.isChecked
                val newSilenceThreshold = (silenceThresholdSeekbar.progress * 100) + 500
                val selectedSilenceStrategy = silenceStrategies[silenceStrategySpinner.selectedItemPosition]
                
                sendConfigToWatch(newChunkMb, newStorageMb, selectedBitrate, selectedSampleRate, selectedAutoStart, selectedTelemetry, newSilenceThreshold, selectedSilenceStrategy)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun requestConfigFromWatch() {
        executor.execute {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                val node = nodes.firstOrNull()
                if (node == null) {
                    runOnUiThread { Toast.makeText(this, "No Watch Connected", Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                Wearable.getMessageClient(this)
                    .sendMessage(node.id, "/config/request_v2", null)
                    .addOnFailureListener { e -> Log.w("MainActivity", "Failed to send request", e) }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error requesting config", e)
            }
        }
    }
    
    private fun sendConfigToWatch(chunkMb: Int, storageMb: Int, bitrate: Int, sampleRate: Int, autoStart: Boolean, telemetryEnabled: Boolean, silenceThreshold: Int, silenceStrategy: Int) {
        // v3 Packet: Adds silence threshold and strategy
        val buffer = ByteBuffer.allocate(36) // 9 ints * 4 bytes
        buffer.putInt(PROTOCOL_VERSION)
        buffer.putInt(chunkMb)
        buffer.putInt(storageMb)
        buffer.putInt(bitrate)
        buffer.putInt(sampleRate)
        buffer.putInt(if (autoStart) 1 else 0)
        buffer.putInt(if (telemetryEnabled) 1 else 0)
        buffer.putInt(silenceThreshold)
        buffer.putInt(silenceStrategy)
        
        executor.execute {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
                for (node in nodes) {
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, "/config/update_v2", buffer.array())
                        .addOnSuccessListener { Log.d("MainActivity", "Config sent to ${node.displayName}") }
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
