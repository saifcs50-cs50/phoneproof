package com.phoneproof.report.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.phoneproof.diagnostics.model.Artifact
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestRecord
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.tests.auto.IdentityTest
import com.phoneproof.report.grading.Grade
import com.phoneproof.report.model.PhoneProofReport
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a [PhoneProofReport] to a branded PDF.
 *
 * Page one is built to work as a WhatsApp thumbnail — grade badge, device name and shop name are
 * oversized on purpose, because that preview is what makes someone open the file. Everything after
 * it is the evidence: raw measured values, per-test findings, charts and photos.
 */
class ReportPdfRenderer(private val context: Context) {

    fun render(report: PhoneProofReport, outputFile: File): File {
        val document = PdfDocument()
        val writer = PdfPageWriter(document)

        writer.footerLeft = footerLeft(report)
        writer.footerRight = report.attestation.verificationCode

        writer.startDocument()

        drawSummaryPage(writer, report)
        drawFindingsSection(writer, report)
        drawDetailSections(writer, report)
        drawCharts(writer, report)
        drawPhotos(writer, report)
        drawVerificationSection(writer, report)

        writer.finish()

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use(document::writeTo)
        document.close()
        return outputFile
    }

    // ------------------------------------------------------------------ page 1

    private fun drawSummaryPage(writer: PdfPageWriter, report: PhoneProofReport) {
        drawBrandHeader(writer, report)

        val deviceName = deviceTitle(report)
        writer.spacer(6f)
        writer.line(deviceName, PdfTheme.text(PdfTheme.TITLE, PdfTheme.INK, bold = true))
        writer.line(deviceSubtitle(report), PdfTheme.text(PdfTheme.BODY, PdfTheme.INK_MUTED))

        writer.spacer(10f)
        drawGradeBlock(writer, report)

        writer.spacer(12f)
        drawHeadlineFacts(writer, report)

        report.offer?.let { offer ->
            writer.spacer(10f)
            drawOfferBlock(writer, offer.formattedOffer, offer.model, offer.note)
        }

        if (!report.customer.isEmpty) {
            writer.spacer(8f)
            writer.line(
                "Customer: ${report.customer.name}  ${report.customer.phone}".trim(),
                PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
            )
        }
    }

    private fun drawBrandHeader(writer: PdfPageWriter, report: PhoneProofReport) {
        val branding = report.branding
        val headerHeight = 46f

        writer.drawInPlace(headerHeight) { canvas, top ->
            var textLeft = PdfTheme.MARGIN

            branding.logoPath
                ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                ?.let { logo ->
                    val target = RectF(PdfTheme.MARGIN, top, PdfTheme.MARGIN + headerHeight, top + headerHeight)
                    canvas.drawBitmap(logo, Rect(0, 0, logo.width, logo.height), target, null)
                    textLeft = target.right + 10f
                }

            val title = if (branding.isConfigured) branding.shopName else "PhoneProof"
            canvas.drawText(
                title,
                textLeft,
                top + 16f,
                PdfTheme.medium(PdfTheme.HEADING, PdfTheme.INK),
            )

            val subtitle = if (branding.isConfigured) {
                listOf(branding.addressLine, branding.phone).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                "Independent device diagnostic"
            }
            canvas.drawText(
                subtitle,
                textLeft,
                top + 29f,
                PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
            )

            val stamp = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
                .format(Date(report.attestation.generatedAtEpochMs))
            canvas.drawText(
                stamp,
                PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
                top + 16f,
                PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED).apply { textAlign = Paint.Align.RIGHT },
            )
        }

