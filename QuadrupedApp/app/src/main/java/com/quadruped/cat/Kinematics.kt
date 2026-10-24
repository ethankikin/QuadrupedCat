package com.quadruped.cat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

object Kinematics {
    /**
     * Two-link inverse kinematics in a leg's vertical plane.
     * @param x foot position forward of the hip (mm)
     * @param z foot position below the hip (mm)
     * @return [hip, knee] in radians: hip = upper link angle from straight down (forward +),
     *         knee = flexion of the lower link relative to the upper (0 = straight leg).
     *         The knee bends backward. Targets outside the reachable range are clamped.
     */
    fun ik(x: Float, z: Float): FloatArray {
        val l1 = RobotConfig.L1
        val l2 = RobotConfig.L2
        val d = hypot(x, z).coerceIn(abs(l1 - l2) + 1f, l1 + l2 - 1f)
        val gamma = acos(((l1 * l1 + l2 * l2 - d * d) / (2f * l1 * l2)).coerceIn(-1f, 1f))
        val beta = acos(((l1 * l1 + d * d - l2 * l2) / (2f * l1 * d)).coerceIn(-1f, 1f))
        return floatArrayOf(atan2(x, z) - beta, PI.toFloat() - gamma)
    }

    /** Converts an IK result for [leg] into [hip, knee] servo angles in degrees (0..180). */
    fun toServo(leg: Int, ik: FloatArray): FloatArray {
        val hip = RobotConfig.SERVO_CENTER + RobotConfig.HIP_SIGN[leg] * Math.toDegrees(ik[0].toDouble()).toFloat()
        val knee = RobotConfig.SERVO_CENTER + RobotConfig.KNEE_SIGN[leg] * Math.toDegrees(ik[1].toDouble()).toFloat()
        return floatArrayOf(hip.coerceIn(0f, 180f), knee.coerceIn(0f, 180f))
    }
}
