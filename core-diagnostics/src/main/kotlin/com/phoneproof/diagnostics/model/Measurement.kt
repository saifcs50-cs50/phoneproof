package com.phoneproof.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * A single raw measured value produced by a test.
 *
 * Measurements are the substance of a PhoneProof report: the PDF prints them verbatim and the
 * grading engine scores against them. A verdict without measurements is not evidence, which is why
 * [TestResult.Executed] refuses to be constructed without at least one.
 */
@Serializable
data class Measurement(
    /** Stable machine key, e.g. `battery.health_pct`. Used by the grading engine. */
    val key: String,
    /** Human label shown in the report, e.g. "Battery health". */
    val label: String,
    val value: MeasuredValue,
    /** Display unit, e.g. `%`, `mAh`, `Hz`, `MB/s`. */
    val unit: String? = null,
    /** What a healthy device looks like, shown next to the value for context. */
    val expected: String? = null,
    /** Free-form qualifier, e.g. how the value was derived or why it is low confidence. */
    val note: String? = null,
    /**
     * How much this particular number can be trusted.
     *
     * Declared by the test that produced it, because only that test knows whether the value came
     * off a fuel gauge or out of an extrapolation. The grading engine reads it before turning a
     * measurement into money, so a shaky figure cannot silently drive a price deduction. New field,
     * defaulted, so existing measurements and serialized reports are unaffected.
     */
    val confidence: MeasurementConfidence = MeasurementConfidence.HIGH,
) {
    /**
     * Restates this measurement at a different confidence, optionally recording why.
     *
     * A copy helper rather than a parameter on every factory: confidence is usually decided after
     * the value is built, once the test has cross-checked it against something else.
     */
    fun withConfidence(confidence: MeasurementConfidence, reason: String? = null): Measurement =
        copy(confidence = confidence, note = reason ?: note)

    /** Value rendered for the report, including unit. */
    val display: String
        get() = buildString {
            append(value.display)
            unit?.let { if (it.isNotBlank()) append(" ").append(it) }
        }

    companion object {
        fun number(key: String, label: String, value: Double, unit: String? = null, expected: String? = null, note: String? = null) =
            Measurement(key, label, MeasuredValue.Num(value), unit, expected, note)

        fun count(key: String, label: String, value: Long, unit: String? = null, expected: String? = null, note: String? = null) =
            Measurement(key, label, MeasuredValue.Count(value), unit, expected, note)

        fun text(key: String, label: String, value: String, expected: String? = null, note: String? = null) =
            Measurement(key, label, MeasuredValue.Text(value), null, expected, note)

        fun flag(key: String, label: String, value: Boolean, expected: String? = null, note: String? = null) =
            Measurement(key, label, MeasuredValue.Flag(value), null, expected, note)

        fun series(key: String, label: String, points: List<Double>, unit: String? = null, note: String? = null) =
            Measurement(key, label, MeasuredValue.Series(points), unit, null, note)

        fun grid(key: String, label: String, columns: Int, rows: Int, cells: List<Int>, note: String? = null) =
            Measurement(key, label, MeasuredValue.Grid(columns, rows, cells), null, null, note)

        fun table(key: String, label: String, rows: List<MeasuredValue.Table.Row>, note: String? = null) =
            Measurement(key, label, MeasuredValue.Table(rows), null, null, note)
    }
}

/** Typed measured value. Kept closed so the report and grading engine can exhaustively handle it. */
@Serializable
sealed interface MeasuredValue {
    val display: String

    @Serializable
    data class Num(val value: Double) : MeasuredValue {
        override val display: String
            get() = if (value == value.toLong().toDouble()) value.toLong().toString()
            else String.format("%.2f", value)
    }

    @Serializable
    data class Count(val value: Long) : MeasuredValue {
        override val display: String get() = value.toString()
    }

    @Serializable
    data class Text(val value: String) : MeasuredValue {
        override val display: String get() = value
    }

    @Serializable
    data class Flag(val value: Boolean) : MeasuredValue {
        override val display: String get() = if (value) "Yes" else "No"
    }

    /** Time series, e.g. thermal samples. Charted in the PDF rather than printed. */
    @Serializable
    data class Series(val values: List<Double>) : MeasuredValue {
        override val display: String
            get() = if (values.isEmpty()) "no samples"
            else "${values.size} samples (min ${fmt(values.min())}, max ${fmt(values.max())})"

        private fun fmt(v: Double) = String.format("%.1f", v)
    }

    /** Ordered label/value pairs, e.g. per-camera or per-mic breakdowns. */
    @Serializable
    data class Table(val rows: List<Row>) : MeasuredValue {
        @Serializable
        data class Row(val label: String, val value: String)

        override val display: String get() = rows.joinToString("; ") { "${it.label}: ${it.value}" }
    }

    /** 2-D occupancy grid, e.g. the touch coverage map. Rendered as a heatmap in the PDF. */
    @Serializable
    data class Grid(val columns: Int, val rows: Int, val cells: List<Int>) : MeasuredValue {
        init {
            require(columns > 0 && rows > 0) { "Grid needs positive dimensions" }
            require(cells.size == columns * rows) { "Grid cells must be columns*rows" }
        }

        val covered: Int get() = cells.count { it > 0 }
        val coverageFraction: Double get() = covered.toDouble() / cells.size

        override val display: String
            get() = "$covered/${cells.size} cells covered (${(coverageFraction * 100).toInt()}%)"
    }
}

/**
 * Trust level a test attaches to one of its own measurements.
 *
 * The distinction that matters commercially is between [LOW] and [CONTRADICTED]. A low-confidence
 * number is still the best available estimate and may be worth a reduced deduction; a contradicted
 * one has been disproved by another measurement from the same test and must never cost the seller
 * money, only be reported as unmeasurable.
 */
@Serializable
enum class MeasurementConfidence(val label: String) {
    /** Read directly from hardware, or reported by the platform itself. */
    HIGH("High"),

    /** Derived or extrapolated by a method known to be sound in normal conditions. */
    MEDIUM("Medium"),

    /** Derived under conditions that are known to distort it, or from an unverifiable input. */
    LOW("Low"),

    /** Disproved by another measurement in the same test. Reportable, never chargeable. */
    CONTRADICTED("Contradicted"),
}
