package com.airplay.tv.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance animated fluid mesh gradient background for TV.
 * Continuously shifts between Electric Violet, Vibrant Cobalt Blue, and Midnight Navy.
 */
class MovingGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint2 = Paint(Paint.ANTI_ALIAS_FLAG)

    private var animationProgress = 0f
    private var animator: ValueAnimator? = null

    // Palette: Violet to Blue
    private val colorBgDark = Color.parseColor("#090614")
    private val colorBgMid = Color.parseColor("#110C24")
    private val colorViolet = Color.parseColor("#807C3AED") // 50% opacity
    private val colorBlue = Color.parseColor("#802563EB")   // 50% opacity
    private val colorCyan = Color.parseColor("#4D06B6D4")   // 30% opacity
    private val colorTransparent = Color.TRANSPARENT

    init {
        startAnimation()
    }

    private fun startAnimation() {
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 14000L // 14s smooth loop
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Deep Midnight Base Linear Gradient
        basePaint.shader = LinearGradient(
            0f, 0f, w, h,
            colorBgDark, colorBgMid,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // 2. Animated Violet Orbital Radial Gradient
        val orb1X = w * 0.35f + w * 0.20f * cos(animationProgress)
        val orb1Y = h * 0.40f + h * 0.25f * sin(animationProgress)
        val radius1 = (w.coerceAtLeast(h)) * 0.65f

        orbPaint1.shader = RadialGradient(
            orb1X, orb1Y, radius1,
            intArrayOf(colorViolet, colorCyan, colorTransparent),
            floatArrayOf(0.0f, 0.45f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, orbPaint1)

        // 3. Animated Cobalt Blue Orbital Radial Gradient (opposing phase)
        val orb2X = w * 0.70f + w * 0.22f * sin(animationProgress + 1.2f)
        val orb2Y = h * 0.65f + h * 0.20f * cos(animationProgress + 1.2f)
        val radius2 = (w.coerceAtLeast(h)) * 0.70f

        orbPaint2.shader = RadialGradient(
            orb2X, orb2Y, radius2,
            intArrayOf(colorBlue, colorTransparent),
            floatArrayOf(0.0f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, orbPaint2)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
