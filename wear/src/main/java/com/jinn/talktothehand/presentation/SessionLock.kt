package com.jinn.talktothehand.presentation

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages a session lock file to detect ungraceful shutdowns and support recovery.
 */
class SessionLock(filesDir: File) {

    private val lockFile = File(filesDir, ".recording_session.lock")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    val isLocked: Boolean
        get() = lockFile.exists()

    /**
     * Locks the session and records the current file path for recovery.
     */
    fun lock(reason: String, lastFilePath: String? = null): Boolean {
        return try {
            val startTime = dateFormat.format(Date())
            val lockContent = buildString {
                append("Status: ACTIVE\n")
                append("Started: $startTime\n")
                append("Reason: $reason\n")
                append("LastTick: $startTime\n")
                lastFilePath?.let { append("LastFile: $it\n") }
            }
            lockFile.writeText(lockContent)
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun updateTick() {
        if (!isLocked) return
        try {
            val lines = lockFile.readLines().toMutableList()
            val now = dateFormat.format(Date())
            val idx = lines.indexOfFirst { it.startsWith("LastTick:") }
            if (idx != -1) lines[idx] = "LastTick: $now"
            lockFile.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun getLastFilePath(): String? {
        if (!isLocked) return null
        return try {
            lockFile.readLines().find { it.startsWith("LastFile:") }?.substringAfter("LastFile: ")
        } catch (_: Exception) { null }
    }

    fun unlock() {
        if (isLocked) lockFile.delete()
    }

    fun readLockReason(): String? = if (isLocked) lockFile.readText() else null
}
