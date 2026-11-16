package com.phoneproof.report.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * A cursor-based writer over a [PdfDocument].
 *
 * Reports vary wildly in length: a quick run is two pages, a shop intake with photos is eight, so
 * every draw call reserves vertical space first and starts a new page when it will not fit. Callers
 * never manage pages directly.
 */
class PdfPageWriter(private val document: PdfDocument) {

    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private var cursorY = 0f

    /** Drawn at the bottom of every page once it is finished. */
    var footerLeft: String = ""
    var footerRight: String = ""

    val canvas: Canvas get() = requirePage().canvas

    val y: Float get() = cursorY

    fun startDocument() {
        newPage()
    }

    fun finish() {
        closePage()
    }

    /** Ensures [height] points are available, starting a new page if not. */
    fun reserve(height: Float) {
        requirePage()
        if (cursorY + height > PdfTheme.PAGE_HEIGHT - FOOTER_SPACE) {
            newPage()
        }
    }

    fun advance(height: Float) {
        cursorY += height
    }

    fun moveTo(y: Float) {
        cursorY = y
    }

    fun spacer(height: Float) {
        reserve(height)
        advance(height)
    }

    /** Single line, ellipsised if it will not fit the available width. */
    fun line(
        text: String,
        paint: TextPaint,
        x: Float = PdfTheme.MARGIN,
        maxWidth: Float = PdfTheme.CONTENT_WIDTH,
        leading: Float = 4f,
    ) {
        val height = paint.textSize + leading
        reserve(height)
        val clipped = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clipped, x, cursorY + paint.textSize, paint)
        advance(height)
    }

    /** Multi-line paragraph that wraps within [maxWidth] and paginates cleanly. */
    fun paragraph(
        text: String,
        paint: TextPaint,
        x: Float = PdfTheme.MARGIN,
        maxWidth: Float = PdfTheme.CONTENT_WIDTH,
        leading: Float = 3f,
    ) {
        if (text.isBlank()) return
        val layout = buildLayout(text, paint, maxWidth)

        // A paragraph taller than a page cannot be reserved whole; draw it from a fresh page and
        // accept the overflow rather than looping forever.
        if (layout.height + leading > usableHeight()) {
            newPage()
        } else {
            reserve(layout.height + leading)
        }

        canvas.save()
        canvas.translate(x, cursorY)
        layout.draw(canvas)
        canvas.restore()
        advance(layout.height + leading)
    }

    fun rule(color: Int = PdfTheme.RULE, thickness: Float = 0.8f, gapAbove: Float = 6f, gapBelow: Float = 8f) {
        reserve(gapAbove + thickness + gapBelow)
        advance(gapAbove)
        canvas.drawRect(
            PdfTheme.MARGIN,
            cursorY,
            PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
            cursorY + thickness,
            PdfTheme.fill(color),
        )
        advance(thickness + gapBelow)
    }

    fun panel(height: Float, color: Int = PdfTheme.PANEL, radius: Float = 6f): RectF {
        reserve(height)
        val rect = RectF(
            PdfTheme.MARGIN,
            cursorY,
            PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
            cursorY + height,
        )
        canvas.drawRoundRect(rect, radius, radius, PdfTheme.fill(color))
        return rect
    }

    fun drawInPlace(height: Float, block: (Canvas, Float) -> Unit) {
        reserve(height)
        block(canvas, cursorY)
        advance(height)
    }

    fun buildLayout(text: String, paint: TextPaint, maxWidth: Float): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, maxWidth.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.5f, 1f)
            .setIncludePad(false)
            .build()

    fun measureParagraph(text: String, paint: TextPaint, maxWidth: Float): Float =
        buildLayout(text, paint, maxWidth).height.toFloat()

    private fun usableHeight(): Float = PdfTheme.PAGE_HEIGHT - FOOTER_SPACE - PdfTheme.MARGIN

    private fun requirePage(): PdfDocument.Page = page ?: newPage()

    private fun newPage(): PdfDocument.Page {
        closePage()
        pageNumber++
        val info = PdfDocument.PageInfo
            .Builder(PdfTheme.PAGE_WIDTH, PdfTheme.PAGE_HEIGHT, pageNumber)
            .create()
        val created = document.startPage(info)
        page = created
        cursorY = PdfTheme.MARGIN
        return created
    }

    private fun closePage() {
        val current = page ?: return
        drawFooter(current.canvas, pageNumber)
        document.finishPage(current)
        page = null
    }

    private fun drawFooter(canvas: Canvas, number: Int) {
        val paint = PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT)
        val baseline = PdfTheme.PAGE_HEIGHT - PdfTheme.MARGIN + 12f

        canvas.drawRect(
            PdfTheme.MARGIN,
            baseline - 18f,
            PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
            baseline - 17.4f,
            PdfTheme.fill(PdfTheme.RULE),
        )

        canvas.drawText(footerLeft, PdfTheme.MARGIN, baseline - 6f, paint)

        val rightPaint = PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT).apply {
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "$footerRight  ·  Page $number",
            PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
            baseline - 6f,
            rightPaint,
        )
    }

    private companion object {
        const val FOOTER_SPACE = 56f
    }
}
