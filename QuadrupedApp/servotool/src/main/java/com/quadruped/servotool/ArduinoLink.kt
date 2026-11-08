package com.quadruped.servotool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.Locale

/** Sends servo angles to the Arduino over USB serial (see QuadrupedFirmware.ino). */
class ArduinoLink(private val context: Context) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null

    val connected: Boolean get() = port != null

    // --- Debug info for the UI ---
    /** Human-readable connection state / last error. */
    @Volatile var note = "starting"; private set
    @Volatile var sentCount = 0; private set
    @Volatile var lastSent = ""; private set

    private val rxBuf = ByteArray(256)
    private val rxLine = StringBuilder()

    private val permissionAction = "com.quadruped.servotool.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == permissionAction) connect()
        }
    }

    fun start() {
        val filter = IntentFilter(permissionAction)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        connect()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        close()
    }

    /** Opens the first serial device, requesting permission first if needed. */
    @Synchronized
    fun connect() {
        if (port != null) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            val all = usb.deviceList.values.joinToString { "%04X:%04X".format(it.vendorId, it.productId) }
            note = "no serial driver found (USB devices: ${all.ifEmpty { "none" }})"
            return
        }
        val dev = driver.device
        val id = "%04X:%04X".format(dev.vendorId, dev.productId)
        if (!usb.hasPermission(dev)) {
            note = "waiting for USB permission ($id)"
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(permissionAction).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
            return
        }
        val conn = usb.openDevice(dev)
        if (conn == null) {
            note = "openDevice failed ($id)"
            return
        }
        try {
            port = driver.ports[0].also {
                it.open(conn)
                it.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                it.dtr = true  // keep the Mega from resetting repeatedly on some boards
            }
            note = "open $id (${driver.javaClass.simpleName})"
        } catch (e: Exception) {
            note = "open failed: ${e.message}"
            close()
        }
    }

    /** Sends 8 servo angles in degrees: A,a0,...,a7 (hip, knee per leg; FL, FR, RL, RR). */
    fun sendAngles(angles: FloatArray) = write(
        angles.joinToString(",", "A,", "\n") { String.format(Locale.US, "%.1f", it) }
    )

    /** Sends 8 pulse widths in microseconds: U,u0,...,u7 (same servo order as [sendAngles]). */
    fun sendPulses(pulsesUs: FloatArray) = write(
        pulsesUs.joinToString(",", "U,", "\n") { it.toInt().toString() }
    )

    @Synchronized
    private fun write(line: String) {
        try {
            port?.write(line.toByteArray(Charsets.US_ASCII), 100)
            sentCount++
            lastSent = line.trim()
        } catch (e: Exception) {
            note = "write failed: ${e.message}"
            close()  // unplugged or failed; reconnect via connect()
        }
    }

    /** Returns any complete text lines the Arduino has sent since the last call. */
    @Synchronized
    fun readLines(): List<String> {
        val p = port ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val n = p.read(rxBuf, 10)
            for (i in 0 until n) {
                val c = rxBuf[i].toInt().toChar()
                if (c == '\n') {
                    if (rxLine.isNotEmpty()) out.add(rxLine.toString())
                    rxLine.setLength(0)
                } else if (c != '\r' && rxLine.length < 200) {
                    rxLine.append(c)
                }
            }
        } catch (e: Exception) {
            note = "read failed: ${e.message}"
            close()
        }
        return out
    }

    @Synchronized
    private fun close() {
        runCatching { port?.close() }
        port = null
    }
}
