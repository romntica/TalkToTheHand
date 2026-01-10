# Custom Instructions for Gemini AI

## Core Coding Standards
* **High Quality:** Always prioritize scalability, reliability, and maintainability.
* **Modularization:** Strictly separate responsibilities (e.g., UI vs. Business Logic).
* **Documentation:** All public functions and complex logic must include clear KDoc/Javadoc comments in English.

## Voice Recording Project Rules
1. **Stability & Race Conditions:**
    * Use `isBusy` atomic flags to prevent redundant UI actions.
    * Handle all UI updates on `Dispatchers.Main`.
2. **Persistence (Petting Watchdog):**
    * Maintain a persistent foreground service with `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
    * Use a periodic "Watchdog Tick" in `SessionLock` (every 30s) to monitor service health.
3. **Architecture:**
    * Business Logic (I/O, Recording) resides in `VoiceRecorderService`.
    * UI State Management resides in `RecorderViewModel`.
    * Session integrity is managed via `SessionLock` to detect and recover from crashes.
4. **Data Integrity & Data Loss Prevention:**
    * **Format:** Use **AAC/ADTS** (streamable, crash-resilient).
    * **Disk Sync:** Use `fileChannel.force(false)` with a small **16KB buffer** to minimize data loss during crashes.
    * **Atomic Finalization:** Use file renaming for finalizing recordings.
5. **VAD & Power Management:**
    * Use RMS-based Voice Activity Detection with a 30ms window.
    * Implement **Exponential Backoff** for deep silence (1s base, max 30s) to reduce System IPC overhead and battery drain.
6. **Auto-Recovery Mechanism:**
    * Handle system restarts via `START_STICKY` (intent == null).
    * Upon recovery: Finalize interrupted files with `_recovered` suffix -> Enqueue for transfer -> Silently start a new recording session.
7. **System Optimization:**
    * Disable all system-level backups (`allowBackup="false"`) to prevent unintended app interference or restarts.

## Telemetry & Logging Standards
1. **Diagnostic Context:**
    * All error logs must automatically include System Metadata (Battery %, Free Storage MB, Low Memory State).
2. **Granular Error Tracking:**
    * Differentiate between hardware failures (Mic, Codec), I/O failures (Disk, Permissions), and Network failures (Wearable API, Timeout).
3. **Structured Payload:**
    * Telemetry messages must follow a consistent format: `[Timestamp] [Level] [Tag] Message \n [SystemInfo] Metadata \n [Stacktrace]`.

## Core Functional Requirements
1. **Wearable Audio Capture:**
    * High-quality voice recording directly from Wear OS devices.
    * Real-time monitoring of duration and file size on the watch UI.
2. **Background Operation:**
    * Continuous recording even when the screen is off or the app is in the background.
3. **Automatic File Management:**
    * Automatic file splitting (chunking) when reaching a user-defined size limit (default 5MB).
    * Fast discard of insignificant recordings when stop recording (e.g., shorter than 2 seconds).
4. **Reliable Data Transfer:**
    * Seamless and automatic transfer of recorded chunks to a paired mobile device.
    * Retry mechanism for pending transfers in case of connection loss.
5. **Robust Session Recovery:**
    * Automatic resumption of recording sessions after system reboots or app crashes.
    * Identification and recovery of "abandoned" files from previous sessions.

## Configuration Requirements
1. **Dynamic Parameters:**
    * **Audio Quality:** Configurable Bitrate and Sampling Rate.
    * **Storage Management:** Adjustable Chunk Size limit and total Storage quota.
2. **Persistence & Synchronization:**
    * Configurations must persist across app restarts and updates.
    * Support remote configuration updates from the paired mobile device.
3. **Automation:**
    * Configurable "Auto-start on boot" setting to ensure immediate availability after a device restart.
