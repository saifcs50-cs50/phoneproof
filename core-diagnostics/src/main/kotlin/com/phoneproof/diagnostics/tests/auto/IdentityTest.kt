package com.phoneproof.diagnostics.tests.auto

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.util.SystemProps
import com.phoneproof.diagnostics.util.Sysfs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Establishes what the phone claims to be. Purely informational (it never fails), but every other
 * test reads from it, and the counterfeit check compares these claims against measured reality.
 */
class IdentityTest : DiagnosticTest {

    override val id = ID
    override val title = "Device identity"
    override val category = Category.IDENTITY
    override val estimatedDurationMs = 400L

    override suspend fun run(ctx: TestContext): TestResult {
        ctx.progress.update(0.1f, "Reading device identity")

        val measurements = buildList {
            add(Measurement.text(KEY_MANUFACTURER, "Manufacturer", Build.MANUFACTURER.orUnknown()))
            add(Measurement.text(KEY_BRAND, "Brand", Build.BRAND.orUnknown()))
            add(Measurement.text(KEY_MODEL, "Model", Build.MODEL.orUnknown()))
            add(Measurement.text(KEY_DEVICE, "Device codename", Build.DEVICE.orUnknown()))

            add(Measurement.text(KEY_SOC, "Chipset", readSoc(), note = socNote()))
            add(Measurement.text(KEY_HARDWARE, "Hardware platform", Build.HARDWARE.orUnknown()))
            add(
                Measurement.text(
                    KEY_ABI,
                    "CPU architecture",
                    Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                ),
            )
            add(Measurement.count(KEY_CPU_CORES, "CPU cores", Runtime.getRuntime().availableProcessors().toLong()))

            val ram = totalRamBytes(ctx.app)
            add(
                Measurement.number(
                    KEY_RAM_GB,
                    "RAM",
                    ram.toGigabytes(),
                    unit = "GB",
                    note = "Reported by the OS; a little below the marketed figure is normal",
                ),
            )

            val storage = totalStorageBytes()
            add(
                Measurement.number(
                    KEY_STORAGE_TOTAL_GB,
                    "Storage (total)",
                    storage.total.toGigabytes(),
                    unit = "GB",
                ),
            )
            add(
                Measurement.number(
                    KEY_STORAGE_FREE_GB,
                    "Storage (free)",
                    storage.free.toGigabytes(),
                    unit = "GB",
                ),
            )

            add(Measurement.text(KEY_ANDROID_VERSION, "Android version", Build.VERSION.RELEASE.orUnknown()))
            add(Measurement.count(KEY_API_LEVEL, "API level", Build.VERSION.SDK_INT.toLong()))
            add(
                Measurement.text(
                    KEY_SECURITY_PATCH,
                    "Security patch",
                    Build.VERSION.SECURITY_PATCH.orUnknown(),
                    note = securityPatchNote(),
                ),
            )
            add(Measurement.text(KEY_FINGERPRINT, "Build fingerprint", Build.FINGERPRINT.orUnknown()))
            add(Measurement.text(KEY_BUILD_ID, "Build number", Build.DISPLAY.orUnknown()))
            add(Measurement.text(KEY_KERNEL, "Kernel", readKernel()))
            add(
                Measurement.text(
                    KEY_BUILD_DATE,
                    "Firmware build date",
                    formatDate(Build.TIME),
                ),
            )
        }

        ctx.progress.update(1f, "Identity captured")

        return TestResult.Pass(
            summary = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
            measurements = measurements,
        )
    }

    private fun readSoc(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val model = Build.SOC_MODEL
            val manufacturer = Build.SOC_MANUFACTURER
            if (!model.isNullOrBlank() && model != Build.UNKNOWN) {
                return listOfNotNull(
                    manufacturer?.takeIf { it.isNotBlank() && it != Build.UNKNOWN },
                    model,
                ).joinToString(" ")
            }
        }
        return cpuInfoHardware() ?: Build.HARDWARE.orUnknown()
    }

    private fun socNote(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null
        else "Read from /proc/cpuinfo — Build.SOC_MODEL needs Android 12+"

    /** Parses the `Hardware` line out of /proc/cpuinfo for pre-Android-12 devices. */
    private fun cpuInfoHardware(): String? = Sysfs.readText("/proc/cpuinfo")
        ?.lineSequence()
        ?.firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.ifBlank { null }

    private fun readKernel(): String =
        System.getProperty("os.version")?.takeIf { it.isNotBlank() }
            ?: Sysfs.readText("/proc/version")?.take(120)
            ?: "unknown"

    private fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    private fun totalStorageBytes(): StorageInfo {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return StorageInfo(total, free)
    }

    private fun securityPatchNote(): String? {
        val patch = Build.VERSION.SECURITY_PATCH.takeIf { !it.isNullOrBlank() } ?: return null
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
        }.getOrNull() ?: return null
        val monthsOld = ((System.currentTimeMillis() - parsed.time) / MILLIS_PER_MONTH).toInt()
        return when {
            monthsOld <= 6 -> "Current — patched within the last 6 months"
            monthsOld <= 18 -> "$monthsOld months old"
            else -> "$monthsOld months old — this device is no longer receiving updates"
        }
    }

    private fun formatDate(epochMs: Long): String =
        if (epochMs <= 0) "unknown"
        else SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(epochMs))

    private data class StorageInfo(val total: Long, val free: Long)

    private fun String?.orUnknown(): String =
        this?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: "unknown"

    private fun Long.toGigabytes(): Double = this / 1_000_000_000.0

    companion object {
        const val ID = "identity.device"

        const val KEY_MANUFACTURER = "identity.manufacturer"
        const val KEY_BRAND = "identity.brand"
        const val KEY_MODEL = "identity.model"
        const val KEY_DEVICE = "identity.device"
        const val KEY_SOC = "identity.soc"
        const val KEY_HARDWARE = "identity.hardware"
        const val KEY_ABI = "identity.abi"
        const val KEY_CPU_CORES = "identity.cpu_cores"
        const val KEY_RAM_GB = "identity.ram_gb"
        const val KEY_STORAGE_TOTAL_GB = "identity.storage_total_gb"
        const val KEY_STORAGE_FREE_GB = "identity.storage_free_gb"
        const val KEY_ANDROID_VERSION = "identity.android_version"
        const val KEY_API_LEVEL = "identity.api_level"
        const val KEY_SECURITY_PATCH = "identity.security_patch"
        const val KEY_FINGERPRINT = "identity.fingerprint"
        const val KEY_BUILD_ID = "identity.build_id"
        const val KEY_KERNEL = "identity.kernel"
        const val KEY_BUILD_DATE = "identity.build_date"

        private const val MILLIS_PER_MONTH = 30L * 24 * 60 * 60 * 1000
    }
}
