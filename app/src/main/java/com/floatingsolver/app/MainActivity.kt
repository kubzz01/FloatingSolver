package com.floatingsolver.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqProjection = 101
    private lateinit var keyInput: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "Floating Solver"
            textSize = 24f
        }

        val info = TextView(this).apply {
            text = "Pilih area layar, teks dibaca OCR, dijawab AI, hasil tampil di panel melayang. Tekan lama tombol melayang untuk menutup."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }

        keyInput = EditText(this).apply {
            hint = "Gemini API Key"
            setText(prefs.getString("key", ""))
            setSingleLine(true)
        }

        val start = Button(this).apply {
            text = "Mulai Floating Tool"
            setOnClickListener {
                prefs.edit().putString("key", keyInput.text.toString().trim()).apply()
                begin()
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(keyInput)
        root.addView(start)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun begin() {
        if (keyInput.text.toString().trim().isEmpty()) {
            Toast.makeText(this, "Isi API Key dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan tampil di atas aplikasi lain, lalu tekan Mulai lagi", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqProjection && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