        writer.rule(gapAbove = 2f, gapBelow = 6f)
    }

    /** The badge that has to read at thumbnail size. */
    private fun drawGradeBlock(writer: PdfPageWriter, report: PhoneProofReport) {
        val grade = report.grade
        val blockHeight = 96f
        val badgeSize = 84f

        writer.drawInPlace(blockHeight) { canvas, top ->
            val badge = RectF(PdfTheme.MARGIN, top, PdfTheme.MARGIN + badgeSize, top + badgeSize)
            val color = PdfTheme.gradeColor(grade.grade)
            canvas.drawRoundRect(badge, 12f, 12f, PdfTheme.fill(color))

            canvas.drawText(
                grade.grade.letter,
                badge.centerX(),
                badge.centerY() + 20f,
                PdfTheme.text(56f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    textAlign = Paint.Align.CENTER
                },
            )

            val textLeft = badge.right + 16f
            canvas.drawText(
                grade.grade.label,
                textLeft,
                top + 22f,
                PdfTheme.medium(PdfTheme.TITLE, color),
            )
            canvas.drawText(
                "Score ${grade.scoreRounded}/100",
                textLeft,
                top + 40f,
                PdfTheme.text(PdfTheme.BODY, PdfTheme.INK_MUTED),
            )

            // Coverage bar: the honest counterweight to the letter grade.
            canvas.drawText(
                "Checks completed",
                textLeft,
                top + 60f,
                PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
            )
            val barTop = top + 66f
            ChartRenderer.drawProgressBar(
                canvas,
                RectF(textLeft, barTop, PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN - 46f, barTop + 8f),
                grade.confidence,
                if (grade.confidence >= 0.75) PdfTheme.PASS else PdfTheme.WARN,
            )
            canvas.drawText(
                "${grade.confidencePercent}%",
                PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN,
                barTop + 8f,
                PdfTheme.medium(PdfTheme.BODY, PdfTheme.INK).apply { textAlign = Paint.Align.RIGHT },
            )
        }

        grade.cappedBy?.let { reason ->
            writer.spacer(4f)
            writer.paragraph(reason, PdfTheme.text(PdfTheme.SMALL, PdfTheme.FAIL))
        }
    }

    private fun drawHeadlineFacts(writer: PdfPageWriter, report: PhoneProofReport) {
        val facts = buildHeadlineFacts(report)
        if (facts.isEmpty()) return

        val rowHeight = 22f
        val panelHeight = rowHeight * facts.size + 16f
        val panel = writer.panel(panelHeight)

        facts.forEachIndexed { index, fact ->
            val baseline = panel.top + 22f + index * rowHeight
            writer.canvas.drawText(
                fact.label,
                panel.left + 12f,
                baseline,
                PdfTheme.text(PdfTheme.BODY, PdfTheme.INK_MUTED),
            )
            writer.canvas.drawText(
                fact.value,
                panel.right - 12f,
                baseline,
                PdfTheme.medium(PdfTheme.BODY, fact.color).apply { textAlign = Paint.Align.RIGHT },
            )
        }
        writer.advance(panelHeight)
    }

    private fun buildHeadlineFacts(report: PhoneProofReport): List<Fact> = buildList {
        val run = report.run

        run.record("integrity.bootloader_root")?.let { record ->
            add(
                Fact(
                    "Bootloader & root",
                    record.result.summary,
                    verdictColor(record.result),
                ),
            )
        }

        run.record("battery.health")?.let { record ->
            add(Fact("Battery health", record.result.summary, verdictColor(record.result)))
        }

        run.record("integrity.counterfeit")?.let { record ->
            add(Fact("Spec verification", record.result.summary, verdictColor(record.result)))
        }

        add(
            Fact(
                "Tests executed",
                "${run.executedCount} of ${run.records.size}" +
                    if (run.skippedCount > 0) "  (${run.skippedCount} skipped)" else "",
                if (run.completeness >= 0.8) PdfTheme.INK else PdfTheme.WARN,
            ),
        )

        if (run.failedCount > 0) {
            add(Fact("Failures", "${run.failedCount} test(s) failed", PdfTheme.FAIL))
        }
    }

    private fun drawOfferBlock(writer: PdfPageWriter, price: String, model: String, note: String) {
        val height = 62f
        val panel = writer.panel(height, color = 0xFFEAF5EE.toInt())
        writer.canvas.drawText(
            "Offer for this device",
            panel.left + 14f,
            panel.top + 20f,
            PdfTheme.text(PdfTheme.BODY, PdfTheme.INK_MUTED),
        )
        writer.canvas.drawText(
            price,
            panel.left + 14f,
            panel.top + 48f,
            PdfTheme.text(28f, PdfTheme.BRAND, bold = true),
        )
        writer.canvas.drawText(
            model,
            panel.right - 14f,
            panel.top + 20f,
            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED).apply { textAlign = Paint.Align.RIGHT },
        )
        if (note.isNotBlank()) {
            writer.canvas.drawText(
                note,
                panel.right - 14f,
                panel.top + 46f,
                PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED).apply { textAlign = Paint.Align.RIGHT },
            )
        }
        writer.advance(height)
    }

    // ------------------------------------------------------------------ findings

    private fun drawFindingsSection(writer: PdfPageWriter, report: PhoneProofReport) {
        val deductions = report.grade.topDeductions(limit = 8)
        if (deductions.isEmpty()) return

        writer.spacer(14f)
        sectionHeading(writer, "What lowered the grade")

        deductions.forEach { deduction ->
            writer.spacer(4f)
            writer.line(
                "−${Math.round(deduction.points)} points · ${deduction.category.displayName}",
                PdfTheme.medium(PdfTheme.SMALL, PdfTheme.FAIL),
            )
            writer.paragraph(deduction.reason, PdfTheme.text(PdfTheme.BODY))
            deduction.evidence?.let { evidence ->
                writer.line(
                    "Measured: $evidence",
                    PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
                    x = PdfTheme.MARGIN + 8f,
                )
            }
        }
    }

    // ------------------------------------------------------------------ detail

    private fun drawDetailSections(writer: PdfPageWriter, report: PhoneProofReport) {
        Category.displayOrder.forEach { category ->
            val records = report.run.byCategory(category)
            if (records.isEmpty()) return@forEach

            writer.spacer(14f)
            sectionHeading(writer, category.displayName)

            records.forEach { record -> drawTestRecord(writer, record) }
        }
    }

    private fun drawTestRecord(writer: PdfPageWriter, record: TestRecord) {
        writer.spacer(6f)

        val verdict = verdictLabel(record.result)
        val color = verdictColor(record.result)

        writer.drawInPlace(16f) { canvas, top ->
            val chipWidth = 46f
            val chip = RectF(PdfTheme.MARGIN, top, PdfTheme.MARGIN + chipWidth, top + 13f)
            canvas.drawRoundRect(chip, 3f, 3f, PdfTheme.fill(color))
            canvas.drawText(
                verdict,
                chip.centerX(),
                chip.bottom - 3.5f,
                PdfTheme.medium(PdfTheme.TINY, 0xFFFFFFFF.toInt()).apply { textAlign = Paint.Align.CENTER },
            )
            canvas.drawText(
                record.title,
                chip.right + 8f,
                top + 10f,
                PdfTheme.medium(PdfTheme.BODY, PdfTheme.INK),
            )
        }

        writer.paragraph(
            record.result.summary,
            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
            x = PdfTheme.MARGIN + 54f,
            maxWidth = PdfTheme.CONTENT_WIDTH - 54f,
        )

        record.measurements
            .filter { it.value !is MeasuredValue.Series && it.value !is MeasuredValue.Grid }
            .forEach { measurement -> drawMeasurement(writer, measurement) }

        record.findings
            .filter { it.severity != Severity.INFO || record.result is TestResult.Pass }
            .forEach { finding ->
                writer.paragraph(
                    "${severityMarker(finding.severity)} ${finding.message}",
                    PdfTheme.text(PdfTheme.SMALL, severityColor(finding.severity)),
                    x = PdfTheme.MARGIN + 54f,
                    maxWidth = PdfTheme.CONTENT_WIDTH - 54f,
                )
            }
    }

    private fun drawMeasurement(writer: PdfPageWriter, measurement: Measurement) {
        val labelX = PdfTheme.MARGIN + 54f
        val valueX = PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN

        when (val value = measurement.value) {
            is MeasuredValue.Table -> {
                writer.line(
                    measurement.label,
                    PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
                    x = labelX,
                )
                value.rows.take(MAX_TABLE_ROWS).forEach { row ->
                    writer.drawInPlace(12f) { canvas, top ->
                        canvas.drawText(
                            "· ${row.label}",
                            labelX + 8f,
                            top + 9f,
                            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK),
                        )
                        canvas.drawText(
                            row.value,
                            valueX,
                            top + 9f,
                            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK).apply {
                                textAlign = Paint.Align.RIGHT
                            },
                        )
                    }
                }
                if (value.rows.size > MAX_TABLE_ROWS) {
                    writer.line(
                        "… and ${value.rows.size - MAX_TABLE_ROWS} more",
                        PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT),
                        x = labelX + 8f,
                    )
                }
            }

            else -> {
                writer.drawInPlace(13f) { canvas, top ->
                    canvas.drawText(
                        measurement.label,
                        labelX,
                        top + 9f,
                        PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
                    )
                    canvas.drawText(
                        measurement.display,
                        valueX,
                        top + 9f,
                        PdfTheme.medium(PdfTheme.SMALL, PdfTheme.INK).apply {
                            textAlign = Paint.Align.RIGHT
                        },
                    )
                }
                measurement.note?.let { note ->
                    writer.paragraph(
                        note,
                        PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT),
                        x = labelX + 8f,
                        maxWidth = PdfTheme.CONTENT_WIDTH - 62f,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ charts & photos

    private fun drawCharts(writer: PdfPageWriter, report: PhoneProofReport) {
        val seriesMeasurements = report.run.records.flatMap { record ->
            record.measurements.mapNotNull { measurement ->
                (measurement.value as? MeasuredValue.Series)
                    ?.takeIf { it.values.size >= 2 }
                    ?.let { record to measurement }
            }
        }

        val grids = report.run.records.flatMap { record ->
            record.measurements.mapNotNull { measurement ->
                (measurement.value as? MeasuredValue.Grid)?.let { record to measurement }
            }
        }

        if (seriesMeasurements.isEmpty() && grids.isEmpty()) return

        writer.spacer(16f)
        sectionHeading(writer, "Charts")

        seriesMeasurements.forEach { (record, measurement) ->
            val series = measurement.value as MeasuredValue.Series
            writer.spacer(6f)
            writer.line(
                "${record.title} — ${measurement.label}",
                PdfTheme.medium(PdfTheme.SMALL, PdfTheme.INK),
            )
            writer.drawInPlace(CHART_HEIGHT) { canvas, top ->
                ChartRenderer.drawSeries(
                    canvas,
                    RectF(PdfTheme.MARGIN, top, PdfTheme.PAGE_WIDTH - PdfTheme.MARGIN, top + CHART_HEIGHT),
                    series.values,
                    PdfTheme.BRAND,
                    measurement.unit.orEmpty(),
                    "${series.values.size} samples over time",
                )
            }
        }

        grids.forEach { (record, measurement) ->
            val grid = measurement.value as MeasuredValue.Grid
            writer.spacer(8f)
            writer.line(
                "${record.title} — ${measurement.label}",
                PdfTheme.medium(PdfTheme.SMALL, PdfTheme.INK),
            )
            writer.line(
                grid.display,
                PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED),
            )

            // Keep the phone's aspect ratio so a dead strip appears where it really is.
            val heatmapHeight = HEATMAP_HEIGHT
            val heatmapWidth = heatmapHeight * grid.columns / grid.rows.toFloat()
            writer.drawInPlace(heatmapHeight + 8f) { canvas, top ->
                ChartRenderer.drawTouchHeatmap(
                    canvas,
                    RectF(PdfTheme.MARGIN, top, PdfTheme.MARGIN + heatmapWidth, top + heatmapHeight),
                    grid,
                )
                canvas.drawText(
                    "Red cells were never touched",
                    PdfTheme.MARGIN + heatmapWidth + 12f,
                    top + 14f,
                    PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED),
                )
            }
        }
    }

    private fun drawPhotos(writer: PdfPageWriter, report: PhoneProofReport) {
        val photos = report.run.artifacts.filter { File(it.absolutePath).exists() }
        if (photos.isEmpty()) return

        writer.spacer(16f)
        sectionHeading(writer, "Condition photos")

        photos.chunked(PHOTOS_PER_ROW).forEach { row ->
            writer.spacer(6f)
            val cellWidth = (PdfTheme.CONTENT_WIDTH - PHOTO_GAP * (PHOTOS_PER_ROW - 1)) / PHOTOS_PER_ROW
            val cellHeight = cellWidth * 4 / 3f

            writer.drawInPlace(cellHeight + 14f) { canvas, top ->
                row.forEachIndexed { index, artifact ->
                    val left = PdfTheme.MARGIN + index * (cellWidth + PHOTO_GAP)
                    drawPhoto(canvas, artifact, RectF(left, top, left + cellWidth, top + cellHeight))
                    canvas.drawText(
                        artifact.label,
                        left,
                        top + cellHeight + 10f,
                        PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED),
                    )
                }
            }
        }
    }

    /**
     * Photos are downsampled while decoding — a run with eight full-resolution captures would
     * otherwise produce a PDF too large to send over WhatsApp, which defeats the point.
     */
    private fun drawPhoto(canvas: Canvas, artifact: Artifact, target: RectF) {
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(artifact.absolutePath, bounds)
            val sample = maxOf(1, bounds.outWidth / PHOTO_TARGET_PX)
            BitmapFactory.decodeFile(
                artifact.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()

        if (bitmap == null) {
            canvas.drawRect(target, PdfTheme.fill(PdfTheme.PANEL))
            canvas.drawText(
                "image unavailable",
                target.left + 6f,
                target.centerY(),
                PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT),
            )
            return
        }

        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), target, null)
        canvas.drawRect(target, PdfTheme.stroke(PdfTheme.RULE, 0.8f))
    }

    // ------------------------------------------------------------------ verification

    private fun drawVerificationSection(writer: PdfPageWriter, report: PhoneProofReport) {
        writer.spacer(18f)
        sectionHeading(writer, "Authenticity")

        val attestation = report.attestation
        val height = 74f
        val panel = writer.panel(height)

        writer.canvas.drawText(
            "Verification code",
            panel.left + 14f,
            panel.top + 18f,
            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
        )
        writer.canvas.drawText(
            attestation.verificationCode,
            panel.left + 14f,
            panel.top + 42f,
            PdfTheme.text(20f, PdfTheme.INK, bold = true),
        )
        writer.canvas.drawText(
            "Device fingerprint ${attestation.deviceFingerprint.take(16)}…",
            panel.left + 14f,
            panel.top + 60f,
            PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_MUTED),
        )
        attestation.verifyUrl?.let { url ->
            writer.canvas.drawText(
                url,
                panel.right - 14f,
                panel.top + 60f,
                PdfTheme.text(PdfTheme.TINY, PdfTheme.BRAND).apply { textAlign = Paint.Align.RIGHT },
            )
        }
        writer.advance(height)

        writer.spacer(6f)
        writer.paragraph(
            "This report is cryptographically signed and bound to the specific handset it was run " +
                "on. Open PhoneProof, choose Verify a report, and enter the code above while " +
                "holding the phone — the app will confirm both that the report is unmodified and " +
                "that it belongs to that exact device. A report that fails either check should not " +
                "be trusted.",
            PdfTheme.text(PdfTheme.SMALL, PdfTheme.INK_MUTED),
        )

        writer.spacer(6f)
        writer.paragraph(
            "PhoneProof is an independent diagnostic tool. It is not affiliated with the device " +
                "manufacturer and does not buy or sell handsets. Results describe the phone at the " +
                "time of testing only.",
            PdfTheme.text(PdfTheme.TINY, PdfTheme.INK_FAINT),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun sectionHeading(writer: PdfPageWriter, title: String) {
        writer.reserve(26f)
        writer.line(title.uppercase(), PdfTheme.medium(PdfTheme.SMALL, PdfTheme.BRAND))
        writer.rule(gapAbove = 1f, gapBelow = 4f)
    }

    private fun deviceTitle(report: PhoneProofReport): String {
        val identity = report.run.record(IdentityTest.ID)
        val manufacturer = identity?.text(IdentityTest.KEY_MANUFACTURER)?.replaceFirstChar { it.uppercase() }
        val model = identity?.text(IdentityTest.KEY_MODEL)
        return listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { "Unidentified device" }
    }

    private fun deviceSubtitle(report: PhoneProofReport): String {
        val identity = report.run.record(IdentityTest.ID) ?: return ""
        return listOfNotNull(
            identity.text(IdentityTest.KEY_SOC),
            identity.numeric(IdentityTest.KEY_RAM_GB)?.let { "%.0f GB RAM".format(it) },
            identity.numeric(IdentityTest.KEY_STORAGE_TOTAL_GB)?.let { "%.0f GB storage".format(it) },
            identity.text(IdentityTest.KEY_ANDROID_VERSION)?.let { "Android $it" },
        ).joinToString("  ·  ")
    }

    private fun footerLeft(report: PhoneProofReport): String =
        if (report.branding.isConfigured) {
            listOf(report.branding.shopName, report.branding.technicianName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        } else {
            "PhoneProof diagnostic report"
        }

    private fun verdictLabel(result: TestResult): String = when (result) {
        is TestResult.Pass -> "PASS"
        is TestResult.Warn -> "WARN"
        is TestResult.Fail -> "FAIL"
        is TestResult.Skipped -> "SKIPPED"
        is TestResult.Unsupported -> "N/A"
    }

    private fun verdictColor(result: TestResult): Int = when (result) {
        is TestResult.Pass -> PdfTheme.PASS
        is TestResult.Warn -> PdfTheme.WARN
        is TestResult.Fail -> PdfTheme.FAIL
        is TestResult.Skipped -> PdfTheme.SKIP
        is TestResult.Unsupported -> PdfTheme.SKIP
    }

    private fun severityColor(severity: Severity): Int = when (severity) {
        Severity.CRITICAL, Severity.MAJOR -> PdfTheme.FAIL
        Severity.MINOR -> PdfTheme.WARN
        Severity.INFO -> PdfTheme.INK_MUTED
    }

    private fun severityMarker(severity: Severity): String = when (severity) {
        Severity.CRITICAL -> "CRITICAL:"
        Severity.MAJOR -> "Important:"
        Severity.MINOR -> "Note:"
        Severity.INFO -> "FYI:"
    }

    private data class Fact(val label: String, val value: String, val color: Int)

    private companion object {
        const val CHART_HEIGHT = 96f
        const val HEATMAP_HEIGHT = 170f
        const val PHOTOS_PER_ROW = 3
        const val PHOTO_GAP = 10f
        const val PHOTO_TARGET_PX = 700
        const val MAX_TABLE_ROWS = 12
    }
}
