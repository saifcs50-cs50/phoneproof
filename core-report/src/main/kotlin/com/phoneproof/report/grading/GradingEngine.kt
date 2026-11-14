package com.phoneproof.report.grading

import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticRun
import com.phoneproof.diagnostics.model.MeasurementConfidence
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestRecord
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.tests.auto.BatteryHealthTest
import com.phoneproof.diagnostics.tests.auto.IntegrityTest
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a [DiagnosticRun] into a letter grade with every deduction traceable to a measured number.
 *
 * Two rules make the output defensible in an actual shop argument:
 *
 *  - **Nothing is deducted without evidence.** Deductions come from findings a test raised or from
 *    a measurement crossing a documented threshold, and each carries the value that triggered it.
 *  - **A deduction is never larger than its evidence.** Every measurement carries the confidence
 *    the producing test assigned it, and deductions are scaled by that confidence. A figure the
 *    test itself contradicted costs nothing at all — it is reported, not charged for.
 *  - **Missing evidence lowers confidence, it does not silently pass.** Skipped and unsupported
 *    tests contribute no score, so their weight drops out of the denominator and [GradeResult.confidence]
 *    falls. Below the configured threshold the grade is capped, because a mostly-unverified phone
 *    cannot honestly be sold as an A.
 */
class GradingEngine(private val config: GradingConfig = GradingConfig.DEFAULT) {

    fun grade(run: DiagnosticRun): GradeResult {
        val categoryScores = Category.displayOrder.mapNotNull { category ->
            scoreCategory(category, run.byCategory(category))
        }

        val allDeductions = categoryScores.flatMap { it.deductions }

        // Only categories that actually produced evidence count toward the score, so a skipped
        // camera test cannot drag the grade down — it reduces confidence instead.
        val scored = categoryScores.filter { it.hasEvidence && it.weight > 0 }
        val totalWeight = scored.sumOf { it.weight }
        val rawScore = if (totalWeight <= 0) 0.0
        else scored.sumOf { it.weightedContribution } / totalWeight

        val confidence = computeConfidence(categoryScores)

        var grade = Grade.forScore(rawScore)
        var cappedBy: String? = null

        config.capFor(confidence)?.let { cap ->
            if (grade.ordinal < cap.maxGrade.ordinal) {
                grade = cap.maxGrade
                cappedBy = "Only ${Math.round(confidence * 100)}% of the checks completed, so the " +
                    "grade is capped at ${cap.maxGrade.letter}. Run the remaining tests — the " +
                    "untested areas are where a buyer's risk sits."
            }
        }

        if (config.integrityFailureIsFatal && hasFatalIntegrityFailure(run)) {
            grade = Grade.F
            cappedBy = "An integrity check failed outright — bootloader unlocked, rooted, or " +
                "non-stock firmware. This overrides every other result."
        }

        return GradeResult(
            grade = grade,
            score = rawScore,
            confidence = confidence,
            categoryScores = categoryScores,
            deductions = allDeductions,
            cappedBy = cappedBy,
        )
    }

    private fun scoreCategory(category: Category, records: List<TestRecord>): CategoryScore? {
        val weight = config.weightFor(category)
        if (records.isEmpty() && weight <= 0) return null

        val executed = records.filter { it.didExecute }
        val deductions = records.flatMap(::deductionsFor)
        val score = (100.0 - deductions.sumOf { it.points }).coerceIn(0.0, 100.0)

        return CategoryScore(
            category = category,
            score = score,
            weight = weight,
            deductions = deductions,
            hasEvidence = executed.isNotEmpty(),
        )
    }

    private fun deductionsFor(record: TestRecord): List<Deduction> {
        if (!record.didExecute) return emptyList()

        val findingDeductions = record.findings
            .filter { it.severity != Severity.INFO }
            .mapNotNull { finding ->
                val source = finding.measurementKey?.let(record::measurement)
                deduction(
                    record = record,
                    rawPoints = config.pointsFor(finding.severity),
                    reason = finding.message,
                    measurementKey = finding.measurementKey,
                    evidence = source?.display,
                    confidence = source?.confidence ?: MeasurementConfidence.HIGH,
                )
            }

        return findingDeductions + graduatedDeductions(record)
    }

