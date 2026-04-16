package com.example.dronecontroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class MapRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val minePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#000000")
        style = Paint.Style.FILL
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val pointSet = linkedSetOf<Pair<Int, Int>>()
    private var maxX = 0
    private var maxY = 0

    @Synchronized
    fun addPoint(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        pointSet.add(x to y)
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        postInvalidate()
    }

    @Synchronized
    fun clearAll() {
        pointSet.clear()
        maxX = 0
        maxY = 0
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val points: List<Pair<Int, Int>>
        val localMaxX: Int
        val localMaxY: Int
        synchronized(this) {
            points = pointSet.toList()
            localMaxX = maxX
            localMaxY = maxY
        }

        val cols = max(localMaxX + 1, 2)
        val rows = max(localMaxY + 1, 2)

        val cellSize = minOf(
            width.toFloat() / cols.toFloat(),
            height.toFloat() / rows.toFloat()
        )

        val mapWidth = cols * cellSize
        val mapHeight = rows * cellSize
        val offsetX = 0f
        val offsetY = height - mapHeight

        for (c in 0..cols) {
            val x = offsetX + c * cellSize
            canvas.drawLine(x, offsetY, x, offsetY + mapHeight, gridPaint)
        }
        for (r in 0..rows) {
            val y = offsetY + r * cellSize
            canvas.drawLine(offsetX, y, offsetX + mapWidth, y, gridPaint)
        }

        canvas.drawLine(offsetX, offsetY + mapHeight, offsetX + mapWidth, offsetY + mapHeight, axisPaint)
        canvas.drawLine(offsetX, offsetY, offsetX, offsetY + mapHeight, axisPaint)

        for ((px, py) in points) {
            val left = offsetX + px * cellSize
            val top = offsetY + (rows - 1 - py) * cellSize
            val right = left + cellSize
            val bottom = top + cellSize
            canvas.drawRect(left, top, right, bottom, minePaint)
        }
    }
}
