package com.floatingsolver.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var selector: SelectView? = null
    private var panel: View? = null
    private var panelText: TextView? = null
    private var lastQuery = ""

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
        val channelId = "floating_solver"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Floating Solver", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Floating Solver aktif")
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
            text = "AI"
            textSize = 18f
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
        bubbleParams = p
        wm.addView(tv, p)
    }

    private fun startSelect() {
        removePanel()
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

    private fun captureArea(rect: Rect) {
        val img = synchronized(lock) {
            val i = latest
            latest = null
            i
        }
        bubble?.visibility = View.VISIBLE
        if (img == null) {
            showPanel("Gagal mengambil layar. Coba pilih area lagi.")
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

        showPanel("Membaca teks dari area...")
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isEmpty()) {
                    updatePanel("Teks tidak terbaca. Pilih area yang lebih jelas.")
                } else {
                    lastQuery = text
                    updatePanel("Soal:\n" + text + "\n\nMenanyakan ke AI...")
                    askAi(text)
                }
            }
            .addOnFailureListener { e ->
                updatePanel("OCR gagal: " + e.message)
            }
    }

    private fun askAi(question: String) {
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isEmpty()) {
            updatePanel("API Key kosong. Buka aplikasi dan isi dulu.")
            return
        }
        Thread {
            try {
                val prompt = "Jawab soal berikut dengan jelas dan singkat dalam bahasa Indonesia. " +
                    "Tulis jawaban akhir di baris terakhir. Soal:\n" + question
                val url = URL(
                    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + key
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 60000

                val body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream.bufferedReader().use { it.readText() }
                if (code !in 200..299) {
                    handler.post { updatePanel("Error AI (" + code + "):\n" + resp.take(300)) }
                    return@Thread
                }
                val answer = JSONObject(resp)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                handler.post {
                    updatePanel("Soal:\n" + question + "\n\nJawaban AI:\n" + answer)
                }
            } catch (e: Exception) {
                handler.post { updatePanel("Gagal menghubungi AI: " + e.message) }
            }
        }.start()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun showPanel(message: String) {
        removePanel()

        val tv = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        panelText = tv

        val scroll = ScrollView(this).apply {
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenH * 0.4f).toInt()
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(makeButton("Cari Google") { openGoogle() })
            addView(makeButton("Salin") { copyText() })
            addView(makeButton("Tutup") { removePanel() })
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(240, 25, 30, 45))
            }
            addView(scroll)
            addView(row)
        }

        val p = WindowManager.LayoutParams(
            screenW - dp(24),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(24)
        }

        panel = box
        wm.addView(box, p)
    }

    private fun updatePanel(message: String) {
        panelText?.text = message
    }

    private fun removePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        panel = null
        panelText = null
    }

    private fun openGoogle() {
        if (lastQuery.isEmpty()) return
        val q = URLEncoder.encode(lastQuery.replace("\n", " ").take(250), "UTF-8")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + q))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun copyText() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jawaban", panelText?.text ?: ""))
    }

    override fun onDestroy() {
        removeSelector()
        removePanel()
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
