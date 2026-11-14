package com.phoneproof.diagnostics.util

import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.GuidedOutcome
import com.phoneproof.diagnostics.model.GuidedStep
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult

/**
 * Base for tests that need the user to do something.
 *
 * The subclass describes the interaction ([step]) and owns the pass/fail thresholds ([evaluate]).
 * The UI screen only gathers raw measurements, which keeps every threshold unit-testable without
 * touching Compose.
 */
abstract class GuidedTest : DiagnosticTest {

    override val requiresUserAction: Boolean get() = true

    override val estimatedDurationMs: Long get() = 20_000L

    protected abstract fun step(ctx: TestContext): GuidedStep

    /** Turns the raw measurements collected by the UI into a verdict. */
    protected abstract fun evaluate(outcome: GuidedOutcome.Completed): TestResult

    override suspend fun run(ctx: TestContext): TestResult =
        when (val outcome = ctx.interaction.await(step(ctx))) {
            is GuidedOutcome.Completed -> {
                if (outcome.measurements.isEmpty()) {
                    TestResult.Unsupported("Screen returned no measurements")
                } else {
                    outcome.artifacts.forEach(ctx.artifacts::register)
                    evaluate(outcome)
                }
            }

            is GuidedOutcome.Skipped -> TestResult.Skipped(outcome.reason, outcome.detail)
            is GuidedOutcome.Unsupported -> TestResult.Unsupported(outcome.reason)
        }

    protected fun GuidedOutcome.Completed.numeric(key: String): Double? =
        measurements.firstOrNull { it.key == key }?.let { m ->
            when (val v = m.value) {
                is com.phoneproof.diagnostics.model.MeasuredValue.Num -> v.value
                is com.phoneproof.diagnostics.model.MeasuredValue.Count -> v.value.toDouble()
                else -> null
            }
        }

    protected fun GuidedOutcome.Completed.flag(key: String): Boolean? =
        (measurements.firstOrNull { it.key == key }?.value
            as? com.phoneproof.diagnostics.model.MeasuredValue.Flag)?.value

    protected fun GuidedOutcome.Completed.grid(key: String) =
        measurements.firstOrNull { it.key == key }?.value
            as? com.phoneproof.diagnostics.model.MeasuredValue.Grid

    protected fun measurementsOf(vararg m: Measurement): List<Measurement> = m.toList()
}
