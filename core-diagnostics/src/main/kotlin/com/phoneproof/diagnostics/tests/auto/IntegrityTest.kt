package com.phoneproof.diagnostics.tests.auto

import android.os.Build
import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.Finding
import com.phoneproof.diagnostics.model.Measurement
import com.phoneproof.diagnostics.model.Severity
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.util.SystemProps
import com.phoneproof.diagnostics.util.Sysfs
import com.scottyab.rootbeer.RootBeer
import java.io.File

/**
 * Bootloader lock state, root, and custom-ROM detection.
 *
 * This is the test buyers cannot perform themselves and the one that moves price the most: an
 * unlocked bootloader means the phone's software cannot be trusted, banking apps will refuse to
 * run, and the handset is worth materially less. Findings here are deliberately loud.
 *
 * Detection is honest about its limits — a well-configured Magisk install with DenyList can defeat
 * userspace root checks, so a clean result is reported as "no evidence of root", not "guaranteed
 * unrooted". The Play Integrity verdict in [PlayIntegrityTest] is the hardware-backed cross-check.
 */
class IntegrityTest : DiagnosticTest {

    override val id = ID
    override val title = "Bootloader, root & ROM integrity"
    override val category = Category.INTEGRITY
    override val estimatedDurationMs = 1_500L

    override suspend fun run(ctx: TestContext): TestResult {
        ctx.progress.update(0.15f, "Reading verified boot state")
        val boot = readBootState()

        ctx.progress.update(0.45f, "Scanning for root")
        val root = detectRoot(ctx)

        ctx.progress.update(0.75f, "Checking ROM authenticity")
        val rom = detectCustomRom()

        val selinux = readSelinux()

        val measurements = buildList {
            add(
                Measurement.text(
                    KEY_BOOT_STATE,
                    "Verified boot state",
                    boot.verifiedBootState ?: "unavailable",
                    expected = "green",
                    note = boot.note,
                ),
            )
            add(
                Measurement.text(
                    KEY_BOOTLOADER_LOCKED,
                    "Bootloader",
                    boot.lockDescription,
                    expected = "Locked",
                ),
            )
            add(Measurement.flag(KEY_ROOTED, "Root detected", root.rooted, expected = "No"))
            add(
                Measurement.text(
                    KEY_ROOT_EVIDENCE,
                    "Root evidence",
                    root.evidence.ifEmpty { listOf("none found") }.joinToString(", "),
                ),
            )
            add(Measurement.flag(KEY_STOCK_ROM, "Stock firmware", !rom.isCustom, expected = "Yes"))
            add(Measurement.text(KEY_BUILD_TAGS, "Build tags", Build.TAGS ?: "unknown", expected = "release-keys"))
            add(Measurement.text(KEY_BUILD_TYPE, "Build type", SystemProps.getOrNull("ro.build.type") ?: "unknown", expected = "user"))
            if (rom.evidence.isNotEmpty()) {
                add(Measurement.text(KEY_ROM_EVIDENCE, "Custom ROM evidence", rom.evidence.joinToString(", ")))
            }
            add(
                Measurement.text(
                    KEY_SELINUX,
                    "SELinux",
                    selinux ?: "unavailable",
                    expected = "Enforcing",
                ),
            )
        }

        val findings = buildList {
            when (boot.lockState) {
                LockState.UNLOCKED -> add(
                    Finding(
                        Severity.CRITICAL,
                        "Bootloader is UNLOCKED. The system software can have been modified — " +
                            "banking and payment apps will refuse to run, and the phone is worth " +
                            "significantly less. Do not accept a seller's claim that this is harmless.",
                        KEY_BOOTLOADER_LOCKED,
                    ),
                )

                LockState.LOCKED -> Unit

                LockState.UNKNOWN -> add(
                    Finding(
                        Severity.MINOR,
                        "Bootloader lock state could not be read on this device. Verify manually " +
                            "by checking for a warning screen during boot.",
                        KEY_BOOTLOADER_LOCKED,
                    ),
                )
            }

            if (boot.verifiedBootState != null && !boot.verifiedBootState.equals("green", true)) {
                add(
                    Finding(
                        Severity.CRITICAL,
                        "Verified boot reports '${boot.verifiedBootState}' instead of 'green' — " +
                            "the boot chain is not intact.",
                        KEY_BOOT_STATE,
                    ),
                )
            }

            if (root.rooted) {
                add(
                    Finding(
                        Severity.CRITICAL,
                        "Root access detected (${root.evidence.take(3).joinToString(", ")}). " +
                            "A rooted phone has no meaningful security boundary and cannot be trusted " +
                            "with accounts or payments until it is fully reflashed.",
                        KEY_ROOTED,
                    ),
                )
            }

            if (rom.isCustom) {
                add(
                    Finding(
                        Severity.MAJOR,
                        "Firmware does not look like the manufacturer's stock release " +
                            "(${rom.evidence.joinToString(", ")}). Warranty is void and OTA updates " +
                            "will not arrive normally.",
                        KEY_STOCK_ROM,
                    ),
                )
            }

            if (selinux != null && !selinux.equals("Enforcing", true)) {
                add(
                    Finding(
                        Severity.MAJOR,
                        "SELinux is $selinux rather than Enforcing — a strong sign the system has " +
                            "been tampered with.",
                        KEY_SELINUX,
                    ),
                )
            }
        }

        ctx.progress.update(1f, "Integrity checked")

        val hasCritical = findings.any { it.severity == Severity.CRITICAL }
        val hasMajor = findings.any { it.severity == Severity.MAJOR }

        return when {
            hasCritical || hasMajor -> TestResult.Fail(
                summary = buildFailSummary(boot, root, rom),
                measurements = measurements,
                findings = findings,
            )

            boot.lockState == LockState.UNKNOWN -> TestResult.Warn(
                summary = "No tampering found, but bootloader state could not be confirmed",
                measurements = measurements,
                findings = findings,
            )

            else -> TestResult.Pass(
                summary = "Bootloader locked, verified boot green, no evidence of root",
                measurements = measurements,
                findings = findings,
            )
        }
    }

