package com.quadruped.cat

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

    private val permissionAction = "com.quadruped.cat.USB_PERMISSION"

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
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull() ?: return
        if (!usb.hasPermission(driver.device)) {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(permissionAction).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(driver.device, pi)
            return
        }
        val conn = usb.openDevice(driver.device) ?: return
        port = driver.ports[0].also {
            it.open(conn)
            it.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            it.dtr = true  // keep the Mega from resetting repeatedly on some boards
        }
    }

    /** Sends 8 servo angles in degrees: A,a0,...,a7 (hip, knee per leg; FL, FR, RL, RR). */
    fun sendAngles(angles: FloatArray) = write(
        angles.joinToString(",", "A,", "\n") { String.format(Locale.US, "%.1f", it) }
    )

    @Synchronized
    private fun write(line: String) {
        try {
            port?.write(line.toByteArray(Charsets.US_ASCII), 100)
        } catch (e: Exception) {
            close()  // unplugged or failed; reconnect via connect()
        }
    }

    @Synchronized
    private fun close() {
        runCatching { port?.close() }
        port = null
    }
}
