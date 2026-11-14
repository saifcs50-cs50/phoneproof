package com.phoneproof.diagnostics.tests.auto

import android.Manifest
import android.content.Context
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Acquires a satellite fix and reports time-to-first-fix and satellite count.
 *
 * GPS failure is usually antenna damage from a drop or a careless repair. A phone that sees plenty
 * of satellites but never fixes is a different fault from one that sees none at all, so both
 * numbers are reported rather than a bare pass/fail.
 */
class GpsTest : DiagnosticTest {

    override val id = ID
    override val title = "GPS / satellite fix"
    override val category = Category.CONNECTIVITY
    override val estimatedDurationMs = 45_000L

    override val requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override val permissionRationale =
        "Needed to acquire a satellite fix. Your location is measured on-device for this test and never leaves the phone or gets stored in the report."

    override suspend fun run(ctx: TestContext): TestResult {
        val manager = ctx.app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return TestResult.Unsupported("LocationManager unavailable")

        val providerEnabled = runCatching {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)

        if (!providerEnabled) {
            return TestResult.Skipped(
                SkipReason.PRECONDITION_NOT_MET,
                "Location is switched off. Enable it and re-run to test GPS.",
            )
        }

        ctx.progress.update(0.05f, "Waiting for satellites — best near a window")

        val outcome = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            acquireFix(ctx, manager)
        }

        if (outcome == null) {
            return TestResult.Fail(
                summary = "No fix within ${FIX_TIMEOUT_MS / 1000} seconds",
                measurements = listOf(
                    Measurement.flag(KEY_FIX_ACQUIRED, "Fix acquired", false, expected = "Yes"),
                    Measurement.count(KEY_TIMEOUT, "Search time", FIX_TIMEOUT_MS / 1000, unit = "s"),
                ),
                findings = listOf(
                    Finding(
                        Severity.MAJOR,
                        "GPS did not acquire a fix in ${FIX_TIMEOUT_MS / 1000} seconds. Retry outdoors " +
                            "before concluding — but indoors-only failure plus a low satellite count " +
                            "usually means antenna damage.",
                        KEY_FIX_ACQUIRED,
                    ),
                ),
            )
        }

        val measurements = buildList {
            add(Measurement.flag(KEY_FIX_ACQUIRED, "Fix acquired", true, expected = "Yes"))
            add(
                Measurement.number(
                    KEY_TTFF,
                    "Time to first fix",
                    outcome.timeToFixMs / 1000.0,
                    unit = "s",
                    expected = "under 30 s with a clear sky view",
                ),
            )
            add(
                Measurement.count(
                    KEY_SATELLITES_USED,
                    "Satellites used in fix",
                    outcome.satellitesUsed.toLong(),
                    expected = "4 or more",
                ),
            )
            add(Measurement.count(KEY_SATELLITES_SEEN, "Satellites visible", outcome.satellitesSeen.toLong()))
            outcome.accuracyMeters?.let {
                add(Measurement.number(KEY_ACCURACY, "Reported accuracy", it.toDouble(), unit = "m"))
            }
            outcome.constellations.takeIf { it.isNotEmpty() }?.let {
                add(Measurement.text(KEY_CONSTELLATIONS, "Constellations", it.joinToString(", ")))
            }
        }

        ctx.progress.update(1f, "Fix acquired")

        val findings = buildList {
            if (outcome.timeToFixMs > SLOW_FIX_MS) {
                add(
                    Finding(
                        Severity.MINOR,
                        "Took ${outcome.timeToFixMs / 1000} seconds to fix. Slow, though indoor " +
                            "testing accounts for much of that.",
                        KEY_TTFF,
                    ),
                )
            }
            if (outcome.satellitesUsed < MIN_HEALTHY_SATELLITES) {
                add(
                    Finding(
                        Severity.MINOR,
                        "Only ${outcome.satellitesUsed} satellites contributed to the fix.",
                        KEY_SATELLITES_USED,
                    ),
                )
            }
        }

        val summary = "Fix in ${outcome.timeToFixMs / 1000}s using ${outcome.satellitesUsed} satellites"

        return if (findings.any { it.severity == Severity.MINOR }) {
            TestResult.Warn(summary, measurements, findings)
        } else {
            TestResult.Pass(summary, measurements, findings)
        }
    }

    private suspend fun acquireFix(ctx: TestContext, manager: LocationManager): FixOutcome =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val startedAt = System.currentTimeMillis()
                var satellitesSeen = 0
                var satellitesUsed = 0
                val constellations = LinkedHashSet<String>()

                val gnssCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        satellitesSeen = status.satelliteCount
                        var used = 0
                        for (index in 0 until status.satelliteCount) {
                            if (status.usedInFix(index)) used++
                            constellations += constellationName(status.getConstellationType(index))
                        }
                        satellitesUsed = used
                        val elapsed = System.currentTimeMillis() - startedAt
                        ctx.progress.update(
                            (elapsed.toFloat() / FIX_TIMEOUT_MS).coerceIn(0f, 0.95f),
                            "$satellitesSeen satellites visible, $used used",
                        )
                    }
                }

                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        if (!continuation.isActive) return
                        cleanup()
                        continuation.resume(
                            FixOutcome(
                                timeToFixMs = System.currentTimeMillis() - startedAt,
                                satellitesUsed = satellitesUsed,
                                satellitesSeen = satellitesSeen,
                                accuracyMeters = location.accuracy.takeIf { it > 0 },
                                constellations = constellations.toList(),
                            ),
                        )
                    }

                    @Deprecated("Required by the pre-API-29 interface")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                }

                fun cleanupInternal() {
                    runCatching { manager.removeUpdates(locationListener) }
                    runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
                }

                cleanup = ::cleanupInternal

                runCatching {
                    @Suppress("MissingPermission")
                    manager.registerGnssStatusCallback(gnssCallback, android.os.Handler(Looper.getMainLooper()))
                    @Suppress("MissingPermission")
                    manager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        0f,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                }.onFailure {
                    if (continuation.isActive) continuation.cancel(it)
                }

                continuation.invokeOnCancellation { cleanupInternal() }
            }
        }

    private var cleanup: () -> Unit = {}

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
        GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
        else -> "Other"
    }

    private data class FixOutcome(
        val timeToFixMs: Long,
        val satellitesUsed: Int,
        val satellitesSeen: Int,
        val accuracyMeters: Float?,
        val constellations: List<String>,
    )

    companion object {
        const val ID = "connectivity.gps"

        const val KEY_FIX_ACQUIRED = "gps.fix_acquired"
        const val KEY_TTFF = "gps.ttff_s"
        const val KEY_SATELLITES_USED = "gps.satellites_used"
        const val KEY_SATELLITES_SEEN = "gps.satellites_seen"
        const val KEY_ACCURACY = "gps.accuracy_m"
        const val KEY_CONSTELLATIONS = "gps.constellations"
        const val KEY_TIMEOUT = "gps.search_time_s"

        private const val FIX_TIMEOUT_MS = 60_000L
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val SLOW_FIX_MS = 30_000L
        private const val MIN_HEALTHY_SATELLITES = 4
    }
}
