package com.phoneproof.diagnostics.model

import kotlinx.serialization.Serializable

/** Which audience this run is for. Changes test selection, wording and what the PDF emphasises. */
@Serializable
enum class RunMode(val label: String) {
    BUYER("Buyer check"),
    SELLER("Seller proof"),
    SHOP("Shop intake"),
}

/** Depth of the run — lets a shop trade thoroughness for counter time. */
@Serializable
enum class RunDepth(val label: String) {
    QUICK("Quick (automatic tests only)"),
    STANDARD("Standard (recommended)"),
    DEEP("Deep (adds long storage + thermal tests)"),
}

/** A completed (or abandoned) diagnostic session. This is the input to grading and the PDF. */
@Serializable
data class DiagnosticRun(
    val runId: String,
    val mode: RunMode,
    val depth: RunDepth,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val records: List<TestRecord>,
    val artifacts: List<Artifact> = emptyList(),
) {
    val durationMs: Long get() = finishedAtEpochMs - startedAtEpochMs

    fun record(testId: String): TestRecord? = records.firstOrNull { it.testId == testId }

    fun byCategory(category: Category): List<TestRecord> = records.filter { it.category == category }

    val executedCount: Int get() = records.count { it.didExecute }
    val skippedCount: Int get() = records.count { it.result is TestResult.Skipped }
    val unsupportedCount: Int get() = records.count { it.result is TestResult.Unsupported }
    val failedCount: Int get() = records.count { it.result is TestResult.Fail }
    val warnedCount: Int get() = records.count { it.result is TestResult.Warn }

    /**
     * Share of attempted tests that produced real evidence. The report prints this so a
     * skip-heavy run is obviously less trustworthy than a complete one.
     */
    val completeness: Double
        get() = if (records.isEmpty()) 0.0 else executedCount.toDouble() / records.size
}