    /**
     * Builds one deduction, scaled by how far the measurement behind it can be trusted.
     *
     * This is where the report stops short of charging a seller for a number the app cannot stand
     * behind. The scaling and the original figure are both written into the reason, so a shop can
     * still explain the line — including why it is smaller than the raw severity would suggest.
     *
     * Returns null when nothing meaningful survives, which is the correct outcome for a measurement
     * the test itself disproved: the finding still appears in the report, it just costs nothing.
     */
    private fun deduction(
        record: TestRecord,
        rawPoints: Double,
        reason: String,
        measurementKey: String?,
        evidence: String?,
        confidence: MeasurementConfidence,
    ): Deduction? {
        val factor = config.deductionFactorFor(confidence)
        val points = rawPoints * factor
        if (points < MIN_MEANINGFUL_POINTS) return null

        return Deduction(
            category = record.category,
            testId = record.testId,
            points = points,
            reason = if (factor < 1.0) {
                "$reason — scored at ${Math.round(factor * 100)}% of the usual " +
                    "${Math.round(rawPoints)} points because the measurement behind it is " +
                    "${confidence.label.lowercase()} confidence"
            } else {
                reason
            },
            measurementKey = measurementKey,
            evidence = evidence,
            confidence = confidence,
        )
    }

    /**
     * Some measurements deserve a proportional deduction rather than a step change. Battery health
     * is the clearest case: 84% and 60% are both "worn", but they are not the same money.
     */
    private fun graduatedDeductions(record: TestRecord): List<Deduction> = buildList {
        if (record.testId == BatteryHealthTest.ID) {
            val source = record.measurement(BatteryHealthTest.KEY_HEALTH_PCT)
            val health = record.numeric(BatteryHealthTest.KEY_HEALTH_PCT)
            if (health != null && health < BATTERY_IDEAL_PCT) {
                // One point per percentage point below ideal, so a 70% battery loses 25 points on
                // top of whatever finding severity already applied. Scaled by confidence like every
                // other deduction: a phone reporting 50% health on a cell with 40 charge cycles is a
                // broken fuel gauge, not a 45-point price cut.
                val shortfall = BATTERY_IDEAL_PCT - health
                deduction(
                    record = record,
                    rawPoints = min(shortfall * BATTERY_POINTS_PER_PERCENT, BATTERY_MAX_DEDUCTION),
                    reason = "Battery is ${Math.round(shortfall)}% below a healthy cell",
                    measurementKey = BatteryHealthTest.KEY_HEALTH_PCT,
                    evidence = "${Math.round(health)}% of factory capacity",
                    confidence = source?.confidence ?: MeasurementConfidence.HIGH,
                )?.let(::add)
            }
        }
    }

    /** Share of total configured weight that is backed by tests which actually executed. */
    private fun computeConfidence(scores: List<CategoryScore>): Double {
        val totalWeight = scores.sumOf { it.weight }
        if (totalWeight <= 0) return 0.0
        val evidencedWeight = scores.filter { it.hasEvidence }.sumOf { it.weight }
        return (evidencedWeight / totalWeight).coerceIn(0.0, 1.0)
    }

    private fun hasFatalIntegrityFailure(run: DiagnosticRun): Boolean {
        val integrity = run.record(IntegrityTest.ID) ?: return false
        return integrity.result is TestResult.Fail &&
            integrity.findings.any { it.severity == Severity.CRITICAL }
    }

    private companion object {
        const val BATTERY_IDEAL_PCT = 95.0
        const val BATTERY_POINTS_PER_PERCENT = 1.0
        const val BATTERY_MAX_DEDUCTION = 60.0
        const val MIN_MEANINGFUL_POINTS = 0.5
    }
}