    private fun buildFailSummary(boot: BootState, root: RootState, rom: RomState): String {
        val problems = buildList {
            if (boot.lockState == LockState.UNLOCKED) add("bootloader unlocked")
            if (root.rooted) add("rooted")
            if (rom.isCustom) add("custom firmware")
        }
        return problems.joinToString(", ").replaceFirstChar { it.uppercase() }
            .ifBlank { "Integrity checks failed" }
    }

    // ---------------------------------------------------------------- bootloader

    private fun readBootState(): BootState {
        val verified = SystemProps.getOrNull("ro.boot.verifiedbootstate")
        val flashLocked = SystemProps.getOrNull("ro.boot.flash.locked")
        val vbmetaState = SystemProps.getOrNull("ro.boot.vbmeta.device_state")

        val lockState = when {
            flashLocked == "1" || vbmetaState.equals("locked", true) -> LockState.LOCKED
            flashLocked == "0" || vbmetaState.equals("unlocked", true) -> LockState.UNLOCKED
            verified.equals("green", true) -> LockState.LOCKED
            verified != null && !verified.equals("green", true) -> LockState.UNLOCKED
            else -> LockState.UNKNOWN
        }

        val note = when {
            flashLocked != null -> "ro.boot.flash.locked=$flashLocked"
            vbmetaState != null -> "ro.boot.vbmeta.device_state=$vbmetaState"
            verified != null -> "derived from ro.boot.verifiedbootstate"
            else -> "OEM does not expose boot state properties to apps"
        }

        return BootState(verified, lockState, note)
    }

    // ---------------------------------------------------------------- root

    private fun detectRoot(ctx: TestContext): RootState {
        val evidence = mutableListOf<String>()

        val rootBeerSaysRooted = runCatching {
            RootBeer(ctx.app).isRooted
        }.getOrDefault(false)
        if (rootBeerSaysRooted) evidence += "RootBeer heuristics"

        SU_PATHS.filter { path -> runCatching { File(path).exists() }.getOrDefault(false) }
            .forEach { evidence += "su at $it" }

        MAGISK_PATHS.filter { path -> runCatching { File(path).exists() }.getOrDefault(false) }
            .forEach { evidence += "Magisk artifact $it" }

        if (Build.TAGS?.contains("test-keys") == true) evidence += "test-keys build"

        if (SystemProps.getOrNull("ro.debuggable") == "1") evidence += "ro.debuggable=1"
        if (SystemProps.getOrNull("ro.secure") == "0") evidence += "ro.secure=0"

        MAGISK_PACKAGES.filter { pkg -> isPackageInstalled(ctx, pkg) }
            .forEach { evidence += "package $it" }

        if (canExecuteSu()) evidence += "su executes"

        return RootState(rooted = evidence.isNotEmpty(), evidence = evidence)
    }

