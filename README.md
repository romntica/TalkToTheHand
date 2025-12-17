# TalkToTheHand ✋⌚

**TalkToTheHand** is a robust voice recorder application for **Wear OS**, accompanied by a generic **Android Mobile** companion app for file management and configuration.

The app is designed for reliability, handling background recording, file transfer resilience, and remote telemetry for debugging.

> **Note:** The entire codebase, architecture, and documentation for this project were generated with the assistance of **Google Gemini**.

## Features

### ⌚ Wear OS App
*   **Robust Recording:** Uses a **Foreground Service** to ensure recording continues even if the screen turns off or the user navigates away.
*   **Configurable Quality:** Supports multiple sampling rates (16kHz, 44.1kHz, 48kHz) and bitrates via settings.
*   **Smart Storage Management:** Automatically stops when storage limits (configurable) are reached.
*   **Resilient File Transfer:**
    *   Automatically transfers recordings to the paired phone.
    *   Uses **WorkManager** with an **Exponential Backoff** retry policy to handle connection drops.
    *   Periodically scans for and retries sending pending files.
*   **Haptic Feedback:** Vibrates on start, stop, and error events for eyes-free operation.
*   **Remote Telemetry:** Optionally sends error logs to the phone for easier debugging.
*   **Auto-Start:** Option to automatically launch the recorder when the watch boots.

### 📱 Mobile Companion App
*   **Live Status:** Shows real-time status of incoming file transfers.
*   **Remote Configuration:** Configure the watch's recording settings (Chunk Size, Storage Limit, Bitrate, Sample Rate, Auto-start) directly from the phone.
*   **File Management:** Saves recordings to the public `Downloads/TalkToTheHand` folder for easy access.
*   **Telemetry Logging:** Receives and saves error logs from the watch to `wear_logs.txt` for troubleshooting.

## Tech Stack

*   **Language:** Kotlin
*   **UI:** Jetpack Compose (Wear & Mobile)
*   **Architecture:** MVVM (Model-View-ViewModel)
*   **Concurrency:** Kotlin Coroutines & Flow
*   **Background Work:**
    *   `WorkManager` (File Transfer)
    *   `ForegroundService` (Recording)
*   **Connectivity:** Google Play Services Wearable Data Layer API (`ChannelClient`, `MessageClient`)
*   **Audio:** Android `AudioRecord` & `MediaCodec` (AAC/M4A)

## Downloads

If you are not a developer and just want to use the app, you can download the latest pre-built APKs for your Android Phone and Wear OS Watch from the **[Releases Page](https://github.com/romntica/TalkToTheHand/releases)**.

## Getting Started

### Prerequisites
*   Android Studio Ladybug or newer (recommended).
*   A physical Wear OS device (Wear OS 3.0+) and an Android Phone, or Emulators.

### Installation (For Developers)
1.  Clone the repository:
    ```bash
    git clone https://github.com/romntica/TalkToTheHand.git
    ```
2.  Open the project in Android Studio.
3.  Build and run the `mobile` configuration on your phone.
4.  Build and run the `wear` configuration on your watch/emulator.

**Note:** Ensure both devices are paired via Bluetooth or connected to the same network for the Wearable Data Layer to function.

## Architecture Overview

*   **RecorderViewModel (Wear):** Manages the UI state and communicates with the `VoiceRecorderService`.
*   **VoiceRecorderService (Wear):** A Foreground Service that holds the `VoiceRecorder` instance, ensuring process priority.
*   **FileTransferManager (Wear):** Queues finished recordings into `WorkManager`. If the phone is disconnected, it retries with an exponential backoff strategy.
*   **VoiceRecordingListenerService (Mobile):** A `WearableListenerService` that listens for incoming file channels and saves the data to the local file system.

## Usage

1.  **Start Recording:** Tap the microphone icon on the watch.
2.  **Stop:** Tap the stop button. The file is saved locally and queued for transfer.
3.  **Transfer:** Watch for the status on the mobile app ("Receiving...").
4.  **Access:** Find your files in the `Downloads/TalkToTheHand` folder on your phone.

## Settings (v1.1.0~)

1. **Max Chunk Size:** it decides chunk size for transferring to mobile. if it's too small then there are frequently IO access.
2. **Max Storage:** it limits wear side storage. if the peer mobile is not ready then chunk file stored in wear until reach the limitation. if it reached the limitation then recording will not work.
3. **Bitrate:** Audio Bitrate
4. **Sample Rate:** Audio Sample rate
5. **Silence Threshold:** Audio Silence Threshold. we are using software VAD (voice activity detection.). if the input audio level is less than the threshold then it will be dropped and not stored.
6. **Silence Power Saving:** power saving strategy
    Standard : just sleep for CPU idle
    Aggresive : sleep and turn off the MIC. (! it could lose initial audio data, maybe under 500 msec)
7. **Auto-Start on Boot:** Automatically launch the recorder when the watch boots.
8. **Telemetry:** Optionally sends error logs to the phone for easier debugging. (under Download/TalkToTheHand/logs)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
