package com.jinn.talktothehand.presentation

import java.io.File
import java.io.IOException

/**
 * Manages a session lock file to detect ungraceful shutdowns.
 *
 * This class abstracts the file I/O for creating and deleting a lock file,
 * exposing a simple property-based API for checking and managing the lock state.
 */
class SessionLock(filesDir: File) {

    private val lockFile = File(filesDir, ".recording_session.lock")

    /**
     * A property to check if a session is currently locked (i.e., the lock file exists).
     * Reading this property directly checks the file system.
     */
    val isLocked: Boolean
        get() = lockFile.exists()

    /**
     * Creates the lock file and writes the reason for starting the session.
     * @param reason A description of why the session started (e.g., "User Action").
     * @return True if the lock was successfully created, false otherwise.
     */
    fun lock(reason: String): Boolean {
        return try {
            val lockContent = "Started at: ${System.currentTimeMillis()}, Reason: $reason"
            lockFile.writeText(lockContent)
            true
        } catch (e: IOException) {
            // In a production app, you might inject a logger here.
            e.printStackTrace()
            false
        }
    }

    /**
     * Deletes the lock file, signifying a clean shutdown.
     */
    fun unlock() {
        if (isLocked) {
            lockFile.delete()
        }
    }

    /**
     * Reads the content of the lock file, if it exists.
     * @return The content of the lock file, or null if it doesn't exist or cannot be read.
     */
    fun readLockReason(): String? {
        return if (isLocked) {
            try {
                lockFile.readText()
            } catch (e: IOException) {
                e.printStackTrace()
                "Could not read lock file reason."
            }
        } else {
            null
        }
    }
}
