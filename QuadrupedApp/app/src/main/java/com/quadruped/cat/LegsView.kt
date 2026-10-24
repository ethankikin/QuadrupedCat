package com.quadruped.cat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wireframe side view of the four legs (FL, FR, RL, RR), drawn from the servo angles being
 * sent to the Arduino. Forward is to the right. Upper segment white, lower segment cyan.
 */
class LegsView(context: Context) : View(context) {
    /** 8 servo angles in degrees, same order as the serial protocol. */
    @Volatile var angles: FloatArray = FloatArray(8) { RobotConfig.SERVO_CENTER }

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val upper = Paint().apply {
        color = Color.WHITE; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val lower = Paint().apply {
        color = Color.CYAN; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
    }
    private val joint = Paint().apply { color = Color.YELLOW; isAntiAlias = true }
    private val ground = Paint().apply { color = Color.argb(160, 255, 255, 255); strokeWidth = 1f * density }
    private val label = Paint().apply {
        color = Color.WHITE; textSize = 11f * density; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }

    private val names = arrayOf("FL", "FR", "RL", "RR")

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val a = angles
        val panelW = width / 4f
        val scale = 0.8f * (height - 20f * density) / (RobotConfig.L1 + RobotConfig.L2)

        for (l in 0 until 4) {
            val hipTheta = Math.toRadians((RobotConfig.HIP_SIGN[l] * (a[l * 2] - RobotConfig.SERVO_CENTER)).toDouble())
            val kneeTheta = Math.toRadians((RobotConfig.KNEE_SIGN[l] * (a[l * 2 + 1] - RobotConfig.SERVO_CENTER)).toDouble())
            // Angles from straight down, forward positive; the lower link turns forward by the knee flexion.
            val a1 = hipTheta
            val a2 = hipTheta + kneeTheta

            val hx = panelW * (l + 0.5f)
            val hy = 8f * density
            val kx = hx + RobotConfig.L1 * scale * sin(a1).toFloat()
            val ky = hy + RobotConfig.L1 * scale * cos(a1).toFloat()
            val fx = kx + RobotConfig.L2 * scale * sin(a2).toFloat()
            val fy = ky + RobotConfig.L2 * scale * cos(a2).toFloat()

            val groundY = hy + RobotConfig.STAND_Z * scale
            c.drawLine(hx - panelW * 0.45f, groundY, hx + panelW * 0.45f, groundY, ground)
            c.drawLine(hx, hy, kx, ky, upper)
            c.drawLine(kx, ky, fx, fy, lower)
            for ((px, py) in listOf(hx to hy, kx to ky, fx to fy)) c.drawCircle(px, py, 3f * density, joint)
            c.drawText(names[l], hx, height - 4f * density, label)
        }
    }
}
