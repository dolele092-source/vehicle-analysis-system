package com.yonghyeok.term2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<DetectionResult> = listOf()
    private val boxPaint = Paint()
    private val textPaint = Paint()
    private val textBackgroundPaint = Paint()

    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    init { initPaints() }

    private fun initPaints() {
        boxPaint.color = Color.GREEN
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 8f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.alpha = 160
    }

    fun setResults(detectionResults: List<DetectionResult>, imageWidth: Int, imageHeight: Int) {
        this.results = detectionResults
        this.sourceWidth = if (imageWidth > 0) imageWidth else 1
        this.sourceHeight = if (imageHeight > 0) imageHeight else 1

        android.util.Log.d(
            "VIEW_DEBUG",
            "Overlay Update: ${results.size} boxes. Scaling Base: ${sourceWidth}x${sourceHeight}"
        )

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight

        for ((index, result) in results.withIndex()) {

            val rect = result.rect

            val left = rect.left * scaleX
            val top = rect.top * scaleY
            val right = rect.right * scaleX
            val bottom = rect.bottom * scaleY

            if (index == 0) {
                android.util.Log.d("DRAW_DEBUG", "Drawing Box at: $left, $top, $right, $bottom")
            }

            // 차량 bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // --------------------------
            // 🔥 텍스트 구성 변경 (차종/연식 포함)
            // --------------------------
            val displayText = "${result.label} ${(result.score * 100).toInt()}% - ${result.vehicleType}"

            val textWidth = textPaint.measureText(displayText)
            val textHeight = textPaint.textSize

            // 텍스트 배경 박스
            canvas.drawRect(
                left,
                top - textHeight - 10,
                left + textWidth + 20,
                top,
                textBackgroundPaint
            )

            // 텍스트 출력
            canvas.drawText(displayText, left + 10, top - 10, textPaint)
        }
    }

}
