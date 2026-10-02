package com.airplay.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Quirky minimal running dots animation view for BloomAir connection status.
 * Renders 4 smooth animated dots that pulse and slide in a rhythmic wave.
 */
class RunningDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotCount = 4
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#A8C3B0") // Sage Green accent
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        startDotAnimation()
    }

    fun setDotColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun startDotAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopDotAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startDotAnimation()
    }

    override fun onDetachedFromWindow() {
        stopDotAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerY = h / 2f
        val dotRadius = minOf(w, h) / 7f
        val totalSpacing = w - (dotCount * dotRadius * 2f)
        val gap = totalSpacing / (dotCount + 1)

        for (i in 0 until dotCount) {
            val cx = gap + dotRadius + i * (dotRadius * 2f + gap)

            // Calculate phase offset for each dot (wave effect)
            val phase = (progress + (i.toFloat() / dotCount)) % 1f
            // Sine wave scale between 0.4 and 1.2
            val scale = 0.4f + 0.8f * sin(phase * PI).toFloat().coerceAtLeast(0f)
            // Alpha pulse between 60 and 255
            val alpha = (60 + (195 * scale)).toInt().coerceIn(0, 255)

            paint.alpha = alpha
            canvas.drawCircle(cx, centerY, dotRadius * scale, paint)
        }
    }
}
