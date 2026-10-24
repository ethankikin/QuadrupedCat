package com.quadruped.cat

import android.os.SystemClock

/**
 * Fixed-rate control loop (CONTROL_HZ) on its own thread: planner command + tilt in,
 * 8 servo angles out over USB. Keeps running independently of the camera frame rate.
 */
class Controller(private val arduino: ArduinoLink, private val tilt: TiltSensor) {
    private val gait = Gait()

    /** When false nothing is sent and the servos hold their last pose. */
    @Volatile var enabled = false

    /** Most recent angles computed (for display); starts as the standing pose. */
    @Volatile var lastAngles: FloatArray = gait.standAngles(); private set

    @Volatile private var command = Command.STOP
    @Volatile private var commandMs = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    fun setCommand(c: Command) {
        command = c
        commandMs = SystemClock.elapsedRealtime()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "controller").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val periodMs = 1000L / RobotConfig.CONTROL_HZ
        var last = SystemClock.elapsedRealtime()
        var lastConnectTry = 0L
        var integralPitch = 0f
        var integralRoll = 0f
        var wasEnabled = false
        val legOffset = FloatArray(4)

        while (running) {
            val now = SystemClock.elapsedRealtime()
            val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.1f)
            last = now

            if (!arduino.connected && now - lastConnectTry > 2000) {
                lastConnectTry = now
                arduino.connect()
            }

            if (enabled) {
                val pitch = tilt.pitchUp
                val roll = tilt.rollLeftUp
                integralPitch = (integralPitch + RobotConfig.BALANCE_KI * pitch * dt)
                    .coerceIn(-RobotConfig.MAX_INTEGRAL_RAD, RobotConfig.MAX_INTEGRAL_RAD)
                integralRoll = (integralRoll + RobotConfig.BALANCE_KI * roll * dt)
                    .coerceIn(-RobotConfig.MAX_INTEGRAL_RAD, RobotConfig.MAX_INTEGRAL_RAD)
                val pitchCmd = RobotConfig.BALANCE_KP * pitch + integralPitch
                val rollCmd = RobotConfig.BALANCE_KP * roll + integralRoll
                // Nose up -> shorten front legs / lengthen rear; left up -> shorten left legs.
                for (l in 0 until 4) {
                    legOffset[l] = (-(RobotConfig.LEG_X[l] * pitchCmd + RobotConfig.LEG_Y[l] * rollCmd))
                        .coerceIn(-RobotConfig.MAX_LEG_OFFSET, RobotConfig.MAX_LEG_OFFSET)
                }

                val stale = now - commandMs > RobotConfig.COMMAND_TIMEOUT_MS
                val angles = gait.step(dt, if (stale) Command.STOP else command, legOffset)
                lastAngles = angles
                arduino.sendAngles(angles)
                wasEnabled = true
            } else if (wasEnabled) {
                lastAngles = gait.standAngles()
                arduino.sendAngles(lastAngles)   // leave the servos in a standing pose
                wasEnabled = false
                integralPitch = 0f
                integralRoll = 0f
            }

            val sleep = periodMs - (SystemClock.elapsedRealtime() - now)
            if (sleep > 0) Thread.sleep(sleep)
        }
        if (wasEnabled) arduino.sendAngles(gait.standAngles())
    }
}
