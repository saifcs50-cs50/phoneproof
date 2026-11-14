package com.phoneproof.diagnostics.tests.auto

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.phoneproof.diagnostics.catalog.DeviceSpecCatalog
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult

/**
 * Enumerates the sensor hardware the phone exposes and flags anything a handset of this class
 * should have but does not.
 *
 * A missing gyroscope or magnetometer on a phone claiming to be a flagship is a strong counterfeit
 * signal; a missing proximity sensor on a genuine phone usually means a third-party screen was
 * fitted and the sensor was never reconnected — which the owner discovers when the display stops
 * blanking during calls.
 */
class SensorInventoryTest : DiagnosticTest {

    override val id = ID
    override val title = "Sensor inventory"
    override val category = Category.SENSORS
    override val estimatedDurationMs = 600L

    override suspend fun run(ctx: TestContext): TestResult {
        val manager = ctx.app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return TestResult.Unsupported("SensorManager unavailable")

        ctx.progress.update(0.3f, "Enumerating sensors")
        val sensors = manager.getSensorList(Sensor.TYPE_ALL).orEmpty()

        if (sensors.isEmpty()) {
            return TestResult.Fail(
                summary = "No sensors reported at all",
                measurements = listOf(Measurement.count(KEY_TOTAL, "Sensors found", 0)),
                findings = listOf(
                    Finding(
                        Severity.CRITICAL,
                        "The device reports zero sensors. On a real phone this is impossible — " +
                            "expect a heavily modified ROM or an emulator.",
                        KEY_TOTAL,
                    ),
                ),
            )
        }

        val presentTypes = sensors.map { it.type }.toSet()
        val missing = ESSENTIAL_SENSORS.filter { it.type !in presentTypes }

        val measurements = buildList {
            add(Measurement.count(KEY_TOTAL, "Sensors found", sensors.size.toLong()))
            add(
                Measurement.table(
                    KEY_LIST,
                    "Sensor list",
                    sensors
                        .sortedBy { it.name }
                        .map { MeasuredValue.Table.Row(it.name, it.vendor.orEmpty().ifBlank { "—" }) },
                ),
            )
            ESSENTIAL_SENSORS.forEach { expected ->
                add(
                    Measurement.flag(
                        "sensors.has_${expected.key}",
                        expected.label,
                        expected.type in presentTypes,
                        expected = "Yes",
                    ),
                )
            }
            add(
                Measurement.text(
                    KEY_MISSING,
                    "Missing essentials",
                    missing.joinToString(", ") { it.label }.ifBlank { "none" },
                ),
            )
        }

        ctx.progress.update(1f, "${sensors.size} sensors found")

        val catalogueEntry = DeviceSpecCatalog.get(ctx.app).forCurrentDevice()

        // Without a reference row we cannot distinguish "this phone lost its gyroscope" from "this
        // model never had one". Plenty of budget handsets genuinely ship without a gyroscope or
        // magnetometer, so calling that a fault would fail honest phones — report it, do not judge it.
        val haveReference = catalogueEntry != null

        val findings = missing.map { absent ->
            Finding(
                severity = if (haveReference) absent.severity else Severity.INFO,
                message = if (!haveReference) {
                    "${absent.label} is not present. PhoneProof has no reference specification for " +
                        "${android.os.Build.MODEL}, so it cannot tell whether this model shipped " +
                        "without one — which is common on budget handsets — or whether it has " +
                        "failed. Add this model to the catalogue to turn this into a real check."
                } else when (absent.type) {
                    Sensor.TYPE_PROXIMITY ->
                        "No proximity sensor. Either this model uses a virtual proximity solution, " +
                            "or a replacement screen was fitted without reconnecting it — the screen " +
                            "will then stay lit against your ear during calls."

                    Sensor.TYPE_GYROSCOPE ->
                        "No gyroscope. Genuine mid-range and flagship phones all have one; its " +
                            "absence is a common tell on counterfeit hardware and breaks camera " +
                            "stabilisation and most games."

                    Sensor.TYPE_MAGNETIC_FIELD ->
                        "No magnetometer — the compass will not work in navigation apps."

                    else -> "${absent.label} is not present on this device."
                },
                measurementKey = "sensors.has_${absent.key}",
            )
        }.toMutableList()

        if (catalogueEntry != null && missing.isNotEmpty()) {
            findings += Finding(
                Severity.INFO,
                "Compared against the catalogue entry for ${catalogueEntry.displayName}.",
                KEY_MISSING,
            )
        }

        val summary = when {
            missing.isEmpty() -> "${sensors.size} sensors present, no essentials missing"
            !haveReference ->
                "${sensors.size} sensors · ${missing.joinToString(", ") { it.label }} not present " +
                    "(no reference to compare against)"
            else -> "${sensors.size} sensors · missing ${missing.joinToString(", ") { it.label }}"
        }

        return when {
            findings.any { it.severity == Severity.MAJOR || it.severity == Severity.CRITICAL } ->
                TestResult.Fail(summary, measurements, findings)

            findings.any { it.severity == Severity.MINOR } ->
                TestResult.Warn(summary, measurements, findings)

            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    private data class ExpectedSensor(
        val type: Int,
        val key: String,
        val label: String,
        val severity: Severity,
    )

    companion object {
        const val ID = "sensors.inventory"

        const val KEY_TOTAL = "sensors.total"
        const val KEY_LIST = "sensors.list"
        const val KEY_MISSING = "sensors.missing"

        /**
         * Severity reflects how suspicious the absence is, not how important the sensor is.
         * Barometers and thermometers are genuinely optional, so they are not listed at all.
         */
        private val ESSENTIAL_SENSORS = listOf(
            ExpectedSensor(Sensor.TYPE_ACCELEROMETER, "accelerometer", "Accelerometer", Severity.MAJOR),
            ExpectedSensor(Sensor.TYPE_GYROSCOPE, "gyroscope", "Gyroscope", Severity.MAJOR),
            ExpectedSensor(Sensor.TYPE_MAGNETIC_FIELD, "magnetometer", "Magnetometer", Severity.MINOR),
            ExpectedSensor(Sensor.TYPE_LIGHT, "light", "Ambient light sensor", Severity.MINOR),
            ExpectedSensor(Sensor.TYPE_PROXIMITY, "proximity", "Proximity sensor", Severity.MINOR),
        )
    }
}
