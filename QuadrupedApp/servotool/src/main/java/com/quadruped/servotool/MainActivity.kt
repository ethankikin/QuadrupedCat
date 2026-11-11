package com.quadruped.servotool

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Manual control of all 8 servos, either by joint angle (sent as "A,a0,...,a7") or by pulse
 * width (sent as "U,u0,...,u7"). While the Send switch is on, the values for the selected mode
 * are streamed to the Arduino at about 20 Hz.
 * Order (pins 2..9): FL hip, FL knee, FR hip, FR knee, RL hip, RL knee, RR hip, RR knee.
 *
 * Angle mode: 0 is each joint's start position and the slider runs to its travel (hips 135,
 * knees 270); the firmware mirrors the direction per joint. Pulse mode shows the raw pulse
 * width over the same physical travel. Keep START_DEG / END_DEG in step with the firmware.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        // Servo position (degrees on the 270 degree scale) at logical angle 0 and at the joint's max.
        val START_DEG = intArrayOf(270, 0, 0, 270, 135, 0, 135, 270)
        val END_DEG = intArrayOf(135, 270, 135, 0, 0, 270, 270, 0)
        const val SERVO_RANGE_DEG = 270.0

        fun us(servoDeg: Int) = (500 + servoDeg / SERVO_RANGE_DEG * 2000).roundToInt()

        val ANGLE_SPAN = IntArray(8) { abs(END_DEG[it] - START_DEG[it]) }
        val PULSE_MIN = IntArray(8) { minOf(us(START_DEG[it]), us(END_DEG[it])) }
        val PULSE_MAX = IntArray(8) { maxOf(us(START_DEG[it]), us(END_DEG[it])) }
    }

    private val names = arrayOf(
        "FL hip", "FL knee", "FR hip", "FR knee", "RL hip", "RL knee", "RR hip", "RR knee"
    )

    @Volatile private var angles = FloatArray(8) { (ANGLE_SPAN[it] / 2).toFloat() }
    @Volatile private var pulses = FloatArray(8) { ((PULSE_MIN[it] + PULSE_MAX[it]) / 2).toFloat() }
    @Volatile private var pulseMode = false
    private val bars = arrayOfNulls<SeekBar>(8)
    private val labels = arrayOfNulls<TextView>(8)
    private val dials = arrayOfNulls<ServoDial>(8)
    private val rows = arrayOfNulls<View>(8)

    private lateinit var link: ArduinoLink
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var centerAll: Button
    private lateinit var sendSwitch: SwitchCompat
    private val rxLog = ArrayDeque<String>()      // last lines received from the Arduino
    @Volatile private var sending = false
    @Volatile private var running = false
    private var sender: Thread? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = ArduinoLink(this)

        status = TextView(this).apply { textSize = 16f }
        debug = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE          // collapsed until the Debug button is tapped
        }
        sendSwitch = SwitchCompat(this).apply {
            text = "Send to Arduino  "
            setOnCheckedChangeListener { _, on -> sending = on }
        }
        val modeSwitch = SwitchCompat(this).apply {
            text = "Pulse width (µs)  "
            setOnCheckedChangeListener { _, on ->
                sendSwitch.isChecked = false          // never start driving servos by switching mode
                pulseMode = on
                refreshAll()
            }
        }
        centerAll = Button(this).apply {
            text = "All centre"
            setOnClickListener {
                for (i in 0 until 8) setValue(i, (minValue(i) + maxValue(i)) / 2)
            }
        }

        for (i in 0 until 8) {
            // Left side (even legs FL, RL): hips start facing down and sweep clockwise, knees start
            // facing right (towards the body) and sweep clockwise. The right side is the mirror image.
            val left = i / 2 % 2 == 0
            val hip = i % 2 == 0
            val startAngle = if (hip) 90f else if (left) 0f else 180f
            dials[i] = ServoDial(this).apply {
                configure(startAngle, clockwise = left, spanDegrees = ANGLE_SPAN[i].toFloat())
            }
        }
        val topView = TopView(this, dials, names)

        // Controls live in a bordered, scrollable box at the bottom.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            for (i in 0 until 8) addView(buildRow(i))
        }
        val box = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(1), 0x66888888)
                cornerRadius = dp(8).toFloat()
            }
            addView(controls)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(status)
            addView(Button(context).apply {
                text = "Debug ▸"
                setOnClickListener {
                    val open = debug.visibility != View.VISIBLE
                    debug.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "Debug ▾" else "Debug ▸"
                }
            })
            addView(debug)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(sendSwitch)
                addView(modeSwitch)
            })
            addView(centerAll)
            addView(topView)
            addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val overlay = LinkOverlay(this, dials, rows, box)
        setContentView(FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        refreshAll()
    }

    private fun buildRow(i: Int): LinearLayout {
        val name = TextView(this).apply { text = names[i]; width = dp(64); textSize = 13f }
        val minus = Button(this).apply {
            text = "−"; minWidth = dp(44); minimumWidth = dp(44)
            setOnClickListener { setValue(i, currentValue(i) - step()) }
        }
        val plus = Button(this).apply {
            text = "+"; minWidth = dp(44); minimumWidth = dp(44)
            setOnClickListener { setValue(i, currentValue(i) + step()) }
        }
        val bar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setValue(i, p + minValue(i))
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val value = TextView(this).apply { width = dp(72); gravity = Gravity.END }
        bars[i] = bar
        labels[i] = value
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            rows[i] = this
            addView(name)
            addView(minus)
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(plus)
            addView(value)
        }
    }

    // --- Mode- and joint-dependent helpers (the slider shows value - minValue(i)) ---

    private fun minValue(i: Int) = if (pulseMode) PULSE_MIN[i] else 0
    private fun maxValue(i: Int) = if (pulseMode) PULSE_MAX[i] else ANGLE_SPAN[i]
    private fun step() = if (pulseMode) 5 else 1
    private fun currentValue(i: Int) = (if (pulseMode) pulses[i] else angles[i]).toInt()

    private fun setValue(i: Int, raw: Int) {
        val v = raw.coerceIn(minValue(i), maxValue(i))
        if (pulseMode) pulses = pulses.copyOf().also { it[i] = v.toFloat() }
        else angles = angles.copyOf().also { it[i] = v.toFloat() }
        bars[i]?.progress = v - minValue(i)
        labels[i]?.text = if (pulseMode) "$v µs" else "$v°"
        dials[i]?.setPosition(logicalOf(i, v))
    }

    /** Logical joint angle (0 = start of travel) for a slider value in the current mode. */
    private fun logicalOf(i: Int, v: Int): Float =
        if (pulseMode) {
            val servoDeg = (v - 500) / 2000f * SERVO_RANGE_DEG.toFloat()
            (servoDeg - START_DEG[i]) * (if (END_DEG[i] >= START_DEG[i]) 1 else -1)
        } else v.toFloat()

    /** Re-ranges every slider and label for the current mode. */
    private fun refreshAll() {
        for (i in 0 until 8) {
            bars[i]?.max = maxValue(i) - minValue(i)
            setValue(i, currentValue(i))
        }
    }

    override fun onResume() {
        super.onResume()
        link.start()
        running = true
        sender = Thread(::senderLoop, "sender").also { it.start() }
    }

    override fun onPause() {
        super.onPause()
        running = false
        sender?.join(500)
        sender = null
        link.stop()
    }

    private fun senderLoop() {
        var lastConnectTry = 0L
        var lastStatus = 0L
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (!link.connected && now - lastConnectTry > 2000) {
                lastConnectTry = now
                link.connect()
            }
            if (sending && link.connected) {
                if (pulseMode) link.sendPulses(pulses) else link.sendAngles(angles)
            }
            for (line in link.readLines()) {
                rxLog.addLast(line)
                while (rxLog.size > 8) rxLog.removeFirst()
            }
            if (now - lastStatus > 250) {
                lastStatus = now
                val text = "USB: ${if (link.connected) "connected" else "not connected"}    " +
                    "mode: ${if (pulseMode) "pulse µs" else "angle"}    " +
                    "sending: ${if (sending) "ON" else "off"}"
                val dbg = "usb: ${link.note}\n" +
                    "TX lines: ${link.sentCount}\n" +
                    "last TX: ${link.lastSent}\n" +
                    "RX from Arduino:\n" +
                    (if (rxLog.isEmpty()) "  (nothing received)" else rxLog.joinToString("\n") { "  $it" })
                runOnUiThread {
                    status.text = text
                    debug.text = dbg
                }
            }
            Thread.sleep(40)
        }
    }
}
