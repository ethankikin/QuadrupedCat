package com.quadruped.servotool

import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Manual control of all 8 servos, either by angle (0-180 degrees, sent as "A,a0,...,a7") or by
 * pulse width (500-2500 microseconds, sent as "U,u0,...,u7"). While the Send switch is on, the
 * values for the selected mode are streamed to the Arduino at about 20 Hz.
 * Rows are in pin order (pins 2..9); see [names] for the joint each one is wired to.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        // 270 degree servos (500-2500 us), restricted to a centred 180 degree window.
        const val PULSE_MIN = 833
        const val PULSE_MAX = 2167
        const val PULSE_CENTER = 1500
        const val ANGLE_CENTER = 90
    }

    // Slider i drives the servo on pin 2 + i. Names reflect which joint is actually wired there.
    private val names = arrayOf(
        "RL hip", "RL knee", "FR knee", "RR hip", "FL knee", "FR hip", "RR knee", "FL hip"
    )

    @Volatile private var angles = FloatArray(8) { ANGLE_CENTER.toFloat() }
    @Volatile private var pulses = FloatArray(8) { PULSE_CENTER.toFloat() }
    @Volatile private var pulseMode = false
    private val bars = arrayOfNulls<SeekBar>(8)
    private val labels = arrayOfNulls<TextView>(8)

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
            setOnClickListener {
                for (i in 0 until 8) setValue(i, if (pulseMode) PULSE_CENTER else ANGLE_CENTER)
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(status)
            addView(debug)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(sendSwitch)
                addView(modeSwitch)
            })
            addView(centerAll)
            for (i in 0 until 8) addView(buildRow(i))
        }
        setContentView(ScrollView(this).apply { addView(column) })
        refreshAll()
    }

    private fun buildRow(i: Int): LinearLayout {
        val name = TextView(this).apply { text = names[i]; width = dp(80) }
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
                    if (fromUser) setValue(i, p + minValue())
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
            addView(name)
            addView(minus)
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(plus)
            addView(value)
        }
    }

    // --- Mode-dependent helpers (the slider shows value - minValue()) ---

    private fun minValue() = if (pulseMode) PULSE_MIN else 0
    private fun maxValue() = if (pulseMode) PULSE_MAX else 180
    private fun step() = if (pulseMode) 5 else 1
    private fun currentValue(i: Int) = (if (pulseMode) pulses[i] else angles[i]).toInt()

    private fun setValue(i: Int, raw: Int) {
        val v = raw.coerceIn(minValue(), maxValue())
        if (pulseMode) pulses = pulses.copyOf().also { it[i] = v.toFloat() }
        else angles = angles.copyOf().also { it[i] = v.toFloat() }
        bars[i]?.progress = v - minValue()
        labels[i]?.text = if (pulseMode) "$v µs" else "$v°"
    }

    /** Re-ranges every slider and label for the current mode. */
    private fun refreshAll() {
        centerAll.text = if (pulseMode) "All $PULSE_CENTER µs" else "All $ANGLE_CENTER°"
        for (i in 0 until 8) {
            bars[i]?.max = maxValue() - minValue()
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
