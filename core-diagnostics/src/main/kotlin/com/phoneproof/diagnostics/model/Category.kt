package com.phoneproof.diagnostics.model

import kotlinx.serialization.Serializable

/**
 * Top-level grouping for a [DiagnosticTest]. Categories drive both the report layout and the
 * weighted grading engine, so every test must belong to exactly one.
 */
@Serializable
enum class Category(val displayName: String, val order: Int) {
    IDENTITY("Device Identity", 0),
    INTEGRITY("Integrity & Authenticity", 1),
    BATTERY("Battery & Charging", 2),
    PERFORMANCE("Performance & Storage", 3),
    DISPLAY("Display", 4),
    TOUCH("Touchscreen", 5),
    AUDIO("Audio", 6),
    CAMERA("Cameras", 7),
    SENSORS("Sensors", 8),
    CONNECTIVITY("Connectivity", 9),
    CONTROLS("Buttons & Biometrics", 10),
    CONDITION("Physical Condition", 11);

    companion object {
        val displayOrder: List<Category> get() = entries.sortedBy { it.order }
    }
}
