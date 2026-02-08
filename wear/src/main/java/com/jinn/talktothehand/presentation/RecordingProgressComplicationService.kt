package com.jinn.talktothehand.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import com.jinn.talktothehand.R
import kotlinx.coroutines.*

/**
 * High-reliability Complication Service.
 * Optimized for Direct Boot and passive state reading.
 */
class RecordingProgressComplicationService : ComplicationDataSourceService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ProgressComplication"
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        serviceScope.launch {
            try {
                val context = applicationContext
                
                // 1. Read state & config via protected storage (Direct Boot compatible)
                val sessionState = SessionState(context).read()
                val config = RecorderConfig(context)
                
                val isRecording = sessionState.isRecording
                val isPaused = sessionState.isPaused
                
                // Use maxChunkSizeMb from config which handles storage migration correctly
                val maxSize = config.maxChunkSizeBytes.toFloat().coerceAtLeast(1f)
                val progressPercent = if (isRecording) (sessionState.sizeBytes.toFloat() / maxSize).coerceIn(0f, 1f) * 100f else 0f

                // 2. UI Assets
                val iconResId = when {
                    isPaused -> R.drawable.ic_complication_stop
                    isRecording -> R.drawable.ic_complication_record
                    else -> R.drawable.ic_complication_stop
                }
                val statusText = if (isRecording && !isPaused) sessionState.chunkCount.toString() else "--"

                // 3. Tap Action
                val tapIntent = Intent(context, TransparentServiceLauncherActivity::class.java).apply {
                    action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, request.complicationInstanceId, tapIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                // 4. Construct Data with resource safety
                val data = when (request.complicationType) {
                    ComplicationType.RANGED_VALUE -> {
                        RangedValueComplicationData.Builder(
                            value = progressPercent, min = 0f, max = 100f,
                            contentDescription = PlainComplicationText.Builder("Status").build()
                        )
                        .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(context, iconResId)).build())
                        .setText(PlainComplicationText.Builder(statusText).build())
                        .setTapAction(pendingIntent)
                        .build()
                    }
                    ComplicationType.SMALL_IMAGE -> {
                        SmallImageComplicationData.Builder(
                            smallImage = SmallImage.Builder(Icon.createWithResource(context, iconResId), SmallImageType.ICON).build(),
                            contentDescription = PlainComplicationText.Builder("Status").build()
                        )
                        .setTapAction(pendingIntent)
                        .build()
                    }
                    ComplicationType.SHORT_TEXT -> {
                        ShortTextComplicationData.Builder(
                            text = PlainComplicationText.Builder(statusText).build(),
                            contentDescription = PlainComplicationText.Builder("Status").build()
                        )
                        .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(context, iconResId)).build())
                        .setTapAction(pendingIntent)
                        .build()
                    }
                    else -> null
                }

                listener.onComplicationData(data)

            } catch (e: Exception) {
                Log.e(TAG, "Async request failed", e)
                listener.onComplicationData(null)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication_record)).build()
        val text = PlainComplicationText.Builder("1").build()
        return RangedValueComplicationData.Builder(50f, 0f, 100f, text).setMonochromaticImage(icon).setText(text).build()
    }
}
