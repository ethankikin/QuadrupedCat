package com.quadruped.cat

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Open-loop trot: diagonal leg pairs (FL+RR, FR+RL) are half a cycle apart. Feet move
 * back along the ground in stance and forward through the air in swing; inverse kinematics
 * turns each foot target into hip and knee servo angles.
 */
class Gait {
    private var phase = 0f                  // 0..1
    private val stride = FloatArray(4)      // smoothed per-leg stride length (mm, signed)

    /** Servo angles for standing still with every leg at STAND_Z. */
    fun standAngles(): FloatArray = angles { _ -> Pair(0f, RobotConfig.STAND_Z) }

    /**
     * @param legZOffset per-leg change in leg length (mm, + = longer) used for leveling
     * @return 8 servo angles in degrees: [hipFL, kneeFL, hipFR, kneeFR, hipRL, kneeRL, hipRR, kneeRR]
     */
    fun step(dt: Float, cmd: Command, legZOffset: FloatArray): FloatArray {
        val moving = max(abs(cmd.vx), abs(cmd.wz)) > 0.05f
        val smooth = 1f - exp(-dt / 0.15f)
        for (l in 0 until 4) {
            val side = if (l % 2 == 0) 1f else -1f              // left legs +, right legs -
            val legVx = (cmd.vx - side * cmd.wz).coerceIn(-1f, 1f)
            val target = if (moving) legVx * RobotConfig.STRIDE_MAX else 0f
            stride[l] += (target - stride[l]) * smooth
        }
        // Keep cycling until the stride has faded out so the feet settle onto the ground.
        if (moving || stride.any { abs(it) > 1f }) {
            phase = (phase + RobotConfig.GAIT_HZ * dt) % 1f
        }

        return angles { l ->
            val p = (phase + if (l == 0 || l == 3) 0f else 0.5f) % 1f
            val s = stride[l]
            val activity = min(1f, abs(s) / (RobotConfig.STRIDE_MAX * 0.3f))
            if (p < 0.5f) {                                      // stance: foot slides back
                Pair(s / 2f - s * (p / 0.5f), RobotConfig.STAND_Z + legZOffset[l])
            } else {                                             // swing: foot lifts and moves forward
                val u = (p - 0.5f) / 0.5f
                val x = -s / 2f + s * (1f - cos(PI.toFloat() * u)) / 2f
                val lift = RobotConfig.STEP_HEIGHT * sin(PI.toFloat() * u) * activity
                Pair(x, RobotConfig.STAND_Z + legZOffset[l] - lift)
            }
        }
    }

    /** Builds the 8-angle array from a per-leg (x forward, z down) foot target in mm. */
    private inline fun angles(foot: (Int) -> Pair<Float, Float>): FloatArray {
        val out = FloatArray(8)
        for (l in 0 until 4) {
            val (x, z) = foot(l)
            val servo = Kinematics.toServo(l, Kinematics.ik(x, z))
            out[l * 2] = servo[0]
            out[l * 2 + 1] = servo[1]
        }
        return out
    }
}
