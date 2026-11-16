package com.phoneproof.report.model

import com.phoneproof.diagnostics.model.DiagnosticRun
import com.phoneproof.report.grading.GradeResult
import kotlinx.serialization.Serializable

/**
 * Outlet branding applied to every PDF. This is the white-label surface: a shop's own name,
 * address and logo appear on the report in place of PhoneProof's.
 */
@Serializable
data class ShopBranding(
    val shopName: String = "",
    val addressLine: String = "",
    val phone: String = "",
    val email: String = "",
    /** Absolute path to a PNG/JPEG logo drawn on the first page. */
    val logoPath: String? = null,
    val technicianName: String = "",
    val footerNote: String = "",
) {
    val isConfigured: Boolean get() = shopName.isNotBlank()

    companion object {
        /** Used in buyer and seller modes, where there is no shop. */
        val NONE = ShopBranding()
    }
}

/** Who the phone was checked for, captured at intake in shop mode. */
@Serializable
data class CustomerDetails(
    val name: String = "",
    val phone: String = "",
    val note: String = "",
) {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank()
}

/**
 * The tamper-evidence envelope.
 *
 * [deviceFingerprint] binds the report to specific hardware and [signature] binds the whole payload
 * to this installation's key. Together they stop the trick this product exists to prevent: a seller
 * attaching somebody else's clean report to a bad phone.
 */
@Serializable
data class ReportAttestation(
    val reportId: String,
    val generatedAtEpochMs: Long,
    /** Short human-checkable code, e.g. `PP-4K7Q-2XM9`. */
    val verificationCode: String,
    /** SHA-256 over stable hardware attributes plus the report timestamp. */
    val deviceFingerprint: String,
    /** Base64 signature over the canonical payload. */
    val signature: String,
    /** Base64 X.509 public key so a verifier can check the signature offline. */
    val publicKey: String,
    val signatureAlgorithm: String,
    /** Optional hosted verification page. */
    val verifyUrl: String? = null,
)

/** Everything the PDF renderer and the verification screen need. */
@Serializable
data class PhoneProofReport(
    val run: DiagnosticRun,
    val grade: GradeResult,
    val attestation: ReportAttestation,
    val branding: ShopBranding = ShopBranding.NONE,
    val customer: CustomerDetails = CustomerDetails(),
    /** Populated in shop mode when a price matrix is configured. */
    val offer: PriceOffer? = null,
)

/** A concrete buy offer derived from the grade and the shop's price matrix. */
@Serializable
data class PriceOffer(
    val model: String,
    val basePrice: Int,
    val gradeMultiplier: Double,
    val offerPrice: Int,
    val currency: String = "INR",
    val adjustments: List<PriceAdjustment> = emptyList(),
    val note: String = "",
) {
    val formattedOffer: String get() = formatCurrency(offerPrice)
    val formattedBase: String get() = formatCurrency(basePrice)

    private fun formatCurrency(amount: Int): String = when (currency) {
        "INR" -> "₹" + groupIndian(amount)
        else -> "$currency $amount"
    }

    /**
     * Indian digit grouping (1,50,000 rather than 150,000), using the Western grouping on a
     * customer-facing offer in an Indian shop looks immediately wrong.
     */
    private fun groupIndian(amount: Int): String {
        val digits = amount.toString()
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val grouped = head.reversed().chunked(2).joinToString(",").reversed()
        return "$grouped,$tail"
    }
}

@Serializable
data class PriceAdjustment(val label: String, val amount: Int)
