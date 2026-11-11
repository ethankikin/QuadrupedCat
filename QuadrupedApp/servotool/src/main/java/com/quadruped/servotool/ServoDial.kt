package com.quadruped.servotool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Round dial for one joint. The green arc is the joint's travel, starting at [startAngle] on the
 * screen and sweeping [spanDeg] degrees clockwise or anticlockwise; the red needle shows the
 * current logical angle (0 = start of travel). Screen angles: 0 = right, 90 = down, 180 = left,
 * 270 = up.
 */
class ServoDial(context: Context) : View(context) {

    private var startAngle = 90f
    private var dir = 1f            // +1 clockwise, -1 anticlockwise
    private var span = 135f
    private var logical = 0f

    private val density = resources.displayMetrics.density
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x44888888; strokeWidth = 2f * density
    }
    private val travel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = 0xFF4CAF50.toInt(); strokeWidth = 5f * density
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = 0xFFE53935.toInt(); strokeWidth = 4f * density
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555555.toInt() }
    private val oval = RectF()

    fun configure(startScreenAngle: Float, clockwise: Boolean, spanDegrees: Float) {
        startAngle = startScreenAngle
        dir = if (clockwise) 1f else -1f
        span = spanDegrees
        invalidate()
    }

    /** Current joint angle in logical degrees (0 = start of travel). */
    fun setPosition(logicalDeg: Float) {
        logical = logicalDeg; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 6f * density
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, rim)
        c.drawArc(oval, startAngle, dir * span, false, travel)
        val a = Math.toRadians((startAngle + dir * logical).toDouble())
        c.drawLine(cx, cy, cx + (r * Math.cos(a)).toFloat(), cy + (r * Math.sin(a)).toFloat(), needle)
        c.drawCircle(cx, cy, 3f * density, hub)
    }
}
