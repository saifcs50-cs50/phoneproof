package com.phoneproof.diagnostics.model

import kotlinx.serialization.Serializable

/** Severity of an individual [Finding]. Mirrors the report's colour coding. */
@Serializable
enum class Severity { INFO, MINOR, MAJOR, CRITICAL }

/**
 * A specific, itemised observation. Findings are what the grading engine turns into deductions and
 * what the PDF lists under each test, so each one should name the measurement it came from.
 */
@Serializable
data class Finding(
    val severity: Severity,
    val message: String,
    /** Key of the [Measurement] this finding was derived from, when there is one. */
    val measurementKey: String? = null,
)

/**
 * Outcome of a single diagnostic test.
 *
 * Invariant: a test can only report [Pass], [Warn] or [Fail] by supplying at least one
 * [Measurement]. A verdict with no measured evidence is rejected at construction time, which is how
 * PhoneProof guarantees the report never shows a Pass for a test that did not really run.
 *
 * [Skipped] and [Unsupported] are first-class outcomes and are rendered prominently; a report full
 * of skips is visibly worth less than one full of passes.
 */
@Serializable
sealed interface TestResult {

    /** Short line shown under the test title in the report. */
    val summary: String

    /** A test that actually executed and produced evidence. */
    @Serializable
    sealed interface Executed : TestResult {
        val measurements: List<Measurement>
        val findings: List<Finding>
    }

    /** Hardware behaved within expected limits. */
    @Serializable
    data class Pass(
        override val summary: String,
        override val measurements: List<Measurement>,
        override val findings: List<Finding> = emptyList(),
    ) : Executed {
        init {
            require(measurements.isNotEmpty()) {
                "Pass requires at least one measurement — a verdict without evidence is not allowed"
            }
            require(findings.none { it.severity == Severity.CRITICAL || it.severity == Severity.MAJOR }) {
                "Pass cannot carry a MAJOR or CRITICAL finding; use Warn or Fail"
            }
        }
    }

    /** Works, but measurably degraded or of low confidence. */
    @Serializable
    data class Warn(
        override val summary: String,
        override val measurements: List<Measurement>,
        override val findings: List<Finding> = emptyList(),
    ) : Executed {
        init {
            require(measurements.isNotEmpty()) {
                "Warn requires at least one measurement — a verdict without evidence is not allowed"
            }
        }
    }

    /** Hardware is faulty, absent when it should be present, or failed an integrity check. */
    @Serializable
    data class Fail(
        override val summary: String,
        override val measurements: List<Measurement>,
        override val findings: List<Finding> = emptyList(),
    ) : Executed {
        init {
            require(measurements.isNotEmpty()) {
                "Fail requires at least one measurement — a verdict without evidence is not allowed"
            }
        }
    }

    /**
     * The test could have run on this device but did not: the user skipped it, declined a
     * permission, or the run was cut short.
     */
    @Serializable
    data class Skipped(val reason: SkipReason, val detail: String? = null) : TestResult {
        override val summary: String get() = detail ?: reason.description
    }

    /**
     * The device genuinely cannot support this test: hardware absent, API level too low, or the
     * OEM does not expose the required sysfs path. Never a substitute for [Fail]; it means
     * "cannot know", not "is broken".
     */
    @Serializable
    data class Unsupported(val reason: String) : TestResult {
        override val summary: String get() = reason
    }
}

@Serializable
enum class SkipReason(val description: String) {
    USER_SKIPPED("Skipped by the person running the test"),
    PERMISSION_DENIED("Required permission was not granted"),
    TIMED_OUT("Test did not complete in time"),
    PRECONDITION_NOT_MET("Device was not in the required state"),
    ABORTED("Diagnostic run was cancelled"),
    ERROR("Test failed to execute"),
}

/**
 * A [TestResult] plus the execution metadata recorded by the runner.
 *
 * Only [com.phoneproof.diagnostics.runner.DiagnosticRunner] creates these, so the presence of a
 * record is itself proof that the test was dispatched; results cannot be fabricated by a test
 * that never ran.
 */
@Serializable
data class TestRecord(
    val testId: String,
    val title: String,
    val category: Category,
    val result: TestResult,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    /** True when the test needed the user to do something physical. */
    val guided: Boolean,
) {
    val measurements: List<Measurement>
        get() = (result as? TestResult.Executed)?.measurements ?: emptyList()

    val findings: List<Finding>
        get() = (result as? TestResult.Executed)?.findings ?: emptyList()

    val didExecute: Boolean get() = result is TestResult.Executed

    fun measurement(key: String): Measurement? = measurements.firstOrNull { it.key == key }

    fun numeric(key: String): Double? = when (val v = measurement(key)?.value) {
        is MeasuredValue.Num -> v.value
        is MeasuredValue.Count -> v.value.toDouble()
        else -> null
    }

    fun flag(key: String): Boolean? = (measurement(key)?.value as? MeasuredValue.Flag)?.value

    fun text(key: String): String? = (measurement(key)?.value as? MeasuredValue.Text)?.value
}
