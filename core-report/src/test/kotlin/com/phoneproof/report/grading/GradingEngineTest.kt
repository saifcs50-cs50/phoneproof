package com.phoneproof.report.grading

import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticRun
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasurementConfidence
import com.phoneproof.diagnostics.model.RunDepth
import com.phoneproof.diagnostics.model.RunMode
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestRecord
import com.phoneproof.diagnostics.model.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingEngineTest {

    private val engine = GradingEngine()

    @Test
    fun `clean device across all categories grades A with full confidence`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            passRecord("battery.health", Category.BATTERY, Measurement.number("battery.health_pct", "Health", 98.0)),
            passRecord("display.capability", Category.DISPLAY),
            passRecord("touch.grid", Category.TOUCH),
            passRecord("camera.capture", Category.CAMERA),
            passRecord("audio.speaker", Category.AUDIO),
            passRecord("connectivity.cellular", Category.CONNECTIVITY),
            passRecord("performance.storage_speed", Category.PERFORMANCE),
            passRecord("sensors.inventory", Category.SENSORS),
            passRecord("controls.buttons", Category.CONTROLS),
            passRecord("condition.photos", Category.CONDITION),
        )

        val result = engine.grade(run)

        assertEquals(Grade.A, result.grade)
        assertEquals(100, result.confidencePercent)
        assertNull(result.cappedBy)
    }

    @Test
    fun `critical integrity failure forces F regardless of everything else`() {
        val run = runOf(
            TestRecord(
                testId = "integrity.bootloader_root",
                title = "Integrity",
                category = Category.INTEGRITY,
                result = TestResult.Fail(
                    summary = "Bootloader unlocked",
                    measurements = listOf(Measurement.flag("integrity.bootloader_locked", "Bootloader", false)),
                    findings = listOf(
                        Finding(Severity.CRITICAL, "Bootloader is UNLOCKED", "integrity.bootloader_locked"),
                    ),
                ),
                startedAtEpochMs = 0,
                durationMs = 10,
                guided = false,
            ),
            passRecord("battery.health", Category.BATTERY, Measurement.number("battery.health_pct", "Health", 100.0)),
            passRecord("display.capability", Category.DISPLAY),
            passRecord("touch.grid", Category.TOUCH),
            passRecord("camera.capture", Category.CAMERA),
            passRecord("audio.speaker", Category.AUDIO),
            passRecord("connectivity.cellular", Category.CONNECTIVITY),
            passRecord("performance.storage_speed", Category.PERFORMANCE),
            passRecord("sensors.inventory", Category.SENSORS),
            passRecord("controls.buttons", Category.CONTROLS),
            passRecord("condition.photos", Category.CONDITION),
        )

        val result = engine.grade(run)

        assertEquals(Grade.F, result.grade)
        assertNotNull(result.cappedBy)
        assertTrue(result.cappedBy!!.contains("integrity", ignoreCase = true))
    }

    @Test
    fun `a run full of skips cannot present as a top grade`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            skippedRecord("battery.health", Category.BATTERY),
            skippedRecord("display.capability", Category.DISPLAY),
            skippedRecord("touch.grid", Category.TOUCH),
            skippedRecord("camera.capture", Category.CAMERA),
            skippedRecord("audio.speaker", Category.AUDIO),
            skippedRecord("connectivity.cellular", Category.CONNECTIVITY),
            skippedRecord("performance.storage_speed", Category.PERFORMANCE),
        )

        val result = engine.grade(run)

        assertTrue(
            "expected the grade to be capped, got ${result.grade}",
            result.grade.ordinal >= Grade.C.ordinal,
        )
        assertTrue("confidence should be low, was ${result.confidence}", result.confidence < 0.6)
        assertNotNull(result.cappedBy)
    }

    @Test
    fun `skipped tests do not create deductions`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            skippedRecord("battery.health", Category.BATTERY),
        )

        val result = engine.grade(run)

        assertTrue("skips must not deduct points", result.deductions.isEmpty())
    }

    @Test
    fun `worn battery produces a proportional deduction carrying its evidence`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            TestRecord(
                testId = "battery.health",
                title = "Battery health",
                category = Category.BATTERY,
                result = TestResult.Warn(
                    summary = "72% of factory capacity",
                    measurements = listOf(Measurement.number("battery.health_pct", "Health", 72.0, unit = "%")),
                    findings = listOf(Finding(Severity.MINOR, "Battery measurably worn", "battery.health_pct")),
                ),
                startedAtEpochMs = 0,
                durationMs = 10,
                guided = false,
            ),
        )

        val result = engine.grade(run)
        val batteryDeductions = result.deductions.filter { it.category == Category.BATTERY }

        assertTrue("expected a graduated battery deduction", batteryDeductions.size >= 2)
        val graduated = batteryDeductions.first { it.reason.contains("below a healthy cell") }
        assertEquals(23.0, graduated.points, 0.01)
        assertEquals("72% of factory capacity", graduated.evidence)
    }

    @Test
    fun `every deduction names the test it came from`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            TestRecord(
                testId = "display.capability",
                title = "Display",
                category = Category.DISPLAY,
                result = TestResult.Fail(
                    summary = "panel underperforms",
                    measurements = listOf(Measurement.number("display.measured_hz", "Measured", 60.0, unit = "Hz")),
                    findings = listOf(Finding(Severity.MAJOR, "Panel delivered only 60 Hz", "display.measured_hz")),
                ),
                startedAtEpochMs = 0,
                durationMs = 10,
                guided = false,
            ),
        )

        val result = engine.grade(run)

        assertTrue(result.deductions.isNotEmpty())
        result.deductions.forEach { deduction ->
            assertTrue("deduction lacks a test id", deduction.testId.isNotBlank())
            assertTrue("deduction lacks a reason", deduction.reason.isNotBlank())
        }
    }

    @Test
    fun `an automatic-only run cannot claim an A`() {
        // Coverage from automatic tests alone lands near 69%: touch, camera, audio, controls and
        // condition are all unverified, which is precisely where a buyer's money is at risk.
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            passRecord("battery.health", Category.BATTERY, Measurement.number("battery.health_pct", "Health", 99.0)),
            passRecord("display.capability", Category.DISPLAY),
            passRecord("connectivity.cellular", Category.CONNECTIVITY),
            passRecord("performance.storage_speed", Category.PERFORMANCE),
            passRecord("sensors.inventory", Category.SENSORS),
            skippedRecord("touch.grid", Category.TOUCH),
            skippedRecord("camera.capture", Category.CAMERA),
            skippedRecord("audio.speaker", Category.AUDIO),
            skippedRecord("controls.buttons", Category.CONTROLS),
            skippedRecord("condition.photos", Category.CONDITION),
        )

        val result = engine.grade(run)

        assertEquals(
            "a perfect automatic run with everything guided skipped must cap at B",
            Grade.B,
            result.grade,
        )
        assertNotNull(result.cappedBy)
        assertTrue(result.confidence in 0.60..0.80)
    }

    @Test
    fun `full coverage removes the cap`() {
        val run = runOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            passRecord("battery.health", Category.BATTERY, Measurement.number("battery.health_pct", "Health", 99.0)),
            passRecord("display.capability", Category.DISPLAY),
            passRecord("connectivity.cellular", Category.CONNECTIVITY),
            passRecord("performance.storage_speed", Category.PERFORMANCE),
            passRecord("sensors.inventory", Category.SENSORS),
            passRecord("touch.grid", Category.TOUCH),
            passRecord("camera.capture", Category.CAMERA),
            passRecord("audio.speaker", Category.AUDIO),
            passRecord("controls.buttons", Category.CONTROLS),
            passRecord("condition.photos", Category.CONDITION),
        )

        val result = engine.grade(run)

        assertEquals(Grade.A, result.grade)
        assertNull(result.cappedBy)
    }

    @Test
    fun `custom weights change the outcome`() {
        val records = listOf(
            passRecord("integrity.bootloader_root", Category.INTEGRITY),
            TestRecord(
                testId = "camera.capture",
                title = "Cameras",
                category = Category.CAMERA,
                result = TestResult.Fail(
                    summary = "rear camera blurry",
                    measurements = listOf(Measurement.number("camera.blur", "Blur", 3.0)),
                    findings = listOf(Finding(Severity.MAJOR, "Rear camera cannot focus", "camera.blur")),
                ),
                startedAtEpochMs = 0,
                durationMs = 10,
                guided = true,
            ),
        )

        val cameraIrrelevant = GradingConfig(
            categoryWeights = mapOf(Category.INTEGRITY to 100.0, Category.CAMERA to 0.0),
        )
        val cameraCritical = GradingConfig(
            categoryWeights = mapOf(Category.INTEGRITY to 10.0, Category.CAMERA to 90.0),
        )

        val lenient = GradingEngine(cameraIrrelevant).grade(runOf(*records.toTypedArray()))
        val strict = GradingEngine(cameraCritical).grade(runOf(*records.toTypedArray()))

        assertTrue(
            "weighting cameras heavily must lower the score",
            strict.score < lenient.score,
        )
    }

    /**
     * The case this guard exists for: 50% health computed against a design capacity the platform
     * could not confirm, on a cell with 40 charge cycles. The test itself disproved the figure;
     * grading it as a 45-point price cut was the single most expensive bug in the app.
     */
    @Test
    fun `battery health the test contradicted costs nothing`() {
        val run = fullRunWithBattery(
            batteryRecord(
                healthPercent = 50.0,
                confidence = MeasurementConfidence.CONTRADICTED,
                findings = listOf(
                    Finding(
                        Severity.INFO,
                        "Battery capacity could not be measured reliably on this device",
                        "battery.confidence",
                    ),
                ),
            ),
        )

        val result = engine.grade(run)

        assertTrue(
            "a contradicted measurement must not produce any deduction",
            result.deductions.none { it.testId == "battery.health" },
        )
        assertEquals(
            100.0,
            result.categoryScores.first { it.category == Category.BATTERY }.score,
            0.001,
        )
    }

    @Test
    fun `low confidence battery health deducts a fraction and says so`() {
        val run = fullRunWithBattery(
            batteryRecord(
                healthPercent = 50.0,
                confidence = MeasurementConfidence.LOW,
                findings = listOf(
                    Finding(Severity.MAJOR, "Battery holds only 50% of its factory capacity", "battery.health_pct"),
                ),
            ),
        )

        val result = engine.grade(run)
        val batteryPoints = result.deductions.filter { it.testId == "battery.health" }.sumOf { it.points }

        // A quarter of the usual 35 (major finding) + 45 (graduated shortfall).
        assertEquals(20.0, batteryPoints, 0.001)
        assertTrue(
            "the reason must explain why the deduction is smaller than usual",
            result.deductions.filter { it.testId == "battery.health" }
                .all { it.reason.contains("low confidence") },
        )
    }

    /** Guard on the other side: damping must not leak into figures the test does stand behind. */
    @Test
    fun `high confidence battery health still deducts in full`() {
        val run = fullRunWithBattery(
            batteryRecord(
                healthPercent = 50.0,
                confidence = MeasurementConfidence.HIGH,
                findings = listOf(
                    Finding(Severity.MAJOR, "Battery holds only 50% of its factory capacity", "battery.health_pct"),
                ),
            ),
        )

        val result = engine.grade(run)
        val batteryDeductions = result.deductions.filter { it.testId == "battery.health" }

        assertEquals(80.0, batteryDeductions.sumOf { it.points }, 0.001)
        assertFalse(batteryDeductions.any { it.reason.contains("confidence") })
    }

    // ---------------------------------------------------------------- helpers

    /** A complete run where every category passes except battery, which is supplied by the caller. */
    private fun fullRunWithBattery(battery: TestRecord) = runOf(
        passRecord("integrity.bootloader_root", Category.INTEGRITY),
        battery,
        passRecord("display.capability", Category.DISPLAY),
        passRecord("touch.grid", Category.TOUCH),
        passRecord("camera.capture", Category.CAMERA),
        passRecord("audio.speaker", Category.AUDIO),
        passRecord("connectivity.cellular", Category.CONNECTIVITY),
        passRecord("performance.storage_speed", Category.PERFORMANCE),
        passRecord("sensors.inventory", Category.SENSORS),
        passRecord("controls.buttons", Category.CONTROLS),
        passRecord("condition.photos", Category.CONDITION),
    )

    private fun batteryRecord(
        healthPercent: Double,
        confidence: MeasurementConfidence,
        findings: List<Finding>,
    ) = TestRecord(
        testId = "battery.health",
        title = "Battery health",
        category = Category.BATTERY,
        result = TestResult.Warn(
            summary = "$healthPercent% of factory capacity",
            measurements = listOf(
                Measurement.number("battery.health_pct", "Battery health", healthPercent, unit = "%")
                    .withConfidence(confidence),
                Measurement.count("battery.cycle_count", "Charge cycles", 40),
            ),
            findings = findings,
        ),
        startedAtEpochMs = 0,
        durationMs = 10,
        guided = false,
    )

    private fun runOf(vararg records: TestRecord) = DiagnosticRun(
        runId = "test-run",
        mode = RunMode.BUYER,
        depth = RunDepth.STANDARD,
        startedAtEpochMs = 0,
        finishedAtEpochMs = 1_000,
        records = records.toList(),
    )

    private fun passRecord(
        id: String,
        category: Category,
        vararg measurements: Measurement,
    ) = TestRecord(
        testId = id,
        title = id,
        category = category,
        result = TestResult.Pass(
            summary = "ok",
            measurements = if (measurements.isEmpty()) {
                listOf(Measurement.flag("$id.ok", "OK", true))
            } else {
                measurements.toList()
            },
        ),
        startedAtEpochMs = 0,
        durationMs = 10,
        guided = false,
    )

    private fun skippedRecord(id: String, category: Category) = TestRecord(
        testId = id,
        title = id,
        category = category,
        result = TestResult.Skipped(SkipReason.USER_SKIPPED),
        startedAtEpochMs = 0,
        durationMs = 1,
        guided = false,
    )
}
