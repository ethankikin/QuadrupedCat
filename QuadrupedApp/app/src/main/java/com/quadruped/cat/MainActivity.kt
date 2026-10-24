package com.quadruped.cat

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var depthView: ImageView
    private lateinit var legsView: LegsView

    private var session: Session? = null
    private var installRequested = false
    private var cameraTexture = 0
    private var viewportChanged = false
    private var viewW = 0
    private var viewH = 0

    private val planner = DepthPlanner()
    private lateinit var arduino: ArduinoLink
    private lateinit var tilt: TiltSensor
    private lateinit var controller: Controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        status = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 24, 24, 24) }
        arduino = ArduinoLink(this)
        tilt = TiltSensor(this)
        controller = Controller(arduino, tilt)
        toggle = Button(this).apply {
            text = "Enable servos"
            setOnClickListener {
                controller.enabled = !controller.enabled
                text = if (controller.enabled) "Disable servos" else "Enable servos"
            }
        }
        val calibrate = Button(this).apply {
            text = "Calibrate level"
            setOnClickListener { tilt.calibrate() }
        }
        depthView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        legsView = LegsView(this)
        val density = resources.displayMetrics.density
        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(depthView)
            addView(status)
            addView(legsView, FrameLayout.LayoutParams(
                (440 * density).toInt(), (150 * density).toInt(), Gravity.BOTTOM or Gravity.START))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(calibrate)
                addView(toggle)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END))
        })
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        if (session == null) {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                ArCoreApk.InstallStatus.INSTALLED -> {}
            }
            session = Session(this).also { s ->
                s.configure(s.config.apply {
                    depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
            }
        }
        session?.resume()
        glView.onResume()
        arduino.start()
        tilt.start()
        controller.start()
    }

    override fun onPause() {
        super.onPause()
        controller.enabled = false          // the controller leaves the servos standing
        toggle.text = "Enable servos"
        controller.stop()
        tilt.stop()
        arduino.stop()
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) finish()
    }

    // --- GLSurfaceView.Renderer (runs on the GL thread) ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width; viewH = height; viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        s.setCameraTextureName(cameraTexture)
        if (viewportChanged) {
            s.setDisplayGeometry(Surface.ROTATION_90, viewW, viewH)
            viewportChanged = false
        }
        val frame = s.update()
        val tracking = frame.camera.trackingState == TrackingState.TRACKING

        var cmd = Command.STOP
        var info = "tracking=${frame.camera.trackingState}"
        var depthBmp: Bitmap? = null
        try {
            frame.acquireDepthImage16Bits().use { img ->
                val plane = img.planes[0]
                val stride = plane.rowStride / 2
                val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                if (tracking) cmd = planner.plan(buf, img.width, img.height, stride)
                depthBmp = DepthVisualizer.render(buf, img.width, img.height, stride)
            }
            info += "\nclearance L=%.2f C=%.2f R=%.2f m".format(
                planner.lastLeft, planner.lastCentre, planner.lastRight)
        } catch (e: NotYetAvailableException) {
            info += " (no depth yet - move the phone)"
        }
        controller.setCommand(cmd)

        val p = frame.camera.pose.translation
        val text = "$info\nusb=${arduino.connected} servos=${controller.enabled}\n" +
            "cmd vx=%.2f wz=%.2f\ntilt pitch=%.1f roll=%.1f deg\npose x=%.2f y=%.2f z=%.2f".format(
                cmd.vx, cmd.wz, Math.toDegrees(tilt.pitchUp.toDouble()),
                Math.toDegrees(tilt.rollLeftUp.toDouble()), p[0], p[1], p[2])
        val bmp = depthBmp
        runOnUiThread {
            status.text = text
            if (bmp != null) depthView.setImageBitmap(bmp)
            legsView.angles = controller.lastAngles
            legsView.invalidate()
        }
    }
}
