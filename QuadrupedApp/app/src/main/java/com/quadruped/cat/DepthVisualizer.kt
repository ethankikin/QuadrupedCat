package com.quadruped.cat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.nio.ShortBuffer
import kotlin.math.pow

/** Renders an ARCore DEPTH16 image as a colour map: red = near, blue = far, black = unknown. */
object DepthVisualizer {
    private const val NEAR_MM = 300f
    private const val FAR_MM = 5000f
    private const val NEAR_BIAS = 0.4f   // 1 = linear, 0.5 = sqrt, smaller = more near-range detail

    fun render(
        depth: ShortBuffer, width: Int, height: Int, rowStrideShorts: Int,
        minConfidence: Float = DepthPlanner.DEFAULT_MIN_CONFIDENCE,
    ): Bitmap {
        val px = IntArray(width * height)
        val hsv = floatArrayOf(0f, 1f, 1f)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = depth.get(y * rowStrideShorts + x).toInt() and 0xFFFF
                val mm = v and 0x1FFF
                val conf = DepthPlanner.confidence(v shr 13)
                px[y * width + x] = when {
                    mm == 0 -> Color.BLACK
                    conf < minConfidence -> Color.DKGRAY
                    else -> {
                        // exponent < 1 stretches the near range: more colour change close up.
                        val t = ((mm - NEAR_MM) / (FAR_MM - NEAR_MM)).coerceIn(0f, 1f).pow(NEAR_BIAS)
                        hsv[0] = 240f * t
                        Color.HSVToColor(hsv)
                    }
                }
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, width, 0, 0, width, height)

        // Outline the band and thirds the planner uses.
        val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1f }
        val c = Canvas(bmp)
        val top = height * DepthPlanner.BAND_TOP
        val bottom = height * DepthPlanner.BAND_BOTTOM
        c.drawRect(0f, top, width - 1f, bottom, paint)
        c.drawLine(width / 3f, top, width / 3f, bottom, paint)
        c.drawLine(2f * width / 3f, top, 2f * width / 3f, bottom, paint)
        return bmp
    }
}
