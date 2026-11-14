package com.phoneproof.report.security

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import java.security.MessageDigest

/**
 * Derives a stable identifier for the physical handset.
 *
 * The point is to bind a report to *this* phone so a seller cannot attach a clean report from a
 * different unit. Only attributes that survive a reboot and a factory reset are used, and nothing
 * personally identifying goes in — no IMEI, no serial, no advertising ID. Two identical phones of
 * the same model and configuration will legitimately produce the same hardware id, which is why the
 * per-report fingerprint also folds in the report timestamp.
 */
object DeviceFingerprint {

    /** Recomputable on the same device at verification time. */
    fun hardwareId(context: Context): String {
        val attributes = listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.BOARD,
            Build.HARDWARE,
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            socModel(),
            screenSignature(context),
            roundedRamGb(context).toString(),
            roundedStorageGb().toString(),
        )
        return sha256(attributes.joinToString("|"))
    }

    /**
     * Per-report value embedded in the PDF: the hardware id bound to the moment of issue, so an old
     * report cannot be passed off as a fresh one.
     */
    fun reportFingerprint(context: Context, generatedAtEpochMs: Long): String =
        sha256("${hardwareId(context)}|$generatedAtEpochMs")

    /** Recomputes the fingerprint for a report being verified against the phone in hand. */
    fun matches(context: Context, storedFingerprint: String, generatedAtEpochMs: Long): Boolean =
        reportFingerprint(context, generatedAtEpochMs)
            .equals(storedFingerprint, ignoreCase = true)

    private fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""

    private fun screenSignature(context: Context): String = runCatching {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        "${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}"
    }.getOrDefault("unknown")

    /** Rounded so a few reserved megabytes cannot change the identity between runs. */
    private fun roundedRamGb(context: Context): Int = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        Math.round(info.totalMem / 1_000_000_000.0).toInt()
    }.getOrDefault(0)

    private fun roundedStorageGb(): Int = runCatching {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        Math.round(stat.blockCountLong * stat.blockSizeLong / 1_000_000_000.0).toInt()
    }.getOrDefault(0)

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
