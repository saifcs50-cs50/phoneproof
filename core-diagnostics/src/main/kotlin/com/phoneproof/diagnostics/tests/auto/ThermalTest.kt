package com.phoneproof.diagnostics.tests.auto

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.util.Sysfs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sustained-performance and throttling profile under a fixed CPU load.
 *
 * A phone that has been opened for a screen or battery swap very often loses its thermal pad or
 * gets a sloppy reseat, and the only symptom is that it throttles hard after a minute. Peak
 * benchmark numbers never show this; a held load does.
 *
 * Because most modern OEM builds block `/sys/class/thermal`, the primary evidence here is measured
 * throughput decay over time, which needs no privileged access. Temperatures are recorded when the
 * device will disclose them and simply omitted when it will not.
 */
class ThermalTest(
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : DiagnosticTest {

    override val id = ID
    override val title = "Thermal & sustained performance"
    override val category = Category.PERFORMANCE
    override val estimatedDurationMs = durationMs + 5_000L

    override suspend fun run(ctx: TestContext): TestResult = withContext(Dispatchers.Default) {
        val powerManager = ctx.app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_WORKERS)

        val throughput = mutableListOf<Double>()
        val temperatures = mutableListOf<Double>()
        val thermalStatuses = mutableListOf<Int>()

        val startedAt = System.currentTimeMillis()
        var slice = 0

        while (System.currentTimeMillis() - startedAt < durationMs) {
            val opsPerSecond = runLoadSlice(workers, SLICE_MS)
            throughput += opsPerSecond

            readThermalStatus(powerManager)?.let { thermalStatuses += it }
            readHottestZone()?.let { temperatures += it }

            slice++
            val elapsed = System.currentTimeMillis() - startedAt
            ctx.progress.update(
                (elapsed.toFloat() / durationMs).coerceIn(0f, 1f),
                "Holding load ${elapsed / 1000}s / ${durationMs / 1000}s",
            )
        }

        if (throughput.size < MIN_SLICES) {
            return@withContext TestResult.Unsupported(
                "Load test did not gather enough samples to judge throttling",
            )
        }

        val profile = analyse(throughput)

        val measurements = buildList {
            add(
                Measurement.number(
                    KEY_SUSTAINED_RATIO,
                    "Sustained performance",
                    profile.sustainedRatio * 100,
                    unit = "%",
                    expected = "80% or more of peak held to the end",
                    note = "final throughput as a share of peak",
                ),
            )
            add(Measurement.number(KEY_PEAK_OPS, "Peak throughput", profile.peak, unit = "ops/s"))
            add(Measurement.number(KEY_FINAL_OPS, "Throughput at end", profile.finalValue, unit = "ops/s"))
            add(Measurement.series(KEY_SERIES, "Throughput over time", throughput, unit = "ops/s"))
            add(Measurement.count(KEY_WORKERS, "Load threads", workers.toLong()))
            add(Measurement.count(KEY_DURATION, "Load duration", durationMs / 1000, unit = "s"))

            profile.throttleOnsetSeconds?.let {
                add(
                    Measurement.number(
                        KEY_THROTTLE_ONSET,
                        "Throttling began at",
                        it,
                        unit = "s",
                        expected = "no throttling, or late and gradual",
                    ),
                )
            }

            if (temperatures.isNotEmpty()) {
                add(Measurement.number(KEY_PEAK_TEMP, "Peak SoC temperature", temperatures.max(), unit = "°C", expected = "under 45 °C"))
                add(Measurement.series(KEY_TEMP_SERIES, "Temperature over time", temperatures, unit = "°C"))
            } else {
                add(
                    Measurement.text(
                        KEY_PEAK_TEMP,
                        "Peak SoC temperature",
                        "not disclosed by this device",
                        note = "thermal zones are not readable by apps on this build",
                    ),
                )
            }

            if (thermalStatuses.isNotEmpty()) {
                add(
                    Measurement.text(
                        KEY_THERMAL_STATUS,
                        "Worst thermal status",
                        thermalStatusLabel(thermalStatuses.max()),
                        expected = "None or Light",
                    ),
                )
            }
        }

        val findings = buildList {
            when {
                profile.sustainedRatio < SEVERE_THROTTLE_RATIO -> add(
                    Finding(
                        Severity.MAJOR,
                        "Performance collapsed to ${(profile.sustainedRatio * 100).toInt()}% of peak " +
                            "under a ${durationMs / 1000}-second load" +
                            (profile.throttleOnsetSeconds?.let { ", starting after ${it.toInt()}s" } ?: "") +
                            ". On a phone that has been opened before, this usually means the thermal " +
                            "pad was not reseated properly.",
                        KEY_SUSTAINED_RATIO,
                    ),
                )

                profile.sustainedRatio < MODERATE_THROTTLE_RATIO -> add(
                    Finding(
                        Severity.MINOR,
                        "Throughput fell to ${(profile.sustainedRatio * 100).toInt()}% of peak. Some " +
                            "throttling is normal; this is on the heavy side.",
                        KEY_SUSTAINED_RATIO,
                    ),
                )
            }

            temperatures.maxOrNull()?.let { peak ->
                if (peak > HOT_TEMP_C) {
                    add(
                        Finding(
                            Severity.MINOR,
                            "SoC reached ${peak.toInt()} °C under load.",
                            KEY_PEAK_TEMP,
                        ),
                    )
                }
            }

            thermalStatuses.maxOrNull()?.let { worst ->
                if (worst >= THERMAL_STATUS_SEVERE) {
                    add(
                        Finding(
                            Severity.MAJOR,
                            "The system reported '${thermalStatusLabel(worst)}' thermal pressure, at " +
                                "which Android actively sheds performance to protect the hardware.",
                            KEY_THERMAL_STATUS,
                        ),
                    )
                }
            }
        }

        val summary = "Held ${(profile.sustainedRatio * 100).toInt()}% of peak over ${durationMs / 1000}s"

        when {
            findings.any { it.severity == Severity.MAJOR } -> TestResult.Fail(summary, measurements, findings)
            findings.any { it.severity == Severity.MINOR } -> TestResult.Warn(summary, measurements, findings)
            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    /** Runs [workers] busy threads for [sliceMs] and returns aggregate operations per second. */
    private suspend fun runLoadSlice(workers: Int, sliceMs: Long): Double = coroutineScope {
        val deadline = System.currentTimeMillis() + sliceMs
        val counts = (0 until workers).map {
            async(Dispatchers.Default) { burn(deadline) }
        }.awaitAll()
        val totalOps = counts.sum()
        totalOps / (sliceMs / 1000.0)
    }

    /**
     * A fixed mixed integer/float kernel. It deliberately avoids allocation so the measurement
     * tracks CPU clock behaviour rather than garbage collection.
     */
    private fun burn(deadlineMs: Long): Double {
        var operations = 0.0
        var acc = 1.0
        var counter = 0L
        while (System.currentTimeMillis() < deadlineMs) {
            repeat(BATCH_SIZE) {
                counter = counter * 6364136223846793005L + 1442695040888963407L
                acc = (acc * 1.0000001 + (counter ushr 33).toDouble() * 1e-12) % 1_000_000.0
            }
            operations += BATCH_SIZE
        }
        return operations
    }

    private fun analyse(series: List<Double>): Profile {
        val peak = series.max()
        val tailSize = (series.size / 5).coerceAtLeast(1)
        val finalValue = series.takeLast(tailSize).average()
        val sustainedRatio = if (peak <= 0) 0.0 else (finalValue / peak).coerceIn(0.0, 1.0)

        // First slice that drops below the throttle line and stays there.
        val threshold = peak * MODERATE_THROTTLE_RATIO
        val onsetIndex = series.indexOfFirst { it < threshold }
            .takeIf { it >= 0 && series.drop(it).all { value -> value < peak * RECOVERY_RATIO } }

        return Profile(
            peak = peak,
            finalValue = finalValue,
            sustainedRatio = sustainedRatio,
            throttleOnsetSeconds = onsetIndex?.let { it * (SLICE_MS / 1000.0) },
        )
    }

    private fun readThermalStatus(powerManager: PowerManager?): Int? {
        if (powerManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { powerManager.currentThermalStatus }.getOrNull()
    }

    /** Highest plausible reading across all readable thermal zones, in °C. */
    private fun readHottestZone(): Double? {
        val readings = Sysfs.glob(THERMAL_ROOT, "thermal_zone").mapNotNull { zone ->
            val raw = Sysfs.readLong(File(zone, "temp").absolutePath) ?: return@mapNotNull null
            // Kernels report millidegrees, decidegrees or degrees depending on the driver.
            val celsius = when {
                raw > 10_000 -> raw / 1000.0
                raw > 1_000 -> raw / 100.0
                raw > 200 -> raw / 10.0
                else -> raw.toDouble()
            }
            celsius.takeIf { it in PLAUSIBLE_TEMP_RANGE }
        }
        return readings.maxOrNull()
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "None"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent"
        else -> "Unknown"
    }

    private data class Profile(
        val peak: Double,
        val finalValue: Double,
        val sustainedRatio: Double,
        val throttleOnsetSeconds: Double?,
    )

    companion object {
        const val ID = "performance.thermal"

        const val KEY_SUSTAINED_RATIO = "thermal.sustained_ratio_pct"
        const val KEY_PEAK_OPS = "thermal.peak_ops"
        const val KEY_FINAL_OPS = "thermal.final_ops"
        const val KEY_SERIES = "thermal.throughput_series"
        const val KEY_TEMP_SERIES = "thermal.temperature_series"
        const val KEY_PEAK_TEMP = "thermal.peak_temp_c"
        const val KEY_THERMAL_STATUS = "thermal.worst_status"
        const val KEY_THROTTLE_ONSET = "thermal.throttle_onset_s"
        const val KEY_WORKERS = "thermal.workers"
        const val KEY_DURATION = "thermal.duration_s"

        private const val DEFAULT_DURATION_MS = 180_000L
        private const val SLICE_MS = 3_000L
        private const val BATCH_SIZE = 50_000
        private const val MAX_WORKERS = 8
        private const val MIN_SLICES = 4
        private const val SEVERE_THROTTLE_RATIO = 0.60
        private const val MODERATE_THROTTLE_RATIO = 0.80
        private const val RECOVERY_RATIO = 0.90
        private const val HOT_TEMP_C = 45.0
        private const val THERMAL_STATUS_SEVERE = 3
        private const val THERMAL_ROOT = "/sys/class/thermal"

        private val PLAUSIBLE_TEMP_RANGE = 5.0..120.0
    }
}
