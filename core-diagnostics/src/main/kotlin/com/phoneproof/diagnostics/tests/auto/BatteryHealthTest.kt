package com.phoneproof.diagnostics.tests.auto

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.phoneproof.diagnostics.catalog.DeviceSpecCatalog
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasurementConfidence
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.util.Sysfs
import kotlinx.coroutines.delay

/**
 * Estimates remaining battery capacity as a percentage of the factory design capacity.
 *
 * There is no single API that works across OEMs, so this walks a fallback chain and always reports
 * which method produced the number and how much to trust it. That transparency matters: battery is
 * the most common reason a second-hand price is disputed, and an unlabelled "87%" is worthless.
 *
 * Chain, best first:
 *  1. the platform state-of-health property (Android 15+, and only on devices that populate it);
 *  2. `charge_full ÷ charge_full_design` from sysfs, the classic Linux computation;
 *  3. `charge_counter ÷ charge level` compared against a design capacity, itself sourced from
 *     sysfs, then `PowerProfile`, then the bundled device catalogue;
 *  4. as a last resort and only on a deep run, a measured discharge extrapolation.
 */
class BatteryHealthTest(
    /** Deep runs may spend five minutes measuring discharge when nothing else works. */
    private val allowLoadMeasurement: Boolean = false,
) : DiagnosticTest {

    override val id = ID
    override val title = "Battery health"
    override val category = Category.BATTERY
    override val estimatedDurationMs = if (allowLoadMeasurement) 300_000L else 2_500L

    override suspend fun run(ctx: TestContext): TestResult {
        val manager = ctx.app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return TestResult.Unsupported("BatteryManager unavailable on this device")

        ctx.progress.update(0.1f, "Reading battery counters")
        val snapshot = readSnapshot(ctx.app, manager)

        ctx.progress.update(0.4f, "Estimating capacity")
        val estimate = estimateHealth(ctx, manager, snapshot)
            ?.let { sanityCheck(it, snapshot) }

        val measurements = buildList {
            if (estimate != null) {
                add(
                    Measurement.number(
                        KEY_HEALTH_PCT,
                        "Battery health",
                        estimate.healthPercent,
                        unit = "%",
                        expected = "85% or above",
                        note = "${estimate.method.label} · ${estimate.confidence.label} confidence",
                    ).withConfidence(estimate.confidence),
                )
                add(Measurement.text(KEY_METHOD, "Measurement method", estimate.method.label))
                add(Measurement.text(KEY_CONFIDENCE, "Confidence", estimate.confidence.label, note = estimate.confidenceReason))
                estimate.presentFullMah?.let {
                    add(Measurement.number(KEY_PRESENT_FULL_MAH, "Present full capacity", it, unit = "mAh"))
                }
                estimate.designCapacityMah?.let {
                    add(
                        Measurement.number(
                            KEY_DESIGN_MAH,
                            "Factory design capacity",
                            it,
                            unit = "mAh",
                            note = estimate.designSource,
                        ).withConfidence(estimate.designConfidence),
                    )
                }
            }

            add(Measurement.count(KEY_LEVEL, "Charge level at test time", snapshot.levelPercent.toLong(), unit = "%"))
            snapshot.cycleCount?.let {
                add(
                    Measurement.count(
                        KEY_CYCLES,
                        "Charge cycles",
                        it.toLong(),
                        expected = "under 500 for a healthy used phone",
                    ),
                )
            }
            snapshot.temperatureC?.let {
                add(Measurement.number(KEY_TEMPERATURE, "Battery temperature", it, unit = "°C", expected = "under 40 °C at idle"))
            }
            snapshot.voltageV?.let {
                add(Measurement.number(KEY_VOLTAGE, "Battery voltage", it, unit = "V"))
            }
            snapshot.technology?.let { add(Measurement.text(KEY_TECHNOLOGY, "Cell chemistry", it)) }
            add(Measurement.text(KEY_OS_HEALTH, "OS health flag", snapshot.osHealthLabel))
            snapshot.chargeCounterUah?.let {
                add(Measurement.count(KEY_CHARGE_COUNTER, "Charge counter", (it / 1000).toLong(), unit = "mAh"))
            }
        }

        if (estimate == null) {
            return TestResult.Unsupported(
                "This device does not expose usable battery capacity data. " +
                    "Samsung, Xiaomi and Oppo builds commonly block the sysfs nodes this needs, " +
                    "and no design capacity is known for ${Build.MODEL}.",
            )
        }

        ctx.progress.update(1f, "Battery measured")

        val findings = buildList {
            when {
                estimate.implausible -> add(
                    Finding(
                        Severity.INFO,
                        "Battery capacity could not be measured reliably on this device: " +
                            "${estimate.confidenceReason}. Treat the percentage as unusable and " +
                            "judge the battery by real-world runtime instead.",
                        KEY_CONFIDENCE,
                    ),
                )

                estimate.healthPercent < FAIL_THRESHOLD -> add(
                    Finding(
                        Severity.MAJOR,
                        "Battery holds only ${estimate.healthPercent.toInt()}% of its factory " +
                            "capacity. Budget for a replacement — this is a normal, quantifiable " +
                            "price deduction, not a defect to argue about.",
                        KEY_HEALTH_PCT,
                    ),
                )

                estimate.healthPercent < WARN_THRESHOLD -> add(
                    Finding(
                        Severity.MINOR,
                        "Battery is measurably worn at ${estimate.healthPercent.toInt()}% of " +
                            "factory capacity.",
                        KEY_HEALTH_PCT,
                    ),
                )
            }

            snapshot.cycleCount?.let { cycles ->
                if (cycles > HIGH_CYCLE_COUNT) {
                    add(
                        Finding(
                            Severity.MINOR,
                            "$cycles charge cycles recorded — heavy use for a second-hand handset.",
                            KEY_CYCLES,
                        ),
                    )
                }
            }

            snapshot.temperatureC?.let { temp ->
                if (temp > HOT_IDLE_TEMP_C) {
                    add(
                        Finding(
                            Severity.MINOR,
                            "Battery is ${temp.toInt()} °C before any load — check for a swollen " +
                                "cell or a background process cooking the phone.",
                            KEY_TEMPERATURE,
                        ),
                    )
                }
            }

            if (snapshot.osHealthLabel != "Good" && snapshot.osHealthLabel != "Unknown") {
                add(
                    Finding(
                        Severity.MAJOR,
                        "The operating system reports battery health as '${snapshot.osHealthLabel}'.",
                        KEY_OS_HEALTH,
                    ),
                )
            }

            if (estimate.confidence == MeasurementConfidence.LOW) {
                add(
                    Finding(
                        Severity.INFO,
                        "Low confidence figure — ${estimate.confidenceReason}. Treat it as " +
                            "indicative and re-test between 30% and 80% charge.",
                        KEY_CONFIDENCE,
                    ),
                )
            }
        }

        val summary = if (estimate.implausible) {
            "Not reliably measurable on this device"
        } else {
            "${estimate.healthPercent.toInt()}% of factory capacity " +
                "(${estimate.confidence.label.lowercase()} confidence)"
        }

        return when {
            estimate.implausible -> TestResult.Warn(summary, measurements, findings)

            findings.any { it.severity == Severity.MAJOR || it.severity == Severity.CRITICAL } ->
                TestResult.Fail(summary, measurements, findings)

            estimate.healthPercent < WARN_THRESHOLD || estimate.confidence == MeasurementConfidence.LOW ->
                TestResult.Warn(summary, measurements, findings)

            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    // snapshot

    private fun readSnapshot(context: Context, manager: BatteryManager): Snapshot {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
            ?: intent?.let {
                val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (raw >= 0 && scale > 0) raw * 100 / scale else null
            }
            ?: 0

        val chargeCounter = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0 }

        val cycles = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 }
            ?: Sysfs.readInt(*CYCLE_COUNT_PATHS)?.takeIf { it > 0 }

        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > -500 }
            ?.let { it / 10.0 }

        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
            ?.let { it / 1000.0 }

        return Snapshot(
            levelPercent = level,
            chargeCounterUah = chargeCounter,
            cycleCount = cycles,
            temperatureC = temperature,
            voltageV = voltage,
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            osHealthLabel = healthLabel(intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1),
        )
    }

    private fun healthLabel(code: Int): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> "Unknown"
    }

    /**
     * Cross-checks a computed figure against the charge cycle count.
     *
     * Cells degrade with use, so a battery cannot be half-dead after a few dozen cycles. When the
     * two disagree, the fuel-gauge arithmetic is the suspect; several MediaTek platforms report a
     * charge counter that does not correspond to true coulombs, and the design capacity from
     * PowerProfile can be a platform default rather than the real cell. Rather than accuse a
     * healthy phone, the estimate is demoted to low confidence and says why.
     */
    private fun sanityCheck(estimate: Estimate, snapshot: Snapshot): Estimate {
        if (estimate.method == HealthMethod.PLATFORM_API) return estimate

        val cycles = snapshot.cycleCount ?: return estimate
        if (cycles > LOW_CYCLE_COUNT) return estimate
        if (estimate.healthPercent > IMPLAUSIBLE_HEALTH_FOR_LOW_CYCLES) return estimate

        return estimate.copy(
            confidence = MeasurementConfidence.CONTRADICTED,
            confidenceReason = "the computed figure (${estimate.healthPercent.toInt()}%) contradicts " +
                "the recorded $cycles charge cycles — a cell this lightly used cannot be this worn, " +
                "so this device's charge counter or design capacity is unreliable",
        )
    }

    // estimation chain

    private suspend fun estimateHealth(
        ctx: TestContext,
        manager: BatteryManager,
        snapshot: Snapshot,
    ): Estimate? {
        platformStateOfHealth(manager)?.let { soh ->
            return Estimate(
                healthPercent = soh.toDouble(),
                method = HealthMethod.PLATFORM_API,
                confidence = MeasurementConfidence.HIGH,
                confidenceReason = "reported directly by the platform",
            )
        }

        ctx.progress.update(0.5f, "Reading capacity nodes")
        sysfsChargeFullRatio()?.let { return it }

        ctx.progress.update(0.6f, "Computing from charge counter")
        computedFromChargeCounter(ctx, snapshot)?.let { return it }

        if (allowLoadMeasurement) {
            ctx.progress.update(0.65f, "Measuring discharge under load (5 min)")
            return measureByDischarge(ctx, manager, snapshot)
        }

        return null
    }

    /**
     * `BATTERY_PROPERTY_STATE_OF_HEALTH` is not exposed in the public SDK even on API 36, so it is
     * read by its documented constant id. An implausible value means the device does not implement
     * it and we fall through rather than reporting a fabricated number.
     */
    private fun platformStateOfHealth(manager: BatteryManager): Int? {
        if (Build.VERSION.SDK_INT < 35) return null
        val propertyId = resolveStateOfHealthPropertyId()
        return runCatching { manager.getIntProperty(propertyId) }
            .getOrNull()
            ?.takeIf { it in 1..100 }
    }

    private fun resolveStateOfHealthPropertyId(): Int = runCatching {
        BatteryManager::class.java.getField("BATTERY_PROPERTY_STATE_OF_HEALTH").getInt(null)
    }.getOrDefault(STATE_OF_HEALTH_PROPERTY_ID)

    private fun sysfsChargeFullRatio(): Estimate? {
        val full = Sysfs.readLong(*CHARGE_FULL_PATHS) ?: return null
        val design = Sysfs.readLong(*CHARGE_FULL_DESIGN_PATHS) ?: return null
        if (full <= 0 || design <= 0) return null

        val ratio = (full.toDouble() / design.toDouble()) * 100
        if (ratio !in PLAUSIBLE_HEALTH_RANGE) return null

        return Estimate(
            healthPercent = ratio.coerceAtMost(100.0),
            method = HealthMethod.SYSFS_CHARGE_FULL,
            confidence = MeasurementConfidence.HIGH,
            presentFullMah = full / 1000.0,
            designCapacityMah = design / 1000.0,
            designSource = "sysfs charge_full_design",
            confidenceReason = "read straight from the fuel gauge",
        )
    }

    private fun computedFromChargeCounter(ctx: TestContext, snapshot: Snapshot): Estimate? {
        val counter = snapshot.chargeCounterUah ?: return null
        val level = snapshot.levelPercent.takeIf { it in 1..100 } ?: return null

        val design = resolveDesignCapacity(ctx) ?: return null
        val presentFullUah = counter / (level / 100.0)
        val ratio = (presentFullUah / (design.capacityMah * 1000.0)) * 100

        if (ratio !in PLAUSIBLE_HEALTH_RANGE) return null

        // Extrapolating from the charge counter is only accurate in the middle of the range: fuel
        // gauges are deliberately non-linear near empty and near full.
        val (levelConfidence, levelReason) = when {
            level < 25 -> MeasurementConfidence.LOW to
                "measured at only $level% charge, where the fuel gauge is least linear"
            level > 95 -> MeasurementConfidence.LOW to
                "measured at $level% charge, near the top of the curve"
            else -> MeasurementConfidence.MEDIUM to
                "extrapolated from the charge counter at $level%"
        }

        // A ratio is only as good as its denominator, so an unverifiable design capacity drags the
        // whole figure down no matter how ideal the charge level was.
        val confidence = weakerOf(levelConfidence, design.confidence)
        val reason = listOfNotNull(levelReason, design.caveat).joinToString(", and ")

        return Estimate(
            healthPercent = ratio.coerceAtMost(100.0),
            method = HealthMethod.COMPUTED_COUNTER,
            confidence = confidence,
            presentFullMah = presentFullUah / 1000.0,
            designCapacityMah = design.capacityMah,
            designSource = design.source,
            designConfidence = design.confidence,
            confidenceReason = reason,
        )
    }

    /**
     * Last resort: hold a measurable load and watch the charge counter fall, then extrapolate to a
     * full cycle. Slow and noisy, so it only ever runs on a deep run and always reports LOW
     * confidence.
     */
    private suspend fun measureByDischarge(
        ctx: TestContext,
        manager: BatteryManager,
        snapshot: Snapshot,
    ): Estimate? {
        val design = resolveDesignCapacity(ctx) ?: return null
        val startCounter = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val startLevel = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (startCounter <= 0 || startLevel !in 1..100) return null

        val steps = 30
        repeat(steps) { step ->
            busyLoad(LOAD_SLICE_MS)
            delay(LOAD_REST_MS)
            ctx.progress.update(
                0.65f + 0.3f * (step + 1) / steps,
                "Measuring discharge ${step + 1}/$steps",
            )
        }

        val endCounter = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val endLevel = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val levelDrop = startLevel - endLevel
        val counterDrop = startCounter - endCounter
        if (levelDrop < 1 || counterDrop <= 0) return null

        // µAh consumed per percentage point, scaled to a whole battery.
        val fullCapacityUah = counterDrop.toDouble() / levelDrop * 100.0
        val ratio = (fullCapacityUah / (design.capacityMah * 1000.0)) * 100
        if (ratio !in PLAUSIBLE_HEALTH_RANGE) return null

        return Estimate(
            healthPercent = ratio.coerceAtMost(100.0),
            method = HealthMethod.DISCHARGE_MEASUREMENT,
            confidence = MeasurementConfidence.LOW,
            presentFullMah = fullCapacityUah / 1000.0,
            designCapacityMah = design.capacityMah,
            designSource = design.source,
            designConfidence = design.confidence,
            confidenceReason = listOfNotNull(
                "extrapolated from a $levelDrop% drop over five minutes",
                design.caveat,
            ).joinToString(", and "),
        )
    }

    /** Confidence levels are declared weakest-last, so the weaker of two is the later one. */
    private fun weakerOf(a: MeasurementConfidence, b: MeasurementConfidence) =
        if (a.ordinal >= b.ordinal) a else b

    /** Keeps a core busy without allocating, so the drain is CPU-bound and repeatable. */
    private fun busyLoad(durationMs: Long) {
        val end = System.currentTimeMillis() + durationMs
        var acc = 1.0
        while (System.currentTimeMillis() < end) {
            repeat(20_000) { acc = (acc * 1.000001 + 0.5) % 1_000_000.0 }
        }
    }

    /**
     * Picks the most trustworthy factory capacity available, and says how much to trust it.
     *
     * Order matters commercially. `PowerProfile` used to sit second here, which was wrong: it
     * returned an identical capacity on two unrelated test phones, so at least one of those was a
     * compiled-in platform default rather than that phone's real cell. A capacity that
     * is too high inflates the apparent wear and costs the seller money, so PowerProfile is now
     * used only when something corroborates it, or when nothing else exists, and in that last case
     * it is marked low confidence so the health figure it produces cannot drive a confident verdict.
     */
    private fun resolveDesignCapacity(ctx: TestContext): DesignCapacity? {
        // The fuel gauge's own design figure is the only authoritative source.
        Sysfs.readLong(*CHARGE_FULL_DESIGN_PATHS)?.takeIf { it > 0 }?.let {
            return DesignCapacity(it / 1000.0, "sysfs charge_full_design", MeasurementConfidence.HIGH)
        }

        val spec = DeviceSpecCatalog.get(ctx.app).forCurrentDevice()
        val catalogue = spec?.designCapacityMah?.toDouble()
        val powerProfile = powerProfileCapacity(ctx.app)

        // A row someone has checked against the real hardware beats a platform value nobody can check.
        if (catalogue != null && spec.verified) {
            return DesignCapacity(
                capacityMah = catalogue,
                source = "PhoneProof device catalogue (verified against hardware)",
                confidence = MeasurementConfidence.HIGH,
            )
        }

        if (catalogue != null && powerProfile != null) {
            val disagreement = kotlin.math.abs(powerProfile - catalogue) / catalogue
            return if (disagreement <= DESIGN_CAPACITY_TOLERANCE) {
                // Two independent sources agreeing is the strongest signal available short of the
                // fuel gauge, and rules out PowerProfile having handed back a generic default.
                DesignCapacity(
                    capacityMah = catalogue,
                    source = "device catalogue, corroborated by PowerProfile " +
                        "(${Math.round(powerProfile)} mAh)",
                    confidence = MeasurementConfidence.MEDIUM,
                    caveat = null,
                )
            } else {
                DesignCapacity(
                    capacityMah = catalogue,
                    source = "device catalogue (entry not human-verified); PowerProfile reported " +
                        "${Math.round(powerProfile)} mAh and was not used",
                    confidence = MeasurementConfidence.LOW,
                    caveat = "the two available factory capacities disagree " +
                        "(${Math.round(catalogue)} mAh vs ${Math.round(powerProfile)} mAh)",
                )
            }
        }

        if (catalogue != null) {
            return DesignCapacity(
                capacityMah = catalogue,
                source = "PhoneProof device catalogue (entry not human-verified)",
                confidence = MeasurementConfidence.LOW,
                caveat = "the factory capacity comes from a catalogue entry no one has checked " +
                    "against this model",
            )
        }

        if (powerProfile != null) {
            return DesignCapacity(
                capacityMah = powerProfile,
                source = "PowerProfile platform value — nothing available to check it against",
                confidence = MeasurementConfidence.LOW,
                caveat = "the factory capacity came from PowerProfile, which on some builds " +
                    "reports a generic platform default rather than this phone's cell",
            )
        }

        return null
    }

    /**
     * `com.android.internal.os.PowerProfile` holds the design capacity the OEM compiled in. It is a
     * non-SDK interface and is blocked on newer releases, so failure here is expected rather than
     * exceptional.
     */
    private fun powerProfileCapacity(context: Context): Double? = runCatching {
        val clazz = Class.forName("com.android.internal.os.PowerProfile")
        val instance = clazz.getConstructor(Context::class.java).newInstance(context)
        val value = clazz.getMethod("getBatteryCapacity").invoke(instance) as? Double
        value?.takeIf { it > 100 }
    }.getOrNull()

    // types

    private data class Snapshot(
        val levelPercent: Int,
        val chargeCounterUah: Long?,
        val cycleCount: Int?,
        val temperatureC: Double?,
        val voltageV: Double?,
        val technology: String?,
        val osHealthLabel: String,
    )

    /**
     * A design capacity plus how much it can be trusted.
     *
     * Trust is carried alongside the number because the sources differ enormously in quality: the
     * fuel gauge's own `charge_full_design` is authoritative, whereas `PowerProfile` has been
     * observed returning one identical figure on two unrelated handsets. Every health percentage
     * computed from a design capacity inherits that source's confidence.
     */
    private data class DesignCapacity(
        val capacityMah: Double,
        val source: String,
        val confidence: MeasurementConfidence,
        /** Appended to the health figure's confidence reason when the source is not authoritative. */
        val caveat: String? = null,
    )

    private data class Estimate(
        val healthPercent: Double,
        val method: HealthMethod,
        val confidence: MeasurementConfidence,
        val presentFullMah: Double? = null,
        val designCapacityMah: Double? = null,
        val designSource: String? = null,
        val designConfidence: MeasurementConfidence = MeasurementConfidence.HIGH,
        val confidenceReason: String = "",
    ) {
        /** True when another measurement disproved this figure, so it must not drive a failure. */
        val implausible: Boolean get() = confidence == MeasurementConfidence.CONTRADICTED
    }

    enum class HealthMethod(val label: String) {
        PLATFORM_API("Platform state-of-health API"),
        SYSFS_CHARGE_FULL("charge_full ÷ charge_full_design"),
        COMPUTED_COUNTER("Charge counter ÷ charge level"),
        DISCHARGE_MEASUREMENT("Measured discharge under load"),
    }

    companion object {
        const val ID = "battery.health"

        const val KEY_HEALTH_PCT = "battery.health_pct"
        const val KEY_METHOD = "battery.method"
        const val KEY_CONFIDENCE = "battery.confidence"
        const val KEY_PRESENT_FULL_MAH = "battery.present_full_mah"
        const val KEY_DESIGN_MAH = "battery.design_mah"
        const val KEY_LEVEL = "battery.level_pct"
        const val KEY_CYCLES = "battery.cycle_count"
        const val KEY_TEMPERATURE = "battery.temperature_c"
        const val KEY_VOLTAGE = "battery.voltage_v"
        const val KEY_TECHNOLOGY = "battery.technology"
        const val KEY_OS_HEALTH = "battery.os_health"
        const val KEY_CHARGE_COUNTER = "battery.charge_counter_mah"

        /** AOSP value for `BATTERY_PROPERTY_STATE_OF_HEALTH`, which is not in the public SDK. */
        private const val STATE_OF_HEALTH_PROPERTY_ID = 10

        private const val WARN_THRESHOLD = 85.0
        private const val FAIL_THRESHOLD = 75.0
        private const val HIGH_CYCLE_COUNT = 500
        private const val LOW_CYCLE_COUNT = 150
        private const val IMPLAUSIBLE_HEALTH_FOR_LOW_CYCLES = 80.0
        private const val HOT_IDLE_TEMP_C = 40.0

        /**
         * How far two independently sourced factory capacities may differ and still corroborate
         * each other. Published capacities are typical values while compiled-in ones are often
         * rated minimums, so a few percent of spread is normal for the same cell.
         */
        private const val DESIGN_CAPACITY_TOLERANCE = 0.07
        private const val LOAD_SLICE_MS = 8_000L
        private const val LOAD_REST_MS = 2_000L

        private val PLAUSIBLE_HEALTH_RANGE = 25.0..115.0

        private val CHARGE_FULL_PATHS = arrayOf(
            "/sys/class/power_supply/battery/charge_full",
            "/sys/class/power_supply/bms/charge_full",
            "/sys/class/power_supply/battery/energy_full",
        )

        private val CHARGE_FULL_DESIGN_PATHS = arrayOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/energy_full_design",
        )

        private val CYCLE_COUNT_PATHS = arrayOf(
            "/sys/class/power_supply/battery/cycle_count",
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/battery_cycle",
        )
    }
}
