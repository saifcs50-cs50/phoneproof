package com.phoneproof.diagnostics.tests.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult

/**
 * Cellular capability and current radio state: SIM slots, network generation, active bands and
 * signal strength.
 *
 * Band coverage matters more in India than almost anywhere else; a grey-import handset can be
 * perfectly genuine yet miss the exact LTE bands the local carrier uses, which is invisible until
 * the buyer gets home and finds the phone drops to 3G on their street.
 */
class CellularRadioTest : DiagnosticTest {

    override val id = ID
    override val title = "Cellular radio"
    override val category = Category.CONNECTIVITY
    override val estimatedDurationMs = 3_000L

    override val requiredPermissions = listOf(Manifest.permission.READ_PHONE_STATE)

    override val permissionRationale =
        "Reads SIM slots and network type. PhoneProof never reads your phone number or contacts."

    override suspend fun run(ctx: TestContext): TestResult {
        val pm = ctx.app.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return TestResult.Unsupported("No cellular radio in this device (Wi-Fi only)")
        }

        val telephony = ctx.app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return TestResult.Unsupported("TelephonyManager unavailable")

        ctx.progress.update(0.2f, "Reading SIM configuration")

        val slots = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) telephony.activeModemCount
            else @Suppress("DEPRECATION") telephony.phoneCount
        }.getOrDefault(1)

        val subscriptions = readSubscriptions(ctx)
        val simState = simStateLabel(telephony.simState)

        ctx.progress.update(0.5f, "Reading network registration")

        val networkType = runCatching { networkTypeLabel(telephony.dataNetworkType) }
            .getOrDefault("Unknown")

        // Cell identity needs location permission; without it we still report everything else
        // rather than skipping a test that has plenty of other value.
        val locationGranted = ctx.permissions.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            ctx.permissions.request(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Android requires location permission to read the cell bands your phone is using.",
            )

        ctx.progress.update(0.75f, "Scanning bands")
        val cellInfo = if (locationGranted) readCellInfo(telephony) else emptyList()
        val bands = extractBands(cellInfo)
        val signalDbm = extractSignalDbm(cellInfo)
        val nrSeen = cellInfo.any { it is CellInfoNr }

        val measurements = buildList {
            add(Measurement.count(KEY_SIM_SLOTS, "SIM slots", slots.toLong()))
            add(Measurement.flag(KEY_DUAL_SIM, "Dual SIM", slots > 1))
            add(Measurement.text(KEY_SIM_STATE, "SIM state", simState))
            add(Measurement.count(KEY_ACTIVE_SUBS, "Active subscriptions", subscriptions.size.toLong()))
            if (subscriptions.isNotEmpty()) {
                add(
                    Measurement.table(
                        KEY_CARRIERS,
                        "Carriers",
                        subscriptions.map { MeasuredValue.Table.Row("Slot ${it.slot}", it.carrier) },
                    ),
                )
            }
            add(Measurement.text(KEY_NETWORK_TYPE, "Current network", networkType))
            add(Measurement.flag(KEY_ESIM, "eSIM capable", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_EUICC)))
            add(Measurement.flag(KEY_IMS, "VoLTE/IMS stack present", pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS)))
            add(
                Measurement.flag(
                    KEY_NR_OBSERVED,
                    "5G NR cell observed",
                    nrSeen,
                    note = "only observable where 5G coverage exists",
                ),
            )

            if (bands.isNotEmpty()) {
                add(Measurement.text(KEY_BANDS, "Active bands", bands.joinToString(", ")))
            } else {
                add(
                    Measurement.text(
                        KEY_BANDS,
                        "Active bands",
                        if (locationGranted) "none reported" else "location permission declined",
                        note = "Android exposes band numbers only for the currently registered cell",
                    ),
                )
            }

            signalDbm?.let {
                add(
                    Measurement.number(
                        KEY_SIGNAL,
                        "Signal strength",
                        it.toDouble(),
                        unit = "dBm",
                        expected = "-50 (excellent) to -110 (poor)",
                    ),
                )
            }
        }

        ctx.progress.update(1f, "Radio checked")

        val findings = buildList {
            if (telephony.simState == TelephonyManager.SIM_STATE_ABSENT) {
                add(
                    Finding(
                        Severity.INFO,
                        "No SIM inserted, so network registration, bands and calling could not be " +
                            "verified. Insert a SIM to test the radio properly.",
                        KEY_SIM_STATE,
                    ),
                )
            }

            if (telephony.simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED) {
                add(
                    Finding(
                        Severity.MAJOR,
                        "This handset is CARRIER LOCKED. It will only accept SIMs from one network " +
                            "until it is officially unlocked.",
                        KEY_SIM_STATE,
                    ),
                )
            }

            if (signalDbm != null && signalDbm < WEAK_SIGNAL_DBM) {
                add(
                    Finding(
                        Severity.MINOR,
                        "Signal is weak at $signalDbm dBm. Re-test elsewhere before blaming the " +
                            "antenna — but a phone that is always weak often has a damaged one.",
                        KEY_SIGNAL,
                    ),
                )
            }
        }

        val summary = buildString {
            append(if (slots > 1) "Dual SIM" else "Single SIM")
            append(" · $networkType")
            if (bands.isNotEmpty()) append(" · bands ${bands.joinToString("/")}")
        }

        return when {
            findings.any { it.severity == Severity.MAJOR || it.severity == Severity.CRITICAL } ->
                TestResult.Fail(summary, measurements, findings)

            findings.any { it.severity == Severity.MINOR } ->
                TestResult.Warn(summary, measurements, findings)

            else -> TestResult.Pass(summary, measurements, findings)
        }
    }

    private fun readSubscriptions(ctx: TestContext): List<SubscriptionSummary> = runCatching {
        val manager = ctx.app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as? SubscriptionManager ?: return emptyList()
        manager.activeSubscriptionInfoList.orEmpty().map {
            SubscriptionSummary(
                slot = it.simSlotIndex + 1,
                carrier = it.carrierName?.toString().orEmpty().ifBlank { "unknown carrier" },
            )
        }
    }.getOrDefault(emptyList())

    private fun readCellInfo(telephony: TelephonyManager): List<CellInfo> = runCatching {
        @Suppress("MissingPermission")
        telephony.allCellInfo.orEmpty()
    }.getOrDefault(emptyList())

    /** Band numbers for the registered cell. Only available from API 30 onwards. */
    private fun extractBands(cells: List<CellInfo>): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return cells.filter { it.isRegistered }.flatMap { cell ->
            when (cell) {
                is CellInfoLte -> (cell.cellIdentity as? CellIdentityLte)
                    ?.bands?.toList().orEmpty().map { "LTE B$it" }

                is CellInfoNr -> (cell.cellIdentity as? CellIdentityNr)
                    ?.bands?.toList().orEmpty().map { "NR n$it" }

                else -> emptyList()
            }
        }.distinct()
    }

    private fun extractSignalDbm(cells: List<CellInfo>): Int? =
        cells.firstOrNull { it.isRegistered }
            ?.cellSignalStrength
            ?.dbm
            ?.takeIf { it in -140..-30 }

    private fun simStateLabel(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM inserted"
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Carrier locked"
        TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Permanently disabled"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Card read error"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "Card restricted"
        else -> "Unknown"
    }

    private fun networkTypeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Not registered"
        else -> "Type $type"
    }

    private data class SubscriptionSummary(val slot: Int, val carrier: String)

    companion object {
        const val ID = "connectivity.cellular"

        const val KEY_SIM_SLOTS = "cellular.sim_slots"
        const val KEY_DUAL_SIM = "cellular.dual_sim"
        const val KEY_SIM_STATE = "cellular.sim_state"
        const val KEY_ACTIVE_SUBS = "cellular.active_subscriptions"
        const val KEY_CARRIERS = "cellular.carriers"
        const val KEY_NETWORK_TYPE = "cellular.network_type"
        const val KEY_ESIM = "cellular.esim"
        const val KEY_IMS = "cellular.ims"
        const val KEY_NR_OBSERVED = "cellular.nr_observed"
        const val KEY_BANDS = "cellular.bands"
        const val KEY_SIGNAL = "cellular.signal_dbm"

        private const val WEAK_SIGNAL_DBM = -110
    }
}
