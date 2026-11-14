package com.phoneproof.report.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.phoneproof.diagnostics.model.DiagnosticRun
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.report.grading.GradeResult
import com.phoneproof.report.model.ReportAttestation
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Signs reports with a hardware-backed key so a PDF can be proved authentic offline.
 *
 * The threat this defends against is mundane and common: someone edits the PDF, or pairs a good
 * report with a bad phone. Obscurity would not survive contact with a motivated reseller, so the
 * payload is signed with an EC key held in the Android Keystore and the public key travels inside
 * the report — any verifier can check it without contacting a server.
 */
class ReportSigner(private val context: Context) {

    /** Builds the signed envelope for a finished run. */
    fun attest(
        run: DiagnosticRun,
        grade: GradeResult,
        generatedAtEpochMs: Long = System.currentTimeMillis(),
        verifyUrlBase: String? = null,
    ): ReportAttestation {
        val reportId = UUID.randomUUID().toString()
        val fingerprint = DeviceFingerprint.reportFingerprint(context, generatedAtEpochMs)
        val payload = canonicalPayload(reportId, generatedAtEpochMs, fingerprint, run, grade)

        val keyPair = loadOrCreateKey()
        val signature = sign(payload, keyPair.privateKey)
        val publicKeyEncoded = Base64.encodeToString(keyPair.publicKey.encoded, Base64.NO_WRAP)
        val verificationCode = deriveVerificationCode(signature)

        return ReportAttestation(
            reportId = reportId,
            generatedAtEpochMs = generatedAtEpochMs,
            verificationCode = verificationCode,
            deviceFingerprint = fingerprint,
            signature = signature,
            publicKey = publicKeyEncoded,
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            verifyUrl = verifyUrlBase?.let { "$it/verify/$verificationCode" },
        )
    }

    /**
     * Verifies a report's signature. Returns a typed outcome rather than a bare boolean so the
     * verification screen can explain *which* check failed.
     */
    fun verify(
        attestation: ReportAttestation,
        run: DiagnosticRun,
        grade: GradeResult,
    ): VerificationOutcome {
        val payload = canonicalPayload(
            attestation.reportId,
            attestation.generatedAtEpochMs,
            attestation.deviceFingerprint,
            run,
            grade,
        )

        val signatureValid = runCatching {
            val keyBytes = Base64.decode(attestation.publicKey, Base64.NO_WRAP)
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(payload.toByteArray())
                verify(Base64.decode(attestation.signature, Base64.NO_WRAP))
            }
        }.getOrDefault(false)

        if (!signatureValid) return VerificationOutcome.TAMPERED

        val deviceMatches = DeviceFingerprint.matches(
            context,
            attestation.deviceFingerprint,
            attestation.generatedAtEpochMs,
        )

        return if (deviceMatches) VerificationOutcome.VALID_FOR_THIS_DEVICE
        else VerificationOutcome.VALID_BUT_DIFFERENT_DEVICE
    }

    /**
     * Deterministic serialisation of everything that must not change after signing.
     *
     * Per-test verdicts and the measurements behind the grade are all included, so editing a single
     * Fail to a Pass in the PDF invalidates the signature.
     */
    internal fun canonicalPayload(
        reportId: String,
        generatedAtEpochMs: Long,
        fingerprint: String,
        run: DiagnosticRun,
        grade: GradeResult,
    ): String = buildString {
        append("phoneproof.v1\n")
        append("report=").append(reportId).append('\n')
        append("generated=").append(generatedAtEpochMs).append('\n')
        append("fingerprint=").append(fingerprint).append('\n')
        append("runId=").append(run.runId).append('\n')
        append("mode=").append(run.mode.name).append('\n')
        append("depth=").append(run.depth.name).append('\n')
        append("grade=").append(grade.grade.letter).append('\n')
        append("score=").append(Math.round(grade.score)).append('\n')
        append("confidence=").append(grade.confidencePercent).append('\n')

        run.records.sortedBy { it.testId }.forEach { record ->
            append("test:").append(record.testId).append('=')
            append(
                when (record.result) {
                    is TestResult.Pass -> "PASS"
                    is TestResult.Warn -> "WARN"
                    is TestResult.Fail -> "FAIL"
                    is TestResult.Skipped -> "SKIP"
                    is TestResult.Unsupported -> "UNSUPPORTED"
                },
            )
            record.measurements.sortedBy { it.key }.forEach { measurement ->
                append(';').append(measurement.key).append(':').append(measurement.value.display)
            }
            append('\n')
        }
    }

    private fun sign(payload: String, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(payload.toByteArray())
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /**
     * A short code a human can read out over the phone. Crockford-style alphabet with the
     * ambiguous characters removed, because these get dictated across a counter.
     */
    internal fun deriveVerificationCode(signature: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
        val body = (0 until CODE_LENGTH).map { index ->
            CODE_ALPHABET[(digest[index].toInt() and 0xFF) % CODE_ALPHABET.length]
        }.joinToString("")
        return "PP-${body.substring(0, 4)}-${body.substring(4, 8)}"
    }

    private fun loadOrCreateKey(): KeyPairHolder {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        val existingPrivate = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
        val existingCertificate = keyStore.getCertificate(KEY_ALIAS)
        if (existingPrivate != null && existingCertificate != null) {
            return KeyPairHolder(existingPrivate, existingCertificate.publicKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // Deliberately not user-authentication bound: a shop signs dozens of reports an
                // hour and must not be prompted for a fingerprint on every one.
                .build(),
        )
        val pair = generator.generateKeyPair()
        return KeyPairHolder(pair.private, pair.public)
    }

    private data class KeyPairHolder(val privateKey: PrivateKey, val publicKey: PublicKey)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "phoneproof_report_signing_v1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val CODE_LENGTH = 8
        private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}

/** Result of checking a report against its signature and the phone in hand. */
enum class VerificationOutcome(val title: String, val detail: String) {
    VALID_FOR_THIS_DEVICE(
        "Verified",
        "This report is authentic and was produced on this exact phone.",
    ),
    VALID_BUT_DIFFERENT_DEVICE(
        "Wrong phone",
        "The report is genuine and unmodified, but it was produced on a different handset. " +
            "Do not accept it as evidence for the phone in your hand.",
    ),
    TAMPERED(
        "Not valid",
        "The signature does not match the report contents. It has been edited or was not produced " +
            "by PhoneProof.",
    ),
}
