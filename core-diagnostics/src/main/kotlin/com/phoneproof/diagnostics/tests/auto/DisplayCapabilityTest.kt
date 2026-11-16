package com.phoneproof.diagnostics.tests.auto

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Advertised versus measured display capability.
 *
 * Replacement panels are the single most common repair on a second-hand phone, and a cheap one
 * frequently cannot sustain the refresh rate the original did; the phone still *reports* 120 Hz
 * because that comes from the system config, while the panel actually delivers 60. Comparing
 * `Display.getSupportedModes()` against frame timings measured through Choreographer is what
 * separates the two.
 */
class DisplayCapabilityTest : DiagnosticTest {

    override val id = ID
    override val title = "Display capability"
    override val category = Category.DISPLAY
    override val estimatedDurationMs = 4_000L

    override suspend fun run(ctx: TestContext): TestResult {
        val displayManager = ctx.app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return TestResult.Unsupported("DisplayManager unavailable")

        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return TestResult.Unsupported("No default display")

        ctx.progress.update(0.15f, "Reading display modes")

        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        val modes = display.supportedModes.orEmpty()
        val advertisedMaxHz = modes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        val activeModeHz = display.mode?.refreshRate ?: display.refreshRate

        ctx.progress.update(0.35f, "Measuring real frame timing")
        val measuredHz = measureRefreshRate()
        val foreground = isForeground(ctx.app)

        val hdrLabel = describeHdr(display)
        val diagonalInches = estimateDiagonalInches(metrics)

        val measurements = buildList {
            add(Measurement.count(KEY_WIDTH, "Resolution width", metrics.widthPixels.toLong(), unit = "px"))
            add(Measurement.count(KEY_HEIGHT, "Resolution height", metrics.heightPixels.toLong(), unit = "px"))
            add(Measurement.count(KEY_DENSITY_DPI, "Density", metrics.densityDpi.toLong(), unit = "dpi"))
            diagonalInches?.let {
                add(Measurement.number(KEY_DIAGONAL, "Screen size (estimated)", it, unit = "in"))
            }
            add(
                Measurement.number(
                    KEY_ADVERTISED_HZ,
                    "Advertised refresh rate",
                    advertisedMaxHz.toDouble(),
                    unit = "Hz",
                    note = "highest mode the system reports",
                ),
            )
            add(Measurement.number(KEY_ACTIVE_HZ, "Active mode", activeModeHz.toDouble(), unit = "Hz"))

            if (measuredHz != null) {
                add(
                    Measurement.number(
                        KEY_MEASURED_HZ,
                        "Measured refresh rate",
                        measuredHz,
                        unit = "Hz",
                        expected = "within 10% of the advertised rate",
                        note = if (foreground) {
                            "from Choreographer frame callbacks"
                        } else {
                            "indicative only — the app was not in the foreground, and Android " +
                                "drives vsync at 60 Hz for background windows regardless of the panel"
                        },
                    ),
                )
            }

            add(
                Measurement.table(
                    KEY_MODES,
                    "Supported modes",
                    modes.map {
                        MeasuredValue.Table.Row(
                            "${it.physicalWidth}×${it.physicalHeight}",
                            "${it.refreshRate.roundToInt()} Hz",
                        )
                    },
                ),
            )
            add(Measurement.text(KEY_HDR, "HDR support", hdrLabel))
        }

        ctx.progress.update(1f, "Display measured")

        val findings = buildList {
            if (measuredHz != null && advertisedMaxHz > LOW_REFRESH_CEILING) {
                val ratio = measuredHz / activeModeHz
                if (ratio < REFRESH_SHORTFALL_RATIO) {
                    // Choreographer only reports true panel vsync when this app owns a visible
                    // window. In the background the compositor clocks callbacks at 60 Hz on a
                    // perfectly healthy 90/120 Hz panel, so accusing the display of being a cheap
                    // replacement here would fail genuine phones.
                    add(
                        if (foreground) {
                            Finding(
                                Severity.MAJOR,
                                "Panel delivered only ${measuredHz.roundToInt()} Hz while the system " +
                                    "is set to ${activeModeHz.roundToInt()} Hz. A replacement panel " +
                                    "that cannot sustain the original refresh rate is the usual cause.",
                                KEY_MEASURED_HZ,
                            )
                        } else {
                            Finding(
                                Severity.INFO,
                                "Frame timing was sampled while the app was in the background, where " +
                                    "Android clocks callbacks at 60 Hz regardless of the panel. Re-run " +
                                    "this test with PhoneProof on screen to measure the display properly.",
                                KEY_MEASURED_HZ,
                            )
                        },
                    )
                }
            }

            if (measuredHz == null) {
                add(
                    Finding(
                        Severity.INFO,
                        "Frame timing could not be sampled; only the advertised mode list is reported.",
                        KEY_ADVERTISED_HZ,
                    ),
                )
            }
        }

        val summary = buildString {
            append("${metrics.widthPixels}×${metrics.heightPixels}")
            append(" · ${activeModeHz.roundToInt()} Hz")
            measuredHz?.let { append(" (measured ${it.roundToInt()} Hz)") }
        }

        return when {
            findings.any { it.severity == Severity.MAJOR } -> TestResult.Fail(summary, measurements, findings)
            findings.any { it.severity == Severity.MINOR } -> TestResult.Warn(summary, measurements, findings)
            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    /**
     * Counts Choreographer callbacks over a fixed window. Each callback corresponds to one vsync,
     * so the callback rate is the rate the compositor is actually driving the panel at.
     */
    private suspend fun measureRefreshRate(): Double? = withContext(Dispatchers.Main) {
        runCatching {
            suspendCancellableCoroutine { continuation ->
                val choreographer = Choreographer.getInstance()
                val frameTimestamps = ArrayList<Long>(MAX_FRAMES)
                val deadline = System.nanoTime() + SAMPLE_WINDOW_NANOS

                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        frameTimestamps += frameTimeNanos
                        if (frameTimeNanos < deadline && frameTimestamps.size < MAX_FRAMES) {
                            choreographer.postFrameCallback(this)
                        } else if (continuation.isActive) {
                            continuation.resume(computeHz(frameTimestamps))
                        }
                    }
                }

                choreographer.postFrameCallback(callback)
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            }
        }.getOrNull()
    }

