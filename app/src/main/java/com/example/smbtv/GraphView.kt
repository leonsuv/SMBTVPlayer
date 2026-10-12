package com.example.smbtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Scrolling area chart of the last [CAPACITY] samples. New samples enter from the right;
 * samples flagged with a marker get a red tick (used for dropped frames).
 */
class GraphView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val values = FloatArray(CAPACITY)
    private val markers = BooleanArray(CAPACITY)
    private var count = 0
    private var head = 0

    /** Smallest y-axis maximum, so near-zero series don't fill the whole chart. */
    var floor = 1f

    /** Formats the y-axis maximum shown in the corner. */
    var axisLabel: (Float) -> String = { "%.0f".format(it) }

    var color: Int = Color.WHITE
        set(value) {
            field = value
            linePaint.color = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = 0x1FFFFFFF; strokeWidth = density }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCF28B82.toInt(); strokeWidth = 2f * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }
    private val line = Path()
    private val area = Path()

    fun push(value: Float, marker: Boolean = false) {
        values[head] = value.coerceAtLeast(0f)
        markers[head] = marker
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
        invalidate()
    }

    fun clear() {
        count = 0
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 0..2) {
            val y = (h * i / 2f).coerceIn(density / 2, h - density / 2)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (count < 2) return

        var max = floor
        for (i in 0 until count) max = maxOf(max, sample(i))
        max *= 1.15f

        val step = w / (CAPACITY - 1)
        val startX = w - (count - 1) * step
        line.reset()
        area.reset()
        area.moveTo(startX, h)
        for (i in 0 until count) {
            val x = startX + i * step
            val y = h - sample(i) / max * h
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            area.lineTo(x, y)
            if (markers[index(i)]) canvas.drawLine(x, 0f, x, h, markerPaint)
        }
        area.lineTo(w, h)
        area.close()

        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, withAlpha(color, 0x66), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)
        canvas.drawText(axisLabel(max), 4 * density, labelPaint.textSize + density, labelPaint)
    }

    private fun index(i: Int) = (head - count + i + CAPACITY) % CAPACITY

    private fun sample(i: Int) = values[index(i)]

    private fun withAlpha(c: Int, alpha: Int) = (c and 0x00FFFFFF) or (alpha shl 24)

    private companion object {
        /** 2 samples per second → one minute of history. */
        const val CAPACITY = 120
    }
}
