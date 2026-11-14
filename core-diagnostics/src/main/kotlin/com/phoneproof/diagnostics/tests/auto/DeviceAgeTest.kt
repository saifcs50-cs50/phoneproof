package com.phoneproof.diagnostics.tests.auto

import android.content.pm.PackageManager
import android.os.Build
import com.phoneproof.diagnostics.catalog.DeviceSpecCatalog
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Estimates how long the phone has been in service.
 *
 * There is no reliable "manufactured on" field available to apps, so this triangulates from three
 * weak signals and presents the result as an estimate with its reasoning attached. It is
 * deliberately never stated as fact: a factory reset resets the strongest signal, and the report
 * says so rather than letting a buyer over-trust it.
 */
class DeviceAgeTest : DiagnosticTest {

    override val id = ID
    override val title = "Device age estimate"
    override val category = Category.IDENTITY
    override val estimatedDurationMs = 900L

    override suspend fun run(ctx: TestContext): TestResult {
        ctx.progress.update(0.2f, "Reading firmware dates")

        val now = System.currentTimeMillis()
        val signals = mutableListOf<Signal>()

        Build.TIME.takeIf { it > MIN_PLAUSIBLE_EPOCH && it < now }?.let {
            signals += Signal("Firmware build date", it, "when this software image was compiled")
        }

        parsePatchDate()?.takeIf { it < now }?.let {
            signals += Signal("Security patch level", it, "last security update the phone accepted")
        }

        ctx.progress.update(0.5f, "Reading first app install")
        earliestUserInstall(ctx)?.takeIf { it > MIN_PLAUSIBLE_EPOCH && it < now }?.let {
            signals += Signal(
                "First app installed",
                it,
                "proxy for first setup; a factory reset resets this",
            )
        }

        val catalogue = DeviceSpecCatalog.get(ctx.app).forCurrentDevice()
        catalogue?.launchYear?.let { year ->
            val launchEpoch = yearToEpoch(year)
            if (launchEpoch < now) {
                signals += Signal(
                    "Model launch year",
                    launchEpoch,
                    "the model itself cannot be newer than this",
                )
            }
        }

        if (signals.isEmpty()) {
            return TestResult.Unsupported("No usable date signals on this device")
        }

        // The oldest credible signal bounds how long the hardware has existed; the newest bounds
        // how recently it was reset or updated. Service life is estimated from the oldest.
        val oldest = signals.minBy { it.epochMs }
        val estimatedMonths = monthsBetween(oldest.epochMs, now)

        val measurements = buildList {
            add(
                Measurement.number(
                    KEY_ESTIMATED_AGE,
                    "Estimated age",
                    estimatedMonths / 12.0,
                    unit = "years",
                    note = "from the oldest reliable signal: ${oldest.label.lowercase()}",
                ),
            )
            add(Measurement.count(KEY_AGE_MONTHS, "Estimated age", estimatedMonths, unit = "months"))
            signals.forEach { signal ->
                add(
                    Measurement.text(
                        "age.${signal.key}",
                        signal.label,
                        formatDate(signal.epochMs),
                        note = signal.explanation,
                    ),
                )
            }
            add(
                Measurement.text(
                    KEY_BASIS,
                    "Estimate basis",
                    signals.joinToString("; ") { "${it.label} ${formatDate(it.epochMs)}" },
                ),
            )
        }

        ctx.progress.update(1f, "Age estimated")

        val findings = buildList {
            add(
                Finding(
                    Severity.INFO,
                    "This is an estimate, not a manufacturing date. Android does not expose one to " +
                        "apps. A factory reset erases the strongest signal, so a reset phone can " +
                        "look newer than it is.",
                    KEY_ESTIMATED_AGE,
                ),
            )

            if (estimatedMonths > OLD_DEVICE_MONTHS) {
                add(
                    Finding(
                        Severity.MINOR,
                        "Signals point to roughly ${estimatedMonths / 12} years of service. Expect " +
                            "battery wear and no further security updates.",
                        KEY_ESTIMATED_AGE,
                    ),
                )
            }
        }

        val years = estimatedMonths / 12.0
        val summary = "Approximately ${String.format("%.1f", years)} years old (estimated)"

        return TestResult.Pass(summary, measurements, findings)
    }

    private fun parsePatchDate(): Long? {
        val patch = Build.VERSION.SECURITY_PATCH?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)?.time
        }.getOrNull()
    }

    /**
     * The earliest `firstInstallTime` among non-system packages approximates first setup, because
     * the user's first downloads happen right after the phone is unboxed.
     */
    private fun earliestUserInstall(ctx: TestContext): Long? = runCatching {
        val pm = ctx.app.packageManager
        pm.getInstalledPackages(0)
            .asSequence()
            .filter { info ->
                val flags = info.applicationInfo?.flags ?: 0
                flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 &&
                    flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            }
            .map { it.firstInstallTime }
            .filter { it > MIN_PLAUSIBLE_EPOCH }
            .minOrNull()
    }.getOrNull()

    private fun yearToEpoch(year: Int): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("$year-01-01")?.time ?: 0L
    }.getOrDefault(0L)

    private fun monthsBetween(from: Long, to: Long): Long =
        TimeUnit.MILLISECONDS.toDays(to - from) / 30

    private fun formatDate(epochMs: Long): String =
        SimpleDateFormat("MMM yyyy", Locale.US).format(Date(epochMs))

    private data class Signal(
        val label: String,
        val epochMs: Long,
        val explanation: String,
    ) {
        val key: String get() = label.lowercase().replace(' ', '_')
    }

    companion object {
        const val ID = "identity.age_estimate"

        const val KEY_ESTIMATED_AGE = "age.estimated_years"
        const val KEY_AGE_MONTHS = "age.estimated_months"
        const val KEY_BASIS = "age.basis"

        /** 2010-01-01; anything earlier is a broken clock rather than a real date. */
        private const val MIN_PLAUSIBLE_EPOCH = 1_262_304_000_000L
        private const val OLD_DEVICE_MONTHS = 48
    }
}
