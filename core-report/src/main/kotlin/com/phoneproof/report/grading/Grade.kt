package com.phoneproof.report.grading

import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.MeasurementConfidence
import kotlinx.serialization.Serializable

/** Overall letter grade shown on the report badge. */
@Serializable
enum class Grade(val letter: String, val label: String, val minimumScore: Int) {
    A("A", "Excellent", 90),
    B("B", "Good", 80),
    C("C", "Fair", 65),
    D("D", "Poor", 50),
    F("F", "Failed", 0);

    companion object {
        fun forScore(score: Double): Grade =
            entries.firstOrNull { score >= it.minimumScore } ?: F
    }
}

/**
 * One itemised, traceable score reduction.
 *
 * Every deduction names the test and, where there is one, the exact measurement it came from. That
 * is the whole point of the grading engine: a shop must be able to justify its offer line by line
 * when the customer pushes back.
 */
@Serializable
data class Deduction(
    val category: Category,
    val testId: String,
    val points: Double,
    val reason: String,
    val measurementKey: String? = null,
    /** The measured value that triggered this, formatted for display. */
    val evidence: String? = null,
    /**
     * Trust level of the measurement this came from. Anything below [MeasurementConfidence.HIGH]
     * means [points] has already been scaled down, and [reason] says by how much.
     */
    val confidence: MeasurementConfidence = MeasurementConfidence.HIGH,
)

/** Per-category outcome. */
@Serializable
data class CategoryScore(
    val category: Category,
    val score: Double,
    val weight: Double,
    val deductions: List<Deduction>,
    /** False when no test in this category produced evidence. */
    val hasEvidence: Boolean,
) {
    val weightedContribution: Double get() = score * weight
}

/**
 * The complete grading outcome.
 *
 * [confidence] is as important as [score]: it is the share of total weight backed by tests that
 * actually executed. A phone that passed three tests and skipped everything else must not present
 * the same as one that passed all of them, so a low confidence caps the achievable grade.
 */
@Serializable
data class GradeResult(
    val grade: Grade,
    val score: Double,
    val confidence: Double,
    val categoryScores: List<CategoryScore>,
    val deductions: List<Deduction>,
    val cappedBy: String? = null,
) {
    val scoreRounded: Int get() = Math.round(score).toInt()
    val confidencePercent: Int get() = Math.round(confidence * 100).toInt()

    /** Deductions worth showing on the summary page, largest first. */
    fun topDeductions(limit: Int = 5): List<Deduction> =
        deductions.sortedByDescending { it.points }.take(limit)
}
