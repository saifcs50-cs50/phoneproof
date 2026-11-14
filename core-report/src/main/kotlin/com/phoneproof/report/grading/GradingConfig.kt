package com.phoneproof.report.grading

import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.MeasurementConfidence
import com.phoneproof.diagnostics.model.Severity
import kotlinx.serialization.Serializable

/**
 * Tunable grading policy.
 *
 * Shops weight things differently — a refurbisher who replaces every battery cares far less about
 * battery health than a counter buyer does — so this is editable per outlet and travels with the
 * report so an old grade can always be explained by the policy that produced it.
 */
@Serializable
data class GradingConfig(
    val categoryWeights: Map<Category, Double> = DEFAULT_WEIGHTS,
    val severityPoints: Map<Severity, Double> = DEFAULT_SEVERITY_POINTS,
    /**
     * Grade ceilings by evidence coverage, strictest first.
     *
     * A run that skipped the touchscreen, cameras and speakers must not be able to present as an A
     * no matter how well the automatic tests went — the untested half of the phone is exactly where
     * a buyer's money is at risk. Automatic-only runs land near 0.69 coverage and are therefore
     * capped at B, which is honest rather than punitive.
     */
    val confidenceCaps: List<ConfidenceCap> = DEFAULT_CONFIDENCE_CAPS,
    /**
     * How much of a deduction survives when the measurement behind it is not fully trusted.
     *
     * A price cut has to be defensible, and a number the producing test has already qualified
     * cannot defend one. A factor of 0 means the deduction is dropped entirely — the finding still
     * appears in the report, it just does not cost the seller money.
     */
    val confidenceDeductionFactors: Map<MeasurementConfidence, Double> = DEFAULT_CONFIDENCE_FACTORS,
    /** A single critical integrity finding forces the report to fail outright. */
    val integrityFailureIsFatal: Boolean = true,
) {
    fun weightFor(category: Category): Double = categoryWeights[category] ?: 0.0

    fun pointsFor(severity: Severity): Double = severityPoints[severity] ?: 0.0

    /** Multiplier applied to any deduction traced to a measurement of this confidence. */
    fun deductionFactorFor(confidence: MeasurementConfidence): Double =
        confidenceDeductionFactors[confidence] ?: 1.0

    /** The best grade this run is allowed to claim given how much of it actually ran. */
    fun capFor(confidence: Double): ConfidenceCap? =
        confidenceCaps.sortedBy { it.minimumConfidence }
            .firstOrNull { confidence < it.minimumConfidence }

    companion object {
        /**
         * Boundaries are set against real coverage arithmetic rather than round numbers: the
         * automatic-only suite covers 69% of the weighted checks, so the middle rung sits at 0.65
         * to let a clean quick run reach B while still denying it an A.
         */
        val DEFAULT_CONFIDENCE_CAPS: List<ConfidenceCap> = listOf(
            ConfidenceCap(minimumConfidence = 0.45, maxGrade = Grade.D),
            ConfidenceCap(minimumConfidence = 0.65, maxGrade = Grade.C),
            ConfidenceCap(minimumConfidence = 0.85, maxGrade = Grade.B),
        )

        /**
         * Defaults reflect resale reality rather than engineering purity: integrity and battery
         * move the price most, and a dead pixel matters less than an unlocked bootloader.
         */
        val DEFAULT_WEIGHTS: Map<Category, Double> = mapOf(
            Category.INTEGRITY to 20.0,
            Category.BATTERY to 18.0,
            Category.DISPLAY to 14.0,
            Category.TOUCH to 10.0,
            Category.CAMERA to 9.0,
            Category.AUDIO to 8.0,
            Category.CONNECTIVITY to 8.0,
            Category.PERFORMANCE to 6.0,
            Category.SENSORS to 3.0,
            Category.CONTROLS to 3.0,
            Category.CONDITION to 1.0,
            Category.IDENTITY to 0.0,
        )

        /**
         * Medium is the ordinary path for most measurements on most handsets — a fuel-gauge
         * extrapolation at a sane charge level is how battery health is normally obtained — so it
         * is not penalised. Low keeps a quarter of the deduction: the phone may genuinely be worn
         * and ignoring that outright would be as dishonest as over-charging for it. Contradicted
         * scores nothing, because the test that produced the number has already disproved it.
         */
        val DEFAULT_CONFIDENCE_FACTORS: Map<MeasurementConfidence, Double> = mapOf(
            MeasurementConfidence.HIGH to 1.0,
            MeasurementConfidence.MEDIUM to 1.0,
            MeasurementConfidence.LOW to 0.25,
            MeasurementConfidence.CONTRADICTED to 0.0,
        )

        val DEFAULT_SEVERITY_POINTS: Map<Severity, Double> = mapOf(
            Severity.CRITICAL to 100.0,
            Severity.MAJOR to 35.0,
            Severity.MINOR to 12.0,
            Severity.INFO to 0.0,
        )

        val DEFAULT = GradingConfig()
    }
}

/** One rung of the coverage ladder: below [minimumConfidence], the grade cannot beat [maxGrade]. */
@Serializable
data class ConfidenceCap(
    val minimumConfidence: Double,
    val maxGrade: Grade,
)
