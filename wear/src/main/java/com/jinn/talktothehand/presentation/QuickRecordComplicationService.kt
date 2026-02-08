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
 * Super-optimized Icon-only Complication.
 * Supports MonochromaticImage and SmallImage for maximum compatibility with icon slots.
 */
class QuickRecordComplicationService : ComplicationDataSourceService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "QuickIconComplication"
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        serviceScope.launch {
            try {
                val context = applicationContext
                val guardian = RecordingGuardian(context)
                
                // 1. Force state correction
                guardian.verifyIntegrity()

                // 2. Read state
                val state = SessionState(context).read()
                
                // 3. Select Icon
                val iconResId = when {
                    state.isPaused -> R.drawable.ic_complication_stop
                    state.isRecording -> R.drawable.ic_complication_record
                    else -> R.drawable.ic_complication_stop
                }

                // 4. Build Intent
                val tapIntent = Intent(context, TransparentServiceLauncherActivity::class.java).apply {
                    action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, request.complicationInstanceId, tapIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                // 5. Construct pure Image Data
                val data = when (request.complicationType) {
                    ComplicationType.MONOCHROMATIC_IMAGE -> {
                        MonochromaticImageComplicationData.Builder(
                            monochromaticImage = MonochromaticImage.Builder(Icon.createWithResource(context, iconResId)).build(),
                            contentDescription = PlainComplicationText.Builder("Quick Record").build()
                        )
                        .setTapAction(pendingIntent)
                        .build()
                    }
                    ComplicationType.SMALL_IMAGE -> {
                        SmallImageComplicationData.Builder(
                            smallImage = SmallImage.Builder(Icon.createWithResource(context, iconResId), SmallImageType.ICON).build(),
                            contentDescription = PlainComplicationText.Builder("Quick Record").build()
                        )
                        .setTapAction(pendingIntent)
                        .build()
                    }
                    else -> null
                }

                listener.onComplicationData(data)
                
            } catch (e: Exception) {
                Log.e(TAG, "Request failed", e)
                listener.onComplicationData(null)
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_complication_record)
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    MonochromaticImage.Builder(icon).build(),
                    PlainComplicationText.Builder("Record").build()
                ).build()
            }
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(icon, SmallImageType.ICON).build(),
                    PlainComplicationText.Builder("Record").build()
                ).build()
            }
            else -> null
        }
    }
}
