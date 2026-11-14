package com.phoneproof.diagnostics.model

import android.content.Context

/**
 * Everything a test is allowed to reach for. Passing this explicitly (rather than letting tests grab
 * singletons) is what makes each test unit-testable with a fake host.
 */
class TestContext(
    val app: Context,
    val permissions: PermissionGate,
    val interaction: InteractionHost,
    val progress: ProgressSink,
    val artifacts: ArtifactStore,
    private val recordLookup: (String) -> TestRecord?,
) {
    /** Result of an already-completed test, for tests that cross-reference (e.g. counterfeit check). */
    fun recordOf(testId: String): TestRecord? = recordLookup(testId)
}

/** Asks the user for a runtime permission at the moment the test needs it. */
interface PermissionGate {
    fun isGranted(permission: String): Boolean

    /**
     * Suspends while the system dialog is shown. Returns false if the user declines, in which case
     * the test must return [TestResult.Skipped] with [SkipReason.PERMISSION_DENIED].
     */
    suspend fun request(permission: String, rationale: String?): Boolean
}

/** Reports intra-test progress so long tests (thermal, storage) do not look frozen. */
fun interface ProgressSink {
    /** [fraction] in 0..1, [message] a short status line. */
    fun update(fraction: Float, message: String)
}

/** Somewhere to persist files produced during a run. */
interface ArtifactStore {
    /** Creates an empty file for [testId] with [extension] and returns it. */
    fun newFile(testId: String, extension: String): java.io.File

    fun register(artifact: Artifact)

    fun all(): List<Artifact>
}

/**
 * Bridge between a guided test and the Compose UI.
 *
 * The test declares what interaction it needs; the wizard renders the matching screen, collects raw
 * data, and hands measurements back. Pass/fail thresholds stay in the test class so they stay
 * unit-testable — the UI only gathers evidence.
 */
interface InteractionHost {
    suspend fun await(step: GuidedStep): GuidedOutcome
}

/** A request for user interaction. [kind] selects which screen the wizard shows. */
data class GuidedStep(
    val testId: String,
    val kind: GuidedKind,
    val title: String,
    val instruction: String,
    val params: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000L,
)

enum class GuidedKind {
    TOUCH_GRID,
    DISPLAY_SWEEP,
    MULTITOUCH,
    SPEAKER_LOOPBACK,
    EARPIECE_LOOPBACK,
    MICROPHONE,
    CAMERA_CAPTURE,
    BUTTONS,
    VIBRATION,
    FINGERPRINT,
    FACE_UNLOCK,
    PROXIMITY,
    LIGHT_SENSOR,
    MOTION_SENSORS,
    CONDITION_PHOTOS,
    IMEI_ENTRY,
    CONFIRM,
}

/** What the wizard screen hands back. */
sealed interface GuidedOutcome {
    data class Completed(
        val measurements: List<Measurement>,
        val findings: List<Finding> = emptyList(),
        val artifacts: List<Artifact> = emptyList(),
    ) : GuidedOutcome

    data class Skipped(val reason: SkipReason, val detail: String? = null) : GuidedOutcome

    data class Unsupported(val reason: String) : GuidedOutcome
}