    /**
     * Uses the median frame interval rather than the mean: a single dropped frame during startup
     * would otherwise drag the average down and produce a false throttling claim.
     */
    private fun computeHz(timestamps: List<Long>): Double? {
        if (timestamps.size < MIN_FRAMES) return null
        val intervals = timestamps.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (intervals.isEmpty()) return null
        val median = intervals[intervals.size / 2]
        return 1_000_000_000.0 / median
    }

    /**
     * True when this process owns a visible window.
     *
     * The refresh-rate comparison is only meaningful in the foreground, so this decides whether a
     * shortfall is evidence about the panel or an artefact of how the test was invoked.
     */
    private fun isForeground(context: Context): Boolean = runCatching {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun describeHdr(display: Display): String {
        val capabilities = runCatching { display.hdrCapabilities }.getOrNull()
        val types = capabilities?.supportedHdrTypes?.toList().orEmpty()
        if (types.isEmpty()) return "None reported"
        return types.joinToString(", ") { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "Type $type"
            }
        }
    }

    /** Physical size from pixel count and true DPI. Approximate; panels round their reported DPI. */
    private fun estimateDiagonalInches(metrics: DisplayMetrics): Double? {
        if (metrics.xdpi <= 0 || metrics.ydpi <= 0) return null
        val widthInches = metrics.widthPixels / metrics.xdpi
        val heightInches = metrics.heightPixels / metrics.ydpi
        val diagonal = kotlin.math.sqrt(widthInches * widthInches + heightInches * heightInches)
        return diagonal.toDouble().takeIf { it in 3.0..15.0 }
    }

    companion object {
        const val ID = "display.capability"

        const val KEY_WIDTH = "display.width_px"
        const val KEY_HEIGHT = "display.height_px"
        const val KEY_DENSITY_DPI = "display.density_dpi"
        const val KEY_DIAGONAL = "display.diagonal_in"
        const val KEY_ADVERTISED_HZ = "display.advertised_hz"
        const val KEY_ACTIVE_HZ = "display.active_hz"
        const val KEY_MEASURED_HZ = "display.measured_hz"
        const val KEY_MODES = "display.modes"
        const val KEY_HDR = "display.hdr"

        private const val SAMPLE_WINDOW_NANOS = 2_000_000_000L
        private const val MAX_FRAMES = 400
        private const val MIN_FRAMES = 10
        private const val REFRESH_SHORTFALL_RATIO = 0.85
        private const val LOW_REFRESH_CEILING = 65f
    }
}
