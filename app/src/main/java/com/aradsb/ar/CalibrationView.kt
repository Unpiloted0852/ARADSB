package com.aradsb.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a looping figure-8 (lemniscate of Bernoulli) with a moving phone dot, to show the user the
 * motion that recalibrates the magnetometer. Purely visual; it self-animates while visible.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val periodMs = 2600f

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 90, 200, 250)
        strokeWidth = 3f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * density, 10f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(255, 200, 60)
    }
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(70, 255, 200, 60)
    }
    private val path = Path()

    private fun lemniscateX(t: Double, a: Double): Double = a * cos(t) / (1.0 + sin(t) * sin(t))
    private fun lemniscateY(t: Double, a: Double): Double =
        a * sin(t) * cos(t) / (1.0 + sin(t) * sin(t))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val a = (minOf(width, height) * 0.34).coerceAtLeast(40.0)

        path.reset()
        var t = 0.0
        val steps = 120
        while (t <= 2 * Math.PI + 0.01) {
            val x = cx + lemniscateX(t, a).toFloat()
            val y = cy + lemniscateY(t, a).toFloat()
            if (t == 0.0) path.moveTo(x, y) else path.lineTo(x, y)
            t += 2 * Math.PI / steps
        }
        canvas.drawPath(path, pathPaint)

        val phase = (SystemClock.uptimeMillis() % periodMs.toLong()) / periodMs.toDouble()
        val tt = phase * 2 * Math.PI
        val dx = cx + lemniscateX(tt, a).toFloat()
        val dy = cy + lemniscateY(tt, a).toFloat()
        canvas.drawCircle(dx, dy, 13f * density, dotGlow)
        canvas.drawCircle(dx, dy, 7f * density, dotPaint)

        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
