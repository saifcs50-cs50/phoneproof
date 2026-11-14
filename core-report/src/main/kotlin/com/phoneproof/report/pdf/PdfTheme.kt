package com.phoneproof.report.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.phoneproof.report.grading.Grade

/**
 * Visual constants for the PDF.
 *
 * The first page is designed to survive being shrunk to a WhatsApp thumbnail, which is how these
 * reports actually spread in India: the grade badge, the device name and the shop name must all be
 * legible at roughly 200 px wide, so they are far larger than a conventional report would use.
 */
object PdfTheme {

    // A4 at 72 dpi, the unit PdfDocument works in.
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 40f
    const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    const val INK = 0xFF1A1A1A.toInt()
    const val INK_MUTED = 0xFF6B6B6B.toInt()
    const val INK_FAINT = 0xFF9A9A9A.toInt()
    const val RULE = 0xFFE0E0E0.toInt()
    const val PANEL = 0xFFF5F5F5.toInt()
    const val BRAND = 0xFF0B7A3B.toInt()

    const val PASS = 0xFF1B8A4B.toInt()
    const val WARN = 0xFFB8860B.toInt()
    const val FAIL = 0xFFC0392B.toInt()
    const val SKIP = 0xFF8A8A8A.toInt()

    fun gradeColor(grade: Grade): Int = when (grade) {
        Grade.A -> 0xFF1B8A4B.toInt()
        Grade.B -> 0xFF5AA02C.toInt()
        Grade.C -> 0xFFD79B00.toInt()
        Grade.D -> 0xFFE07020.toInt()
        Grade.F -> 0xFFC0392B.toInt()
    }

    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansBold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun text(size: Float, color: Int = INK, bold: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = if (bold) sansBold else sans
        }

    fun medium(size: Float, color: Int = INK): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = size
            this.color = color
            this.typeface = sansMedium
        }

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun stroke(color: Int, width: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    // Type scale
    const val TITLE_HUGE = 34f
    const val TITLE = 20f
    const val HEADING = 14f
    const val BODY = 10f
    const val SMALL = 8.5f
    const val TINY = 7.5f
}
