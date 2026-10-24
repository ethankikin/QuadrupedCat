package com.quadruped.cat

import java.nio.ShortBuffer

/** Body velocity command, each component in [-1, 1]. */
data class Command(val vx: Float, val vy: Float, val wz: Float) {
    companion object { val STOP = Command(0f, 0f, 0f) }
}

/**
 * Reactive obstacle avoidance from an ARCore DEPTH16 image.
 * Assumes the phone is mounted in landscape facing forward, so image columns map left-to-right.
 * Splits a horizontal band into left/centre/right and steers toward the freer side.
 */
class DepthPlanner(
    private val stopDistM: Float = 0.45f,
    private val slowDistM: Float = 1.2f,
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) {
    companion object {
        const val BAND_TOP = 0.35f
        const val BAND_BOTTOM = 0.75f
        const val DEFAULT_MIN_CONFIDENCE = 0.3f

        /** DEPTH16 confidence from the top 3 bits: 0 = 100%, 1 = 0%, 2..7 = (n-1)/7. */
        fun confidence(raw3: Int): Float = when (raw3) {
            0 -> 1f
            1 -> 0f
            else -> (raw3 - 1) / 7f
        }
    }

    /** Last computed sector clearances in metres (0 = unknown); for display. */
    var lastLeft = 0f; private set
    var lastCentre = 0f; private set
    var lastRight = 0f; private set

    /** @param depth the plane buffer of a DEPTH16 image (little-endian shorts). */
    fun plan(depth: ShortBuffer, width: Int, height: Int, rowStrideShorts: Int): Command {
        val y0 = (height * BAND_TOP).toInt()
        val y1 = (height * BAND_BOTTOM).toInt()
        val third = width / 3

        val left = sectorClearance(depth, 0, third, y0, y1, rowStrideShorts)
        val centre = sectorClearance(depth, third, 2 * third, y0, y1, rowStrideShorts)
        val right = sectorClearance(depth, 2 * third, width, y0, y1, rowStrideShorts)
        lastLeft = left; lastCentre = centre; lastRight = right

        // Unknown depth (no valid pixels) is treated as blocked.
        if (centre < stopDistM) {
            // Blocked: turn in place toward the side with more room.
            val dir = if (left >= right) 1f else -1f
            return Command(0f, 0f, 0.7f * dir)
        }
        val speed = ((centre - stopDistM) / (slowDistM - stopDistM)).coerceIn(0.3f, 1f)
        val steer = ((left - right) / maxOf(left, right, 0.1f)).coerceIn(-1f, 1f)
        return Command(speed, 0f, 0.5f * steer)
    }

    /** 20th-percentile distance in metres of valid, confident pixels in the sector. */
    private fun sectorClearance(
        d: ShortBuffer, x0: Int, x1: Int, y0: Int, y1: Int, stride: Int,
    ): Float {
        val samples = ArrayList<Int>()
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val v = d.get(y * stride + x).toInt() and 0xFFFF
                val mm = v and 0x1FFF
                val conf = confidence(v shr 13)
                if (mm > 0 && conf >= minConfidence) samples.add(mm)
                x += 2
            }
            y += 2
        }
        if (samples.size < 20) return 0f
        samples.sort()
        return samples[samples.size / 5] / 1000f
    }
}
