package com.floatingsolver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.net.URLEncoder

class CaptureService : Service() {

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null

    private var screenW = 0
    private var screenH = 0

    private var bubble: TextView? = null
    private var selector: SelectView? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        setupCapture()
        showBubble()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "floating_search"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Floating Search", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Floating Search aktif")
            .setContentText("Tekan lama tombol melayang untuk menutup")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun setupCapture() {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage()
            if (img != null) {
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
            }
        }, handler)
        reader = r

        display = projection?.createVirtualDisplay(
            "floating_capture",
            screenW,
            screenH,
            m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler
        )
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showBubble() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "G"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0, 120, 255))
            }
        }
        val p = WindowManager.LayoutParams(
            size,
            size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(240)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L
        var moved = false

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    downTime = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (SystemClock.uptimeMillis() - downTime > 800) {
                            stopSelf()
                        } else {
                            startSelect()
                        }
                    }
                }
            }
            true
        }

        bubble = tv
        wm.addView(tv, p)
    }

    private fun startSelect() {
        if (selector != null) return
        val sv = SelectView(
            this,
            onDone = { rect ->
                removeSelector()
                bubble?.visibility = View.INVISIBLE
                handler.postDelayed({ captureArea(rect) }, 350)
            },
            onCancel = { removeSelector() }
        )
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        selector = sv
        wm.addView(sv, p)
    }

    private fun removeSelector() {
        selector?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        selector = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun captureArea(rect: Rect) {
        val img = synchronized(lock) {
            val i = latest
            latest = null
            i
        }
        bubble?.visibility = View.VISIBLE
        if (img == null) {
            toast("Gagal mengambil layar. Coba lagi.")
            return
        }
        val full: Bitmap
        try {
            val plane = img.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * img.width
            full = Bitmap.createBitmap(
                img.width + rowPadding / plane.pixelStride,
                img.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
        } finally {
            img.close()
        }

        val maxW = minOf(screenW, full.width)
        val maxH = minOf(screenH, full.height)
        val l = rect.left.coerceIn(0, maxW - 1)
        val t = rect.top.coerceIn(0, maxH - 1)
        val w = rect.width().coerceIn(1, maxW - l)
        val h = rect.height().coerceIn(1, maxH - t)
        val crop = Bitmap.createBitmap(full, l, t, w, h)

        toast("Membaca teks...")
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isEmpty()) {
                    toast("Teks tidak terbaca. Pilih area yang lebih jelas.")
                } else {
                    openGoogle(text)
                }
            }
            .addOnFailureListener { e ->
                toast("OCR gagal: " + e.message)
            }
    }

    private fun openGoogle(text: String) {
        val clean = text.replace("\n", " ").replace(Regex("\\s+"), " ").trim().take(250)
        val q = URLEncoder.encode(clean, "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + q))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    override fun onDestroy() {
        removeSelector()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        display?.release()
        reader?.close()
        projection?.stop()
        recognizer.close()
        super.onDestroy()
    }
}
