package com.phoneproof.diagnostics.model

import kotlinx.serialization.Serializable

/** A file produced during a run: condition photos, camera captures, audio spectra. */
@Serializable
data class Artifact(
    val id: String,
    val kind: ArtifactKind,
    val absolutePath: String,
    val label: String,
    val testId: String,
)

@Serializable
enum class ArtifactKind { CONDITION_PHOTO, CAMERA_SAMPLE, LDI_STICKER, IMEI_PHOTO }
