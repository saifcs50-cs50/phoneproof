package com.phoneproof.report.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.phoneproof.diagnostics.model.MeasuredValue

/** Charts that turn raw series and grids into something a customer can read at a glance. */
object ChartRenderer {

    /**
     * Line chart for a time series such as thermal throughput.
     *
     * The y-axis starts at zero deliberately: a truncated axis makes a 10% dip look like a
     * collapse, and this chart is used to justify money.
     */
    fun drawSeries(
        canvas: Canvas,
        bounds: RectF,
        values: List<Double>,
        lineColor: Int,
        unit: String,
        caption: String,
    ) {
        canvas.drawRoundRect(bounds, 4f, 4f, PdfTheme.fill(PdfTheme.PANEL))
        if (values.size < 2) {
            canvas.drawText(
                "Not enough samples to chart",
                bounds.left + 10f,
                bounds.centerY(),
                PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
            )
            return
        }

        val plot = RectF(
            bounds.left + PLOT_PADDING_LEFT,
            bounds.top + PLOT_PADDING_TOP,
            bounds.right - PLOT_PADDING_RIGHT,
            bounds.bottom - PLOT_PADDING_BOTTOM,
        )

        val maxValue = values.max()
        val minValue = 0.0
        val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0

        // Gridlines at 0/50/100% of the maximum.
        val gridPaint = PdfTheme.stroke(PdfTheme.RULE, 0.6f)
        val labelPaint = PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT)
        listOf(0.0, 0.5, 1.0).forEach { fraction ->
            val y = plot.bottom - (plot.height() * fraction).toFloat()
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            val value = minValue + range * fraction
            canvas.drawText(formatCompact(value), bounds.left + 4f, y + 3f, labelPaint)
        }

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = plot.left + plot.width() * index / (values.size - 1).toFloat()
            val y = plot.bottom - (plot.height() * ((value - minValue) / range)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lineColor
                style = Paint.Style.STROKE
                strokeWidth = 1.6f
                isAntiAlias = true
            },
        )

        canvas.drawText(
            caption,
            plot.left,
            bounds.bottom - 6f,
            PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED),
        )

        if (unit.isNotBlank()) {
            canvas.drawText(
                unit,
                bounds.left + 4f,
                bounds.top + 10f,
                PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT),
            )
        }
    }

    /**
     * Touch coverage heatmap. Uncovered cells are drawn solid red rather than merely tinted, since
     * a dead zone is the single most consequential thing this grid can reveal.
     */
    fun drawTouchHeatmap(
        canvas: Canvas,
        bounds: RectF,
        grid: MeasuredValue.Grid,
    ) {
        val cellWidth = bounds.width() / grid.columns
        val cellHeight = bounds.height() / grid.rows

        val coveredPaint = PdfTheme.fill(0xFFCDE8D5.toInt())
        val strongPaint = PdfTheme.fill(0xFF6FBF8B.toInt())
        val deadPaint = PdfTheme.fill(0xFFE8B4AE.toInt())
        val gridPaint = PdfTheme.stroke(0xFFFFFFFF.toInt(), 0.5f)

        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val touches = grid.cells[row * grid.columns + column]
                val cell = RectF(
                    bounds.left + column * cellWidth,
                    bounds.top + row * cellHeight,
                    bounds.left + (column + 1) * cellWidth,
                    bounds.top + (row + 1) * cellHeight,
                )
                val paint = when {
                    touches <= 0 -> deadPaint
                    touches >= STRONG_TOUCH_THRESHOLD -> strongPaint
                    else -> coveredPaint
                }
                canvas.drawRect(cell, paint)
                canvas.drawRect(cell, gridPaint)
            }
        }

        canvas.drawRect(bounds, PdfTheme.stroke(PdfTheme.RULE, 1f))
    }

    /** Horizontal confidence/coverage bar. */
    fun drawProgressBar(
        canvas: Canvas,
        bounds: RectF,
        fraction: Double,
        fillColor: Int,
        trackColor: Int = PdfTheme.RULE,
    ) {
        val radius = bounds.height() / 2f
        canvas.drawRoundRect(bounds, radius, radius, PdfTheme.fill(trackColor))
        val filledWidth = (bounds.width() * fraction.coerceIn(0.0, 1.0)).toFloat()
        if (filledWidth <= 0f) return
        canvas.drawRoundRect(
            RectF(bounds.left, bounds.top, bounds.left + filledWidth, bounds.bottom),
            radius,
            radius,
            PdfTheme.fill(fillColor),
        )
    }

    private fun formatCompact(value: Double): String = when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000)
        value >= 1_000 -> "%.0fk".format(value / 1_000)
        value >= 10 -> "%.0f".format(value)
        else -> "%.1f".format(value)
    }

    private const val PLOT_PADDING_LEFT = 30f
    private const val PLOT_PADDING_RIGHT = 10f
    private const val PLOT_PADDING_TOP = 14f
    private const val PLOT_PADDING_BOTTOM = 18f
    private const val STRONG_TOUCH_THRESHOLD = 3
}
