package com.phoneproof.diagnostics

import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evidence invariant is the core promise of the product: the report must never show a Pass for
 * a test that produced nothing. These tests pin that down at the type level.
 */
class TestResultInvariantsTest {

    private val sampleMeasurement =
        Measurement.number("battery.health_pct", "Battery health", 92.0, unit = "%")

    @Test
    fun `pass without measurements is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TestResult.Pass(summary = "all good", measurements = emptyList())
        }
        assertTrue(error.message!!.contains("at least one measurement"))
    }

    @Test
    fun `warn without measurements is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestResult.Warn(summary = "hmm", measurements = emptyList())
        }
    }

    @Test
    fun `fail without measurements is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TestResult.Fail(summary = "broken", measurements = emptyList())
        }
    }

    @Test
    fun `pass cannot carry a major finding`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TestResult.Pass(
                summary = "fine",
                measurements = listOf(sampleMeasurement),
                findings = listOf(Finding(Severity.MAJOR, "battery is shot")),
            )
        }
        assertTrue(error.message!!.contains("MAJOR"))
    }

    @Test
    fun `pass may carry informational findings`() {
        val result = TestResult.Pass(
            summary = "fine",
            measurements = listOf(sampleMeasurement),
            findings = listOf(Finding(Severity.INFO, "measured at 50% charge")),
        )
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `skipped and unsupported carry their reason as the summary`() {
        val skipped = TestResult.Skipped(
            com.phoneproof.diagnostics.model.SkipReason.PERMISSION_DENIED,
        )
        assertEquals("Required permission was not granted", skipped.summary)

        val unsupported = TestResult.Unsupported("no barometer on this device")
        assertEquals("no barometer on this device", unsupported.summary)
    }
}
