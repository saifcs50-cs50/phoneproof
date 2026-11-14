package com.phoneproof.diagnostics.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Known-good factory specification for one model.
 *
 * Two tests depend on this: the counterfeit check compares these claims against what the hardware
 * actually reports, and the battery test uses [designCapacityMah] when the device refuses to expose
 * `charge_full_design` (which, on most modern OEM builds, it does).
 *
 * The catalogue ships as an editable JSON asset so shops can extend it without an app update.
 * Entries carry [verified] so an unreviewed community entry never silently drives a price decision.
 */
@Serializable
data class DeviceSpec(
    /** `Build.MODEL` value, matched case-insensitively. */
    val model: String,
    @SerialName("marketing_name") val marketingName: String,
    val brand: String,
    @SerialName("design_capacity_mah") val designCapacityMah: Int? = null,
    @SerialName("ram_gb") val ramGb: List<Int> = emptyList(),
    @SerialName("storage_gb") val storageGb: List<Int> = emptyList(),
    @SerialName("screen_width") val screenWidth: Int? = null,
    @SerialName("screen_height") val screenHeight: Int? = null,
    @SerialName("refresh_hz") val refreshHz: Int? = null,
    val soc: String? = null,
    @SerialName("rear_cameras") val rearCameras: Int? = null,
    @SerialName("front_cameras") val frontCameras: Int? = null,
    @SerialName("launch_year") val launchYear: Int? = null,
    /** False for community-contributed rows that a human has not checked. */
    val verified: Boolean = true,
) {
    val displayName: String get() = "$brand $marketingName"
}

@Serializable
data class DeviceSpecFile(
    val version: Int,
    @SerialName("updated_at") val updatedAt: String,
    val source: String = "",
    val devices: List<DeviceSpec> = emptyList(),
)
