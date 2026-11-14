package com.phoneproof.app

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.phoneproof.diagnostics.model.Artifact
import com.phoneproof.diagnostics.model.ArtifactStore
import com.phoneproof.diagnostics.model.GuidedOutcome
import com.phoneproof.diagnostics.model.GuidedStep
import com.phoneproof.diagnostics.model.InteractionHost
import com.phoneproof.diagnostics.model.MeasuredValue
import com.phoneproof.diagnostics.model.PermissionGate
import com.phoneproof.diagnostics.model.RunDepth
import com.phoneproof.diagnostics.model.RunMode
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.registry.TestCatalogue
import com.phoneproof.diagnostics.runner.DiagnosticRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the full automatic suite against whatever device the instrumentation is attached to and
 * dumps every measured value.
 *
 * This is the harness used to validate real OEM behaviour — sysfs paths and permission handling
 * differ enough between vendors that only a run on physical hardware tells the truth.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticDiagnosticsInstrumentedTest {

    /**
     * AGP uninstalls both APKs after a connected run, which wipes any permissions granted from the
     * shell. Granting them here keeps the harness self-sufficient.
     */
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runAutomaticSuite() = runBlocking {
        val runner = DiagnosticRunner(
            app = context,
            permissions = RealPermissionGate(context),
            interaction = RefusingInteractionHost,
            artifacts = TempArtifactStore(context),
        )

        val depth = System.getProperty("phoneproof.depth")
            ?.let { runCatching { RunDepth.valueOf(it) }.getOrNull() }
            ?: RunDepth.QUICK

        val tests = TestCatalogue.automaticTests(depth)
        Log.i(TAG, "Running ${tests.size} automatic tests at depth $depth")

        val run = runner.run(tests, RunMode.BUYER, depth)

        val report = buildReport(run)
        report.lines().forEach { Log.i(TAG, it) }

        File(context.getExternalFilesDir(null), "phoneproof-run.txt").writeText(report)

        assertTrue("Runner produced no records", run.records.isNotEmpty())
        assertTrue(
            "No test produced real evidence — the suite is not functioning",
            run.executedCount > 0,
        )
    }

    private fun buildReport(run: com.phoneproof.diagnostics.model.DiagnosticRun): String = buildString {
        appendLine("=".repeat(78))
        appendLine("PHONEPROOF AUTOMATIC RUN")
        appendLine("device      : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("android     : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("duration    : ${run.durationMs / 1000}s")
        appendLine("executed    : ${run.executedCount}/${run.records.size}")
        appendLine("skipped     : ${run.skippedCount}   unsupported: ${run.unsupportedCount}")
        appendLine("failed      : ${run.failedCount}    warned: ${run.warnedCount}")
        appendLine("completeness: ${(run.completeness * 100).toInt()}%")
        appendLine("=".repeat(78))

        run.records.forEach { record ->
            val verdict = when (record.result) {
                is TestResult.Pass -> "PASS"
                is TestResult.Warn -> "WARN"
                is TestResult.Fail -> "FAIL"
                is TestResult.Skipped -> "SKIP"
                is TestResult.Unsupported -> "N/A "
            }
            appendLine()
            appendLine("[$verdict] ${record.title}  (${record.durationMs}ms)")
            appendLine("        ${record.result.summary}")
            record.measurements.forEach { measurement ->
                val value = when (val v = measurement.value) {
                    is MeasuredValue.Table -> "\n" + v.rows.joinToString("\n") { "                  - ${it.label}: ${it.value}" }
                    else -> measurement.display
                }
                appendLine("        · ${measurement.label}: $value")
                measurement.note?.let { appendLine("            note: $it") }
            }
            record.findings.forEach { finding ->
                appendLine("        ! [${finding.severity}] ${finding.message}")
            }
        }
    }

    /** Reports real permission state; instrumentation cannot show a system dialog. */
    private class RealPermissionGate(private val context: Context) : PermissionGate {
        override fun isGranted(permission: String): Boolean =
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        override suspend fun request(permission: String, rationale: String?): Boolean {
            Log.w(TAG, "permission not pre-granted: $permission")
            return false
        }
    }

    /** Guided tests must not run headless; refusing proves they degrade to Skipped, not Pass. */
    private object RefusingInteractionHost : InteractionHost {
        override suspend fun await(step: GuidedStep): GuidedOutcome =
            GuidedOutcome.Skipped(SkipReason.USER_SKIPPED, "headless run")
    }

    private class TempArtifactStore(private val context: Context) : ArtifactStore {
        private val artifacts = mutableListOf<Artifact>()

        override fun newFile(testId: String, extension: String): File =
            File.createTempFile(testId.replace('.', '_'), ".$extension", context.cacheDir)

        override fun register(artifact: Artifact) { artifacts += artifact }

        override fun all(): List<Artifact> = artifacts.toList()
    }

    private companion object {
        const val TAG = "PhoneProofRun"
    }
}
