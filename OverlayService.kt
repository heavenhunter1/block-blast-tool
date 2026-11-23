package com.huy.blockblast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var lastFrame: Frame? = null
    private var screenW = 0
    private var screenH = 0

    private var menu: LinearLayout? = null
    private var sugView: SuggestionView? = null
    private var calView: CalibrationView? = null
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null || intent == null) return START_NOT_STICKY

        val ch = NotificationChannel("bb_overlay", "Block Blast Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        val n = Notification.Builder(this, "bb_overlay")
            .setContentTitle("Block Blast Overlay đang chạy")
            .setContentText("Mở game và chạm 💡 để xem gợi ý")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }

        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            intent.getParcelableExtra("data")
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)

        setupCapture(proj)
        showMenu()
        return START_NOT_STICKY
    }

    private fun realSize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun setupCapture(proj: MediaProjection) {
        val size = realSize()
        screenW = size.x
        screenH = size.y
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vdisplay = proj.createVirtualDisplay(
            "bb-capture", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    // ------------------------------------------------------------ thanh nổi
    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC202030.toInt())
        }
        fun item(label: String): TextView {
            val tv = TextView(this).apply {
                text = label; textSize = 22f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            box.addView(tv, LinearLayout.LayoutParams((52 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            return tv
        }
        val handle = item("☰")
        val bulb = item("💡")
        val hide = item("🙈")
        val gear = item("⚙")
        val close = item("✕")

        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (screenW - 60 * d).toInt()
        p.y = screenH / 3

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt()
                    p.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(box, p)
                }
            }
            true
        }
        bulb.setOnClickListener { requestSuggestion() }
        hide.setOnClickListener { removeSuggestion() }
        gear.setOnClickListener { startCalibration() }
        close.setOnClickListener { stopSelf() }

        menu = box
        wm.addView(box, p)
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    // ------------------------------------------------------------ chụp + gợi ý
    private fun grab(): Frame? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            return Frame(img.width, img.height, bytes, plane.rowStride, plane.pixelStride)
        } finally {
            img.close()
        }
    }

    private fun grabWithRetry(): Frame? {
        for (i in 0 until 8) {
            val f = grab()
            if (f != null) { lastFrame = f; return f }
            if (lastFrame != null && i >= 2) return lastFrame
            Thread.sleep(150)
        }
        return lastFrame
    }

    private fun requestSuggestion() {
        if (busy) return
        busy = true
        removeSuggestion()
        menu?.visibility = View.INVISIBLE // ẩn thanh nổi để không lọt vào ảnh chụp
        handler.postDelayed({
            worker.execute {
                val frame = grabWithRetry()
                if (frame == null) {
                    handler.post {
                        menu?.visibility = View.VISIBLE
                        busy = false
                        Toast.makeText(this, "Không chụp được màn hình", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }
                val prefs = Prefs(this)
                val geo = prefs.loadGeo(screenW, screenH)
                val det = Detector.detect(frame, geo, prefs.ratio)
                val res = Solver.solve(det.board, det.pieces)
                val cleared = Solver.simulate(det.board, det.pieces, res.moves)
                handler.post {
                    menu?.visibility = View.VISIBLE
                    showSuggestion(SuggestionView(this, geo, det, res, cleared))
                    busy = false
                }
            }
        }, 350)
    }

    private fun showSuggestion(v: SuggestionView) {
        removeSuggestion()
        sugView = v
        wm.addView(v, overlayParams(screenW, screenH, touchable = false).apply { gravity = Gravity.TOP or Gravity.START })
    }

    private fun removeSuggestion() {
        sugView?.let { runCatching { wm.removeView(it) } }
        sugView = null
    }

    // ------------------------------------------------------------ căn chỉnh
    private fun startCalibration() {
        removeSuggestion()
        if (calView != null) return
        menu?.visibility = View.INVISIBLE
        val prefs = Prefs(this)
        val v = CalibrationView(this, screenW, screenH, prefs.loadGeo(screenW, screenH)) { geo ->
            if (geo != null) prefs.saveGeo(geo, screenW, screenH)
            calView?.let { runCatching { wm.removeView(it) } }
            calView = null
            menu?.visibility = View.VISIBLE
        }
        calView = v
        wm.addView(v, overlayParams(screenW, screenH, touchable = true).apply { gravity = Gravity.TOP or Gravity.START })
    }

    override fun onDestroy() {
        removeSuggestion()
        calView?.let { runCatching { wm.removeView(it) } }
        menu?.let { runCatching { wm.removeView(it) } }
        vdisplay?.release()
        reader?.close()
        projection?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}
