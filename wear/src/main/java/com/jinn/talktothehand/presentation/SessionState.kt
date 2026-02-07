package com.jinn.talktothehand.presentation

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * High-performance, Direct-Boot aware state synchronization.
 * Uses Device Protected Storage to ensure availability before first unlock.
 */
class SessionState(context: Context) {
    // CRITICAL: Use Device Protected Storage for Direct Boot compatibility
    private val safeContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
    private val stateFile = File(safeContext.filesDir, "recording_status.bin")
    private val tempFile = File(safeContext.filesDir, "recording_status.bin.tmp")

    companion object {
        private const val TAG = "SessionState"
        private const val MAGIC_NUMBER: Short = 0x5454
        private const val EXPECTED_SIZE = 16L
    }

    fun update(isRecording: Boolean, isPaused: Boolean, chunkCount: Int, sizeBytes: Long) {
        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(0)
                raf.writeShort(MAGIC_NUMBER.toInt())
                raf.writeByte(if (isRecording) 1 else 0)
                raf.writeByte(if (isPaused) 1 else 0)
                raf.writeInt(chunkCount)
                raf.writeLong(sizeBytes)
                raf.fd.sync()
            }
            if (!tempFile.renameTo(stateFile)) {
                Log.e(TAG, "Rename failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update error", e)
        }
    }

    fun read(): State {
        if (!stateFile.exists()) return State()
        try {
            RandomAccessFile(stateFile, "r").use { raf ->
                if (raf.length() < EXPECTED_SIZE) return State()
                if (raf.readShort() != MAGIC_NUMBER) return State()
                return State(
                    isRecording = raf.readByte() == 1.toByte(),
                    isPaused = raf.readByte() == 1.toByte(),
                    chunkCount = raf.readInt(),
                    sizeBytes = raf.readLong()
                )
            }
        } catch (e: Exception) {
            return State()
        }
    }

    data class State(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val chunkCount: Int = 0,
        val sizeBytes: Long = 0L
    )
}