    private fun isPackageInstalled(ctx: TestContext, packageName: String): Boolean = runCatching {
        ctx.app.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /**
     * Attempts to spawn `su`. On an unrooted phone this throws immediately; on a rooted one it
     * either succeeds or prompts. We never keep the shell open.
     */
    private fun canExecuteSu(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.destroy()
        output.contains("uid=0")
    }.getOrDefault(false)

    // ---------------------------------------------------------------- custom ROM

    private fun detectCustomRom(): RomState {
        val evidence = mutableListOf<String>()

        if (Build.TAGS?.contains("test-keys") == true) evidence += "test-keys signature"

        val buildType = SystemProps.getOrNull("ro.build.type")
        if (buildType != null && buildType != "user") evidence += "build type '$buildType'"

        CUSTOM_ROM_PROPS.forEach { (prop, name) ->
            SystemProps.getOrNull(prop)?.let { evidence += "$name ($it)" }
        }

        // A stock fingerprint almost always begins with the brand or manufacturer.
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        if (fingerprint.isNotBlank() && brand.isNotBlank() && !fingerprint.startsWith(brand)) {
            evidence += "fingerprint does not match brand '$brand'"
        }

        return RomState(isCustom = evidence.isNotEmpty(), evidence = evidence)
    }

    /**
     * Samsung and several other OEMs block `/sys/fs/selinux/enforce` even for the shell user, so
     * fall back to the build property. Both failing means "cannot know", never "permissive".
     */
    private fun readSelinux(): String? {
        Sysfs.readText("/sys/fs/selinux/enforce")?.trim()?.let { raw ->
            when (raw) {
                "1" -> return "Enforcing"
                "0" -> return "Permissive"
            }
        }
        return when (SystemProps.getOrNull("ro.build.selinux")) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else -> null
        }
    }

    private data class BootState(
        val verifiedBootState: String?,
        val lockState: LockState,
        val note: String,
    ) {
        val lockDescription: String get() = when (lockState) {
            LockState.LOCKED -> "Locked"
            LockState.UNLOCKED -> "UNLOCKED"
            LockState.UNKNOWN -> "Could not be determined"
        }
    }

    private enum class LockState { LOCKED, UNLOCKED, UNKNOWN }

    private data class RootState(val rooted: Boolean, val evidence: List<String>)

    private data class RomState(val isCustom: Boolean, val evidence: List<String>)

    companion object {
        const val ID = "integrity.bootloader_root"

        const val KEY_BOOT_STATE = "integrity.verified_boot_state"
        const val KEY_BOOTLOADER_LOCKED = "integrity.bootloader_locked"
        const val KEY_ROOTED = "integrity.rooted"
        const val KEY_ROOT_EVIDENCE = "integrity.root_evidence"
        const val KEY_STOCK_ROM = "integrity.stock_rom"
        const val KEY_ROM_EVIDENCE = "integrity.rom_evidence"
        const val KEY_BUILD_TAGS = "integrity.build_tags"
        const val KEY_BUILD_TYPE = "integrity.build_type"
        const val KEY_SELINUX = "integrity.selinux"

        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sd/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/system/bin/failsafe/su",
        )

        private val MAGISK_PATHS = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
            "/cache/.disable_magisk", "/sbin/.core/mirror", "/data/adb/ksu",
        )

        private val MAGISK_PACKAGES = listOf(
            "com.topjohnwu.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "eu.chainfire.supersu", "com.noshufou.android.su", "com.koushikdutta.superuser",
        )

        /** Property names that only exist on well-known aftermarket ROMs. */
        private val CUSTOM_ROM_PROPS = listOf(
            "ro.lineage.version" to "LineageOS",
            "ro.cm.version" to "CyanogenMod",
            "ro.pa.version" to "Paranoid Android",
            "ro.omni.version" to "OmniROM",
            "ro.rr.version" to "Resurrection Remix",
            "ro.havoc.version" to "Havoc-OS",
            "org.evolution.version" to "Evolution X",
            "ro.crdroid.version" to "crDroid",
            "ro.pixelexperience.version" to "Pixel Experience",
        )
    }
}
