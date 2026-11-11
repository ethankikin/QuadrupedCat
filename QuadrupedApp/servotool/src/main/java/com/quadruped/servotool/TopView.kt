package com.quadruped.servotool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Top-down picture of the robot (front at the top) with the 8 servo dials where they are mounted. */
class TopView(context: Context, private val dials: Array<ServoDial?>, private val names: Array<String>) :
    ViewGroup(context) {

    private val d = resources.displayMetrics.density
    private val dialSize = (68 * d).toInt()

    // Order: FL hip, FL knee, FR hip, FR knee, RL hip, RL knee, RR hip, RR knee.
    // Fractions of the view's width/height; knees are on the outside of each leg.
    private val fx = floatArrayOf(0.34f, 0.12f, 0.66f, 0.88f, 0.34f, 0.12f, 0.66f, 0.88f)
    private val fy = floatArrayOf(0.26f, 0.26f, 0.26f, 0.26f, 0.74f, 0.74f, 0.74f, 0.74f)

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x55888888; strokeWidth = 2f * d
    }
    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14888888 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt(); textSize = 11f * d; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        for (i in 0 until 8) {
            val dial = dials[i] ?: continue
            addView(dial, LayoutParams(dialSize, dialSize))
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(dialSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dialSize, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, (270 * d).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until 8) {
            val dial = dials[i] ?: continue
            val cx = (fx[i] * w).toInt()
            val cy = (fy[i] * h).toInt()
            dial.layout(cx - dialSize / 2, cy - dialSize / 2, cx + dialSize / 2, cy + dialSize / 2)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(w * 0.23f, h * 0.08f, w * 0.77f, h * 0.92f)
        c.drawRoundRect(rect, 16 * d, 16 * d, bodyFill)
        c.drawRoundRect(rect, 16 * d, 16 * d, body)
        c.drawText("▲ FRONT", w / 2, h * 0.08f + 18 * d, text)
        for (i in 0 until 8) {
            val dial = dials[i] ?: continue
            c.drawText(names[i], dial.left + dialSize / 2f, dial.bottom + 12 * d, text)
        }
    }
}

/** Draws faint lines between each control row and its dial. Ignores touches. */
class LinkOverlay(
    context: Context,
    private val dials: Array<ServoDial?>,
    private val targets: Array<View?>,
    private val clip: View,
) : View(context) {

    private val colors = intArrayOf(0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * resources.displayMetrics.density }
    private val me = IntArray(2)
    private val loc = IntArray(2)
    private val vis = android.graphics.Rect()

    init {
        viewTreeObserver.addOnScrollChangedListener { invalidate() }
        viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(me)
        if (!clip.getGlobalVisibleRect(vis)) return
        for (i in 0 until 8) {
            val dial = dials[i] ?: continue
            val target = targets[i] ?: continue
            target.getLocationOnScreen(loc)
            val ty = loc[1] + target.height / 2
            if (ty < vis.top || ty > vis.bottom) continue       // row scrolled out of the box
            val tx = loc[0].toFloat() - me[0]
            // Use the parent's position plus the dial's layout centre: the dial itself is rotated,
            // and getLocationOnScreen would report where its rotated corner ended up.
            (dial.parent as View).getLocationOnScreen(loc)
            val dx = loc[0] + (dial.left + dial.right) / 2f - me[0]
            val dy = loc[1] + (dial.top + dial.bottom) / 2f - me[1]
            paint.color = colors[i / 2]
            paint.alpha = 90
            c.drawLine(dx, dy, tx, (ty - me[1]).toFloat(), paint)
        }
    }
}
