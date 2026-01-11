package com.jinn.talktothehand.presentation

import android.app.PendingIntent
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import com.jinn.talktothehand.R

/**
 * RANGED_VALUE complication that toggles recording on tap.
 * Shows progress gauge and session statistics with enhanced visual feedback.
 */
class RecordingProgressComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val config = RecorderConfig(this)
        val isRecording = config.isRecording
        val chunkCount = config.sessionChunkCount
        
        // Progress calculation: Current vs Max chunk size
        val currentSize = config.currentChunkSizeBytes.toFloat()
        val maxSize = config.maxChunkSizeBytes.toFloat().coerceAtLeast(1f)
        
        // Visual enhancement: Show at least 4% if recording just started, to indicate activity
        val progressPercent = if (isRecording) {
            (currentSize / maxSize).coerceIn(0.04f, 1f) * 100f
        } else {
            0f
        }

        // Tap action: Toggles recording via Transparent Activity (Android 14+ safe)
        val intent = Intent(this, TransparentServiceLauncherActivity::class.java).apply {
            action = VoiceRecorderService.ACTION_TOGGLE_RECORDING
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            request.complicationInstanceId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentDesc = if (isRecording) "Recording Chunk #$chunkCount" else "Standby - Tap to Record"

        val data = when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = progressPercent,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder(contentDesc).build()
                )
                .setMonochromaticImage(
                    MonochromaticImage.Builder(
                        Icon.createWithBitmap(drawStatusIcon(isRecording))
                    ).build()
                )
                .setText(PlainComplicationText.Builder(chunkCount.toString()).build())
                .setTapAction(pendingIntent)
                .build()
            }
            ComplicationType.SMALL_IMAGE -> {
                val bitmap = drawEnhancedSegmentedBitmap(progressPercent / 100f, chunkCount, isRecording)
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.ICON).build(),
                    contentDescription = PlainComplicationText.Builder(contentDesc).build()
                )
                .setTapAction(pendingIntent)
                .build()
            }
            else -> null
        }

        listener.onComplicationData(data)
    }

    private fun drawStatusIcon(isRecording: Boolean): Bitmap {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
        }
        
        if (isRecording) {
            // Recording: Small solid dot
            canvas.drawCircle(size / 2f, size / 2f, size / 5f, paint)
        } else {
            // Standby: Outlined circle
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawCircle(size / 2f, size / 2f, size / 4f, paint)
        }
        return bitmap
    }

    private fun drawEnhancedSegmentedBitmap(progress: Float, chunkCount: Int, isRecording: Boolean): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centerX = size / 2f
        val centerY = size / 2f
        val radius = size * 0.4f
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        // 1. Background segments
        val bgPaint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 6f
            pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
            isAntiAlias = true
        }
        canvas.drawArc(rect, 0f, 360f, false, bgPaint)

        if (isRecording) {
            // 2. Active Progress segments (Bright Green)
            val progressPaint = Paint().apply {
                color = Color.parseColor("#00FF00") // Neon Green
                style = Paint.Style.STROKE
                strokeWidth = 8f
                pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
                isAntiAlias = true
            }
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)

            // 3. Status Indicator (Bright Red Dot)
            val dotPaint = Paint().apply {
                color = Color.RED
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(centerX, centerY, 10f, dotPaint)
        }

        return bitmap
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        return RangedValueComplicationData.Builder(
            value = 60f, min = 0f, max = 100f,
            contentDescription = PlainComplicationText.Builder("Progress Preview").build()
        )
        .setText(PlainComplicationText.Builder("5").build())
        .build()
    }
}
