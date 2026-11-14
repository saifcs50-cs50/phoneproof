package com.phoneproof.diagnostics.tests.auto

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import com.phoneproof.diagnostics.catalog.DeviceSpec
import com.phoneproof.diagnostics.catalog.DeviceSpecCatalog
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compares what the phone *claims* to be against what its hardware actually reports.
 *
 * Counterfeit and re-badged handsets are everywhere in the Indian grey market, and they are built
 * to survive exactly one check: reading the About Phone screen, which is trivially faked because
 * `Build.MODEL` is just a string in a config file. Physical properties — panel resolution, real
 * RAM, camera sensor sizes, SoC identity — are far harder to fake convincingly.
 *
 * A mismatch is reported as a mismatch, never as an accusation: legitimate regional variants exist,
 * so every discrepancy names the specific field and both values and lets the reader judge.
 */
class CounterfeitTest : DiagnosticTest {

    override val id = ID
    override val title = "Counterfeit / spec verification"
    override val category = Category.INTEGRITY
    override val estimatedDurationMs = 1_500L

    override val dependsOn = listOf(IdentityTest.ID, SensorInventoryTest.ID, DisplayCapabilityTest.ID)

    override suspend fun run(ctx: TestContext): TestResult {
        val identity = ctx.recordOf(IdentityTest.ID)
            ?: return TestResult.Unsupported("Identity test did not run, so nothing to verify against")

        ctx.progress.update(0.2f, "Reading camera hardware")
        val cameras = enumerateCameras(ctx.app)

        val claimedModel = identity.text(IdentityTest.KEY_MODEL) ?: Build.MODEL
        val spec = DeviceSpecCatalog.get(ctx.app).forCurrentDevice()

        ctx.progress.update(0.6f, "Comparing against reference specification")

        val measured = MeasuredReality(
            ramGb = identity.numeric(IdentityTest.KEY_RAM_GB),
            storageGb = identity.numeric(IdentityTest.KEY_STORAGE_TOTAL_GB),
            soc = identity.text(IdentityTest.KEY_SOC),
            rearCameras = cameras.count { it.facing == "Rear" },
            frontCameras = cameras.count { it.facing == "Front" },
        )

        val baseMeasurements = buildList {
            add(Measurement.text(KEY_CLAIMED_MODEL, "Model claimed by the phone", claimedModel))
            add(Measurement.count(KEY_CAMERA_COUNT, "Camera sensors found", cameras.size.toLong()))
            add(
                Measurement.table(
                    KEY_CAMERA_LIST,
                    "Camera hardware",
                    cameras.map {
                        MeasuredValue.Table.Row(
                            "${it.facing} camera ${it.id}",
                            "${it.megapixels?.let { mp -> "%.1f MP".format(mp) } ?: "unknown"}",
                        )
                    },
                ),
            )
            measured.ramGb?.let { add(Measurement.number(KEY_MEASURED_RAM, "Measured RAM", it, unit = "GB")) }
            measured.storageGb?.let { add(Measurement.number(KEY_MEASURED_STORAGE, "Measured storage", it, unit = "GB")) }
        }

        if (spec == null) {
            return TestResult.Unsupported(
                "No reference specification for '$claimedModel' in the PhoneProof catalogue, so its " +
                    "claims cannot be verified. Add this model to the catalogue to enable the check.",
            )
        }

        val comparisons = compare(spec, measured, ctx.recordOf(DisplayCapabilityTest.ID))

        val measurements = baseMeasurements + buildList {
            add(Measurement.text(KEY_REFERENCE, "Reference specification", spec.displayName))
            comparisons.forEach { comparison ->
                add(
                    Measurement.text(
                        "counterfeit.${comparison.field}",
                        comparison.label,
                        "claimed ${comparison.expected} · measured ${comparison.actual}",
                        expected = comparison.expected,
                        note = if (comparison.matches) "matches" else "MISMATCH",
                    ),
                )
            }
            if (!spec.verified) {
                add(
                    Measurement.flag(
                        KEY_REFERENCE_VERIFIED,
                        "Reference entry human-verified",
                        false,
                        note = "catalogue row has not been reviewed; treat mismatches cautiously",
                    ),
                )
            }
        }

        ctx.progress.update(1f, "Specification compared")

        val mismatches = comparisons.filter { !it.matches }

        val findings = buildList {
            mismatches.forEach { mismatch ->
                add(
                    Finding(
                        severity = mismatch.severity,
                        message = "${mismatch.label}: the phone claims to be a ${spec.marketingName}, " +
                            "which ships with ${mismatch.expected}, but this unit reports " +
                            "${mismatch.actual}.",
                        measurementKey = "counterfeit.${mismatch.field}",
                    ),
                )
            }

            if (mismatches.size >= MANY_MISMATCHES) {
                add(
                    Finding(
                        Severity.CRITICAL,
                        "${mismatches.size} separate hardware claims do not match the real " +
                            "${spec.marketingName}. This pattern is what a re-badged counterfeit looks " +
                            "like. Do not buy without an independent physical inspection.",
                        KEY_CLAIMED_MODEL,
                    ),
                )
            }

            if (!spec.verified) {
                add(
                    Finding(
                        Severity.INFO,
                        "The reference row for this model has not been human-verified, so a mismatch " +
                            "may be a catalogue error rather than a fake phone.",
                        KEY_REFERENCE_VERIFIED,
                    ),
                )
            }
        }

        val summary = if (mismatches.isEmpty()) {
            "Hardware matches the reference ${spec.marketingName}"
        } else {
            "${mismatches.size} mismatch(es) against the reference ${spec.marketingName}"
        }

        return when {
            findings.any { it.severity == Severity.CRITICAL || it.severity == Severity.MAJOR } ->
                TestResult.Fail(summary, measurements, findings)

            mismatches.isNotEmpty() -> TestResult.Warn(summary, measurements, findings)

            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    private fun compare(
        spec: DeviceSpec,
        measured: MeasuredReality,
        display: com.phoneproof.diagnostics.model.TestRecord?,
    ): List<Comparison> = buildList {
        // RAM is reported slightly below the marketed figure because the kernel reserves some, so
        // the nearest advertised option within tolerance counts as a match.
        if (spec.ramGb.isNotEmpty() && measured.ramGb != null) {
            val nearest = spec.ramGb.minByOrNull { abs(it - measured.ramGb) }!!
            add(
                Comparison(
                    field = "ram",
                    label = "RAM",
                    expected = spec.ramGb.joinToString("/") { "$it GB" },
                    actual = "%.1f GB".format(measured.ramGb),
                    matches = abs(nearest - measured.ramGb) <= nearest * RAM_TOLERANCE,
                    severity = Severity.MAJOR,
                ),
            )
        }

        if (spec.storageGb.isNotEmpty() && measured.storageGb != null) {
            // The data partition is always smaller than nominal capacity; compare generously.
            val plausible = spec.storageGb.any { nominal ->
                measured.storageGb <= nominal && measured.storageGb >= nominal * STORAGE_MIN_RATIO
            }
            add(
                Comparison(
                    field = "storage",
                    label = "Storage",
                    expected = spec.storageGb.joinToString("/") { "$it GB" },
                    actual = "%.0f GB usable".format(measured.storageGb),
                    matches = plausible,
                    severity = Severity.MAJOR,
                ),
            )
        }

        val width = display?.numeric(DisplayCapabilityTest.KEY_WIDTH)?.roundToInt()
        val height = display?.numeric(DisplayCapabilityTest.KEY_HEIGHT)?.roundToInt()
        if (spec.screenWidth != null && spec.screenHeight != null && width != null && height != null) {
            // Compare orientation-independently. The reported metrics follow the current rotation,
            // so a landscape reading of 3120x1440 is the same panel as a portrait 1440x3120 — and
            // treating that as a mismatch would falsely accuse a genuine phone of being a fake.
            val measuredPair = listOf(width, height).sorted()
            val expectedPair = listOf(spec.screenWidth, spec.screenHeight).sorted()
            add(
                Comparison(
                    field = "resolution",
                    label = "Screen resolution",
                    expected = "${spec.screenWidth}×${spec.screenHeight}",
                    actual = "${width}×${height}",
                    matches = measuredPair == expectedPair,
                    severity = Severity.MAJOR,
                ),
            )
        }

        if (spec.soc != null && measured.soc != null) {
            val normalisedExpected = spec.soc.lowercase().replace(" ", "")
            val normalisedActual = measured.soc.lowercase().replace(" ", "")
            add(
                Comparison(
                    field = "soc",
                    label = "Chipset",
                    expected = spec.soc,
                    actual = measured.soc,
                    matches = normalisedActual.contains(normalisedExpected) ||
                        normalisedExpected.contains(normalisedActual),
                    severity = Severity.CRITICAL,
                ),
            )
        }

        spec.rearCameras?.let { expected ->
            add(
                Comparison(
                    field = "rear_cameras",
                    label = "Rear cameras",
                    expected = "at least $expected",
                    actual = "${measured.rearCameras}",
                    matches = measured.rearCameras >= expected,
                    severity = Severity.MAJOR,
                ),
            )
        }

        spec.frontCameras?.let { expected ->
            add(
                Comparison(
                    field = "front_cameras",
                    label = "Front cameras",
                    expected = "at least $expected",
                    actual = "${measured.frontCameras}",
                    matches = measured.frontCameras >= expected,
                    severity = Severity.MINOR,
                ),
            )
        }
    }

    /**
     * Camera enumeration needs no runtime permission, which makes it a rare piece of hard evidence
     * available before the user has agreed to anything.
     */
    private fun enumerateCameras(context: Context): List<CameraSummary> = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.map { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                else -> "External"
            }
            CameraSummary(id = id, facing = facing, megapixels = largestCaptureMegapixels(characteristics))
        }
    }.getOrDefault(emptyList())

