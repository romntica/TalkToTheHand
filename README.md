# TalkToTheHand ✋⌚

**TalkToTheHand** is a robust, battery-efficient voice recorder for **Wear OS**, complete with an **Android Mobile** companion app for file management and remote configuration.

The app is architected for reliability, featuring background recording, resilient file transfers, and detailed telemetry for debugging.

> **Note:** The entire codebase, architecture, and documentation for this project were generated with the assistance of **Google Gemini**.

## Features

### ⌚ Wear OS App
*   **Resilient Recording:** Uses a **Foreground Service** to ensure recording continues if the app is backgrounded. Automatically recovers from hardware errors.
*   **Advanced Power Saving:** Implements multiple strategies to maximize battery life:
    *   **Write Batching:** Minimizes power-hungry storage writes by using a small, efficient buffer with frequent fsyncs.
    *   **CPU Gating:** Reduces CPU wake-ups by processing audio in efficient chunks.
    *   **Configurable VAD:** Features a software-based Voice Activity Detection with a "Standard" mode (sleeps CPU) and an **"Aggressive" mode** (powers down the microphone)
*   **Crash-Proof Files:** Saves audio in a raw **ADTS AAC** format, ensuring files are always playable, even if the app crashes mid-recording.
*   **Smart Storage Management:** Automatically stops when a configurable storage limit is reached.
*   **Resilient File Transfer:** Uses `WorkManager` with an exponential backoff policy to reliably transfer recordings to the paired phone, automatically handling connection drops.
*   **Haptic Feedback:** Provides tactile feedback for key events like start, stop, and errors.

### 📱 Mobile Companion App
*   **Live Transfer Status:** Shows the real-time status of incoming file transfers.
*   **Full Remote Configuration:** Configure all watch settings (chunk size, storage limits, bitrate, sample rate, power-saving modes) directly from the phone.
*   **Easy File Access:** Saves recordings to the public `Downloads/TalkToTheHand` folder.
*   **Telemetry Logging:** Receives and saves error and event logs from the watch for easy troubleshooting.

## Tech Stack

*   **Language:** Kotlin
*   **UI:** Jetpack Compose (Wear & Mobile)
*   **Architecture:** MVVM-style with a Service layer
*   **Concurrency:** Kotlin Coroutines & StateFlow
*   **Background Work:**
    *   `WorkManager` (File Transfer)
    *   `ForegroundService` (Recording)
*   **Connectivity:** Google Play Services Wearable Data Layer (`ChannelClient`, `MessageClient`)
*   **Audio:** Android `AudioRecord` & `MediaCodec` (AAC/ADTS)

## Architecture Overview

A simple text-based diagram of the key components:

```
[ MainActivity (UI) ] <--> [ RecorderViewModel ] <--> [ RecorderServiceConnection ]
       ^                                                       |
       | (observes)                                            | (binds)
       |                                                       v
       |                                             [ VoiceRecorderService ]
       |                                                       |
       | (holds instance of)                                   v
       +---------------------------------------------> [ VoiceRecorder ]
                                                            (Core Logic: Mic, Codec, VAD, I/O)
```

## Downloads

If you are not a developer and just want to use the app, you can download the latest pre-built APKs for your Android Phone and Wear OS Watch from the **[Releases Page](https://github.com/romntica/TalkToTheHand/releases)**.

## Settings (v1.1.0+)

1.  **Max Chunk Size:** The size of each audio file before it is split and a new one is created. Larger sizes reduce the frequency of Bluetooth transfers, saving battery.
2.  **Max Storage:** The total storage space the app will use on the watch. Recording stops when this limit is reached.
3.  **Bitrate & Sample Rate:** Standard audio quality settings.
4.  **Silence Threshold:** The audio level required to trigger voice activity detection (VAD). See the recommendations below for tuning.

    | Sound Example            | Typical Amplitude | Recorded?       |
    | :----------------------- | :---------------- | :-------------- |
    | Digital/Mic Noise Floor  | 10 - 300          | No              |
    | A quiet whisper          | 800 - 1500        | **Yes**         |
    | Normal talking voice     | 4,000 - 15,000    | **Yes**         |
    | Nearby keyboard typing   | 1,500 - 3,000     | **Yes**         |

    **Tuning Recommendations:**
    *   **Quiet Room:** Start with the default `1000`.
    *   **Noisy Office:** If the recording includes too much background noise or stops unexpectedly, try increasing to `1500` or `2000`.

5.  **Silence Power Saving:** The strategy used to save battery during silent periods.
    *   **Standard (Reliable):** Sleeps the CPU but keeps the microphone active. Good battery savings with zero risk of audio loss.
    *   **Aggressive (Battery Saver):** Powers down the microphone during long silences. It utilizes an **exponential backoff** strategy (ranging from 1s up to 30s) to minimize system IPC overhead and battery drain. Offers the best battery life but may have a slight wake-up latency.
    *   **Note : power saving efficiency of each policy could be depending on the device.**


6.  **Auto-Start on Boot:** Automatically launches the app and begins recording (if configured) when the watch starts up.
7.  **Telemetry:** Sends event and error logs with system metadata to the phone (`Downloads/TalkToTheHand/Logs`) for debugging.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
