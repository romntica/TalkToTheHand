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

/**
 * Optimized Icon-only Complication Service.
 * Uses SessionState for cross-process status sync.
 */
class QuickRecordIconComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) {
            listener.onComplicationData(null)
            return
        }

        try {
            val context = applicationContext
            // 1. Fast read from binary file (cache-free)
            val state = SessionState(context).read()
            val isRecording = state.isRecording
            val isPaused = state.isPaused

            // 2. Prepare tap action
            val tapIntent = Intent(context, TransparentServiceLauncherActivity::class.java).apply {
                action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val pendingIntent = PendingIntent.getActivity(
                context, request.complicationInstanceId, tapIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 3. Dynamic Icon
            val iconResId = when {
                isPaused -> R.drawable.ic_complication_stop // Visual hint
                isRecording -> R.drawable.ic_complication_record
                else -> R.drawable.ic_complication_stop
            }

            val data = SmallImageComplicationData.Builder(
                smallImage = SmallImage.Builder(Icon.createWithResource(context, iconResId), SmallImageType.ICON).build(),
                contentDescription = PlainComplicationText.Builder("Quick Record").build()
            )
            .setTapAction(pendingIntent)
            .build()

            listener.onComplicationData(data)
            
        } catch (e: Exception) {
            Log.e("IconComplication", "Error", e)
            listener.onComplicationData(null)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication_record), SmallImageType.ICON).build(),
            contentDescription = PlainComplicationText.Builder("Record").build()
        ).build()
    }
}
