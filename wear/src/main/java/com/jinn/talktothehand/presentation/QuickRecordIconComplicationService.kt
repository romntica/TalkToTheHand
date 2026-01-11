package com.jinn.talktothehand.presentation

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import com.jinn.talktothehand.R

/**
 * Small Image-based complication that toggles recording.
 * Rolled back to stable SMALL_IMAGE type with vector icons.
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

        val config = RecorderConfig(this)
        val isRecording = config.isRecording

        val intent = Intent(this, TransparentServiceLauncherActivity::class.java).apply {
            action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            request.complicationInstanceId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = if (isRecording) {
            R.drawable.ic_complication_stop
        } else {
            R.drawable.ic_complication_record
        }

        val contentDesc = if (isRecording) "Stop Recording" else "Start Recording"

        val data = SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(
                Icon.createWithResource(this, iconRes),
                SmallImageType.ICON
            ).build(),
            contentDescription = PlainComplicationText.Builder(contentDesc).build()
        )
        .setTapAction(pendingIntent)
        .build()

        listener.onComplicationData(data)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(
                Icon.createWithResource(this, R.drawable.ic_complication_record),
                SmallImageType.ICON
            ).build(),
            contentDescription = PlainComplicationText.Builder("Toggle Preview").build()
        ).build()
    }
}
