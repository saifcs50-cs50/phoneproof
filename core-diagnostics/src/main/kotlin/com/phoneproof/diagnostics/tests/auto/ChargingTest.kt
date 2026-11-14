package com.phoneproof.diagnostics.tests.auto

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Measures real charging power at the port.
 *
 * A phone that negotiates only 5 W when it should pull 25 W usually has a worn USB-C port, a
 * damaged charge IC, or a battery the system refuses to fast-charge. None of that is visible from
 * the outside, and all of it is expensive, so this is one of the highest-signal quick tests.
 *
 * The result depends on the charger and cable in use, which the report states plainly so nobody
 * reads a low figure as a verdict on the phone alone.
 */
class ChargingTest : DiagnosticTest {

    override val id = ID
    override val title = "Charging power"
    override val category = Category.BATTERY
    override val estimatedDurationMs = 12_000L

    override suspend fun run(ctx: TestContext): TestResult {
        val manager = ctx.app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return TestResult.Unsupported("BatteryManager unavailable")

        val intent = ctx.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val wirelessSupported = detectWirelessSupport(ctx)

        if (plugged == 0) {
            return TestResult.Skipped(
                SkipReason.PRECONDITION_NOT_MET,
                "Not charging. Plug in a charger and run this test again to measure charge rate.",
            )
        }

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless pad"
            else -> "Unknown source"
        }

        ctx.progress.update(0.1f, "Sampling charge current")

        val samples = mutableListOf<Double>()
        val voltages = mutableListOf<Double>()
        repeat(SAMPLE_COUNT) { index ->
            val currentMa = readCurrentMilliamps(manager)
            val voltageV = readVoltageVolts(ctx.app)
            if (currentMa != null && voltageV != null && currentMa > MIN_MEANINGFUL_MA) {
                samples += currentMa
                voltages += voltageV
            }
            ctx.progress.update(
                0.1f + 0.8f * (index + 1) / SAMPLE_COUNT,
                "Sampling ${index + 1}/$SAMPLE_COUNT",
            )
            delay(SAMPLE_INTERVAL_MS)
        }

        if (samples.isEmpty()) {
            return TestResult.Unsupported(
                "Device is charging but does not report charge current to apps. " +
                    "Some Xiaomi and Vivo builds withhold BATTERY_PROPERTY_CURRENT_NOW.",
            )
        }

        // Peak matters more than the mean: negotiation ramps, and a brief stable peak is the real
        // capability of the port and charger together.
        val peakCurrentMa = samples.max()
        val medianCurrentMa = samples.sorted()[samples.size / 2]
        val voltage = voltages.average()
        val peakWatts = peakCurrentMa / 1000.0 * voltage
        val medianWatts = medianCurrentMa / 1000.0 * voltage

        val measurements = listOf(
            Measurement.number(KEY_PEAK_WATTS, "Peak charging power", peakWatts, unit = "W", expected = "matches the phone's rated speed"),
            Measurement.number(KEY_MEDIAN_WATTS, "Sustained charging power", medianWatts, unit = "W"),
            Measurement.number(KEY_PEAK_CURRENT, "Peak charge current", peakCurrentMa, unit = "mA"),
            Measurement.number(KEY_VOLTAGE, "Charging voltage", voltage, unit = "V", note = "9 V or above indicates a fast-charge profile"),
            Measurement.text(KEY_SOURCE, "Power source", source),
            Measurement.flag(KEY_WIRELESS, "Wireless charging supported", wirelessSupported),
            Measurement.series(KEY_SERIES, "Current samples", samples, unit = "mA"),
        )

        val findings = buildList {
            if (peakWatts < SLOW_CHARGE_WATTS) {
                add(
                    Finding(
                        Severity.MAJOR,
                        "Charging at only ${format(peakWatts)} W. If the charger and cable are known " +
                            "good, suspect a worn USB-C port or a failing charge controller.",
                        KEY_PEAK_WATTS,
                    ),
                )
            } else if (peakWatts < MODEST_CHARGE_WATTS) {
                add(
                    Finding(
                        Severity.MINOR,
                        "Peak of ${format(peakWatts)} W is below what most modern handsets negotiate. " +
                            "Re-test with the phone's own charger before drawing conclusions.",
                        KEY_PEAK_WATTS,
                    ),
                )
            }

            add(
                Finding(
                    Severity.INFO,
                    "Measured with the charger and cable currently attached — a slow result can be " +
                        "the accessory rather than the phone.",
                    KEY_PEAK_WATTS,
                ),
            )
        }

        val summary = "${format(peakWatts)} W peak via $source"

        return when {
            peakWatts < SLOW_CHARGE_WATTS -> TestResult.Fail(summary, measurements, findings)
            peakWatts < MODEST_CHARGE_WATTS -> TestResult.Warn(summary, measurements, findings)
            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    /**
     * `BATTERY_PROPERTY_CURRENT_NOW` is documented as microamps, but a long tail of OEM kernels
     * report milliamps, and the sign convention for "charging" is inconsistent too. Normalising by
     * magnitude is ugly but it is the only thing that works across the market.
     */
    private fun readCurrentMilliamps(manager: BatteryManager): Double? {
        val raw = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        val magnitude = abs(raw.toDouble())
        return if (magnitude > MICROAMP_THRESHOLD) magnitude / 1000.0 else magnitude
    }

    private fun readVoltageVolts(context: Context): Double? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val millivolts = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return if (millivolts > 0) millivolts / 1000.0 else null
    }

    private fun detectWirelessSupport(ctx: TestContext): Boolean =
        ctx.app.packageManager.hasSystemFeature("android.hardware.wifi") &&
            ctx.app.packageManager.hasSystemFeature(FEATURE_WIRELESS_CHARGING)

    private fun format(value: Double) = String.format("%.1f", value)

    companion object {
        const val ID = "battery.charging"

        const val KEY_PEAK_WATTS = "charging.peak_watts"
        const val KEY_MEDIAN_WATTS = "charging.median_watts"
        const val KEY_PEAK_CURRENT = "charging.peak_current_ma"
        const val KEY_VOLTAGE = "charging.voltage_v"
        const val KEY_SOURCE = "charging.source"
        const val KEY_WIRELESS = "charging.wireless_supported"
        const val KEY_SERIES = "charging.current_series"

        private const val FEATURE_WIRELESS_CHARGING = "android.hardware.sensor.wireless_charging"
        private const val SAMPLE_COUNT = 10
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val MIN_MEANINGFUL_MA = 50.0
        private const val MICROAMP_THRESHOLD = 20_000.0
        private const val SLOW_CHARGE_WATTS = 5.0
        private const val MODEST_CHARGE_WATTS = 10.0
    }
}