    private fun largestCaptureMegapixels(characteristics: CameraCharacteristics): Double? {
        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (pixelArray != null && pixelArray.width > 0) {
            return pixelArray.width.toDouble() * pixelArray.height / 1_000_000.0
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            as? StreamConfigurationMap ?: return null
        val largest = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            ?: return null
        return largest.width.toDouble() * largest.height / 1_000_000.0
    }

    private data class CameraSummary(val id: String, val facing: String, val megapixels: Double?)

    private data class MeasuredReality(
        val ramGb: Double?,
        val storageGb: Double?,
        val soc: String?,
        val rearCameras: Int,
        val frontCameras: Int,
    )

    private data class Comparison(
        val field: String,
        val label: String,
        val expected: String,
        val actual: String,
        val matches: Boolean,
        val severity: Severity,
    )

    companion object {
        const val ID = "integrity.counterfeit"

        const val KEY_CLAIMED_MODEL = "counterfeit.claimed_model"
        const val KEY_REFERENCE = "counterfeit.reference"
        const val KEY_REFERENCE_VERIFIED = "counterfeit.reference_verified"
        const val KEY_CAMERA_COUNT = "counterfeit.camera_count"
        const val KEY_CAMERA_LIST = "counterfeit.camera_list"
        const val KEY_MEASURED_RAM = "counterfeit.measured_ram_gb"
        const val KEY_MEASURED_STORAGE = "counterfeit.measured_storage_gb"

        private const val RAM_TOLERANCE = 0.25
        private const val STORAGE_MIN_RATIO = 0.80
        private const val MANY_MISMATCHES = 3
    }
}
