package com.quadruped.cat

/**
 * Robot geometry and tuning. Lengths in mm. Legs are ordered FL, FR, RL, RR; servo order is
 * hip, knee per leg (index = leg * 2 + joint), matching QuadrupedFirmware.ino.
 *
 * The geometry values are PLACEHOLDERS - measure your robot and change them.
 */
object RobotConfig {
    // --- Leg geometry ---
    const val L1 = 80f            // hip joint to knee joint
    const val L2 = 80f            // knee joint to foot
    const val STAND_Z = 120f      // hip-to-foot vertical distance when standing (< L1 + L2)

    // --- Body geometry (hip positions relative to body centre; x forward, y left) ---
    const val BODY_LENGTH = 150f  // front-rear hip distance
    const val BODY_WIDTH = 100f   // left-right hip distance
    val LEG_X = floatArrayOf(0.5f, 0.5f, -0.5f, -0.5f).map { it * BODY_LENGTH }.toFloatArray()
    val LEG_Y = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f).map { it * BODY_WIDTH }.toFloatArray()

    // --- Servo mapping ---
    // Assumes servo = SERVO_CENTER when the upper leg points straight down and the knee is
    // fully extended. Flip a sign if that joint moves the wrong way on a given leg.
    const val SERVO_CENTER = 90f
    val HIP_SIGN = floatArrayOf(1f, -1f, 1f, -1f)
    val KNEE_SIGN = floatArrayOf(1f, -1f, 1f, -1f)

    // --- Gait (trot) ---
    const val GAIT_HZ = 1.5f
    const val STRIDE_MAX = 50f    // foot travel front-to-back at full command
    const val STEP_HEIGHT = 25f   // foot lift during swing

    // --- Balance (leveling by adjusting leg lengths) ---
    const val BALANCE_KP = 0.3f           // mm of leg length per mm of lever arm per rad of tilt
    const val BALANCE_KI = 2.0f           // integral gain, per second
    const val MAX_LEG_OFFSET = 30f        // mm, clamp on any leg's length adjustment
    const val MAX_INTEGRAL_RAD = 0.4f

    // --- Control loop ---
    const val CONTROL_HZ = 50
    const val COMMAND_TIMEOUT_MS = 500L   // planner command older than this is treated as STOP
}
