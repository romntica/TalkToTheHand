package com.jinn.talktothehand.presentation

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import com.jinn.talktothehand.R

/**
 * Data source for the "Progress Record" watch face complication.
 * Correctly uses SmallImageComplicationData.Builder to provide tap actions.
 */
class QuickRecordComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) {
            listener.onComplicationData(null)
            return
        }

        val config = RecorderConfig(this)
        val totalChunks = config.sessionChunkCount

        // 1. Draw custom visualization bitmap
        val bitmap = drawProgressBitmap(0L, config.maxChunkSizeBytes, totalChunks)
        val icon = Icon.createWithBitmap(bitmap)

        // 2. Prepare intent
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_RECORD
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, request.complicationInstanceId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. Proper layering: SmallImage wrapped by SmallImageComplicationData
        val smallImage = SmallImage.Builder(
            image = icon,
            type = SmallImageType.ICON
        ).build()

        val data = SmallImageComplicationData.Builder(
            smallImage = smallImage,
            contentDescription = PlainComplicationText.Builder("Recording Progress").build()
        )
            .setTapAction(pendingIntent)
            .build()

        listener.onComplicationData(data)
    }

    private fun drawProgressBitmap(currentSize: Long, maxSize: Long, chunkCount: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(10f, 10f, size - 10f, size - 10f)
        
        val bgPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 6f
            pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
            isAntiAlias = true
        }
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)

        val progress = if (maxSize > 0) (currentSize.toFloat() / maxSize).coerceIn(0f, 1f) else 0f
        val progressPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)

        val chunkPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }
        val chunkStep = 20f
        for (i in 0 until chunkCount) {
            val startAngle = (i * (chunkStep + 5f)) % 360f
            canvas.drawArc(rect, startAngle, chunkStep, false, chunkPaint)
        }
        return bitmap
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        
        val previewImage = SmallImage.Builder(
            image = Icon.createWithResource(this, R.drawable.ic_complication_record),
            type = SmallImageType.ICON
        ).build()

        return SmallImageComplicationData.Builder(
            smallImage = previewImage,
            contentDescription = PlainComplicationText.Builder("Quick Record Preview").build()
        ).build()
    }
}
