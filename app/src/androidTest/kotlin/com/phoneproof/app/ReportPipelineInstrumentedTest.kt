package com.phoneproof.app

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.phoneproof.diagnostics.model.Artifact
import com.phoneproof.diagnostics.model.ArtifactStore
import com.phoneproof.diagnostics.model.GuidedOutcome
import com.phoneproof.diagnostics.model.GuidedStep
import com.phoneproof.diagnostics.model.InteractionHost
import com.phoneproof.diagnostics.model.PermissionGate
import com.phoneproof.diagnostics.model.RunDepth
import com.phoneproof.diagnostics.model.RunMode
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.registry.TestCatalogue
import com.phoneproof.diagnostics.runner.DiagnosticRunner
import com.phoneproof.report.grading.GradingEngine
import com.phoneproof.report.model.CustomerDetails
import com.phoneproof.report.model.PhoneProofReport
import com.phoneproof.report.model.ShopBranding
import com.phoneproof.report.pdf.ReportPdfRenderer
import com.phoneproof.report.security.ReportSigner
import com.phoneproof.report.security.VerificationOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end: run the diagnostics, grade them, sign the result, render the PDF, then verify the
 * signature round-trips. This is the whole product path in one test.
 */
@RunWith(AndroidJUnit4::class)
class ReportPipelineInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generateSignedReport() = runBlocking {
        val runner = DiagnosticRunner(
            app = context,
            permissions = RealPermissionGate(context),
            interaction = SkippingInteractionHost,
            artifacts = NoArtifactStore,
        )

        val run = runner.run(
            TestCatalogue.automaticTests(RunDepth.QUICK),
            RunMode.SHOP,
            RunDepth.QUICK,
        )

        val grade = GradingEngine().grade(run)
        Log.i(TAG, "grade=${grade.grade.letter} score=${grade.scoreRounded} confidence=${grade.confidencePercent}%")
        grade.deductions.forEach { Log.i(TAG, "deduction -${Math.round(it.points)}: ${it.reason}") }

        val signer = ReportSigner(context)
        val attestation = signer.attest(run, grade, verifyUrlBase = "https://verify.example.com")
        Log.i(TAG, "verification code = ${attestation.verificationCode}")

        val report = PhoneProofReport(
            run = run,
            grade = grade,
            attestation = attestation,
            branding = ShopBranding(
                shopName = "Example Mobile Care",
                addressLine = "1 Example Street, Sample City",
                phone = "+1 555 0100",
                technicianName = "Counter 1",
            ),
            customer = CustomerDetails(name = "Walk-in customer", phone = "—"),
        )

        val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
        val pdf = ReportPdfRenderer(context).render(report, File(outputDir, "phoneproof-report.pdf"))

        Log.i(TAG, "pdf written to ${pdf.absolutePath} (${pdf.length()} bytes)")

        assertTrue("PDF was not created", pdf.exists())
        assertTrue("PDF is suspiciously small", pdf.length() > 5_000)
        assertEquals("%PDF", pdf.readBytes().take(4).toByteArray().decodeToString())

        // The signature must validate against the same payload and bind to this handset.
        val outcome = signer.verify(attestation, run, grade)
        Log.i(TAG, "verification outcome = $outcome")
        assertEquals(VerificationOutcome.VALID_FOR_THIS_DEVICE, outcome)

        // Tampering with any verdict must break the signature.
        //
        // The forged record carries its own measurements rather than reusing the real ones. An
        // earlier version copied them across, which meant that on a phone whose battery already
        // passed, the "tampered" payload was byte-identical to the original and the test asserted
        // nothing. The marker guarantees the signed payload really changes.
        assertTrue(
            "run must contain the battery test for this tamper check to mean anything",
            run.records.any { it.testId == "battery.health" },
        )
        val tamperedRun = run.copy(
            records = run.records.map { record ->
                if (record.testId == "battery.health") {
                    record.copy(
                        result = com.phoneproof.diagnostics.model.TestResult.Pass(
                            summary = "100% — perfect battery",
                            measurements = listOf(
                                com.phoneproof.diagnostics.model.Measurement
                                    .number("battery.health_pct", "Health", 100.0),
                                com.phoneproof.diagnostics.model.Measurement
                                    .text("battery.forged_marker", "Forged marker", "edited-by-test"),
                            ),
                        ),
                    )
                } else {
                    record
                }
            },
        )
        assertTrue(
            "tamper must actually change the run",
            tamperedRun.records != run.records,
        )
        val tamperedOutcome = signer.verify(attestation, tamperedRun, grade)
        Log.i(TAG, "tampered outcome = $tamperedOutcome")
        assertEquals(VerificationOutcome.TAMPERED, tamperedOutcome)
    }

    private class RealPermissionGate(private val context: Context) : PermissionGate {
        override fun isGranted(permission: String): Boolean =
            context.checkSelfPermission(permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        override suspend fun request(permission: String, rationale: String?): Boolean = false
    }

    private object SkippingInteractionHost : InteractionHost {
        override suspend fun await(step: GuidedStep): GuidedOutcome =
            GuidedOutcome.Skipped(SkipReason.USER_SKIPPED, "headless")
    }

    private object NoArtifactStore : ArtifactStore {
        override fun newFile(testId: String, extension: String): File =
            File.createTempFile(testId.replace('.', '_'), ".$extension")

        override fun register(artifact: Artifact) = Unit
        override fun all(): List<Artifact> = emptyList()
    }

    private companion object {
        const val TAG = "PhoneProofPipeline"
    }
}
