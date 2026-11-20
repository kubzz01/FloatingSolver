package com.floatingsolver.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

class SelectView(
    context: Context,
    private val onDone: (Rect) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var active = false

    private val dim = Paint().apply { color = Color.argb(120, 0, 0, 0) }

    private val border = Paint().apply {
        color = Color.rgb(0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val hint = Paint().apply {
        color = Color.WHITE
        textSize = 44f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private fun current(): Rect {
        val l = minOf(sx, ex).toInt()
        val t = minOf(sy, ey).toInt()
        val r = maxOf(sx, ex).toInt()
        val b = maxOf(sy, ey).toInt()
        return Rect(l, t, r, b)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!active) {
            canvas.drawRect(0f, 0f, w, h, dim)
            canvas.drawText("Tarik untuk memilih area soal", w / 2f, h / 2f, hint)
            return
        }
        val r = current()
        canvas.drawRect(0f, 0f, w, r.top.toFloat(), dim)
        canvas.drawRect(0f, r.bottom.toFloat(), w, h, dim)
        canvas.drawRect(0f, r.top.toFloat(), r.left.toFloat(), r.bottom.toFloat(), dim)
        canvas.drawRect(r.right.toFloat(), r.top.toFloat(), w, r.bottom.toFloat(), dim)
        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x
                sy = event.y
                ex = sx
                ey = sy
                active = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                ex = event.x
                ey = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                ex = event.x
                ey = event.y
                val r = current()
                active = false
                if (r.width() < 24 || r.height() < 24) {
                    onCancel()
                } else {
                    onDone(r)
                }
            }
        }
        return true
    }
}
