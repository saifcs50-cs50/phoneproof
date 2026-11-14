package com.phoneproof.diagnostics.runner

import com.phoneproof.diagnostics.model.ArtifactStore
import com.phoneproof.diagnostics.model.DiagnosticRun
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.InteractionHost
import com.phoneproof.diagnostics.model.PermissionGate
import com.phoneproof.diagnostics.model.ProgressSink
import com.phoneproof.diagnostics.model.RunDepth
import com.phoneproof.diagnostics.model.RunMode
import com.phoneproof.diagnostics.model.SkipReason
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestRecord
import com.phoneproof.diagnostics.model.TestResult
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Live state of a run, consumed by the wizard UI. */
data class RunProgress(
    val started: Boolean = false,
    val finished: Boolean = false,
    val currentTestId: String? = null,
    val currentTitle: String? = null,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val stepFraction: Float = 0f,
    val stepMessage: String = "",
    val records: List<TestRecord> = emptyList(),
) {
    /** Overall 0..1 progress across the whole run. */
    val overallFraction: Float
        get() = if (total == 0) 0f
        else ((currentIndex + stepFraction) / total).coerceIn(0f, 1f)
}

/**
 * Executes a list of [DiagnosticTest]s in dependency order, one at a time.
 *
 * Guarantees the framework relies on:
 *  - every test gets a [TestRecord] with real start time and duration, so a Pass always corresponds
 *    to a dispatched test;
 *  - a thrown exception becomes [SkipReason.ERROR], never a Pass, and never kills the run;
 *  - a missing permission becomes [SkipReason.PERMISSION_DENIED] before the test body executes;
 *  - a test that overruns its timeout becomes [SkipReason.TIMED_OUT].
 */
class DiagnosticRunner(
    private val app: Context,
    private val permissions: PermissionGate,
    private val interaction: InteractionHost,
    private val artifacts: ArtifactStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow(RunProgress())
    val progress: StateFlow<RunProgress> = _progress.asStateFlow()

    private val completed = LinkedHashMap<String, TestRecord>()

    suspend fun run(
        tests: List<DiagnosticTest>,
        mode: RunMode,
        depth: RunDepth,
        runId: String = UUID.randomUUID().toString(),
    ): DiagnosticRun {
        val ordered = TestOrdering.order(tests)
        val startedAt = clock()
        completed.clear()
        _progress.value = RunProgress(started = true, total = ordered.size)

        ordered.forEachIndexed { index, test ->
            _progress.value = _progress.value.copy(
                currentTestId = test.id,
                currentTitle = test.title,
                currentIndex = index,
                stepFraction = 0f,
                stepMessage = "",
            )
            val record = executeOne(test, index)
            completed[test.id] = record
            _progress.value = _progress.value.copy(records = completed.values.toList())
        }

        val finishedAt = clock()
        _progress.value = _progress.value.copy(
            finished = true,
            currentIndex = ordered.size,
            currentTestId = null,
            currentTitle = null,
        )

        return DiagnosticRun(
            runId = runId,
            mode = mode,
            depth = depth,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            records = completed.values.toList(),
            artifacts = artifacts.all(),
        )
    }

    private suspend fun executeOne(test: DiagnosticTest, index: Int): TestRecord {
        val startedAt = clock()

        val result: TestResult = try {
            val denied = test.requiredPermissions.firstOrNull { permission ->
                !permissions.isGranted(permission) &&
                    !permissions.request(permission, test.permissionRationale)
            }
            if (denied != null) {
                TestResult.Skipped(
                    SkipReason.PERMISSION_DENIED,
                    "Needs ${denied.substringAfterLast('.')} permission",
                )
            } else {
                val ctx = TestContext(
                    app = app,
                    permissions = permissions,
                    interaction = interaction,
                    progress = ProgressSink { fraction, message ->
                        _progress.value = _progress.value.copy(
                            currentIndex = index,
                            stepFraction = fraction.coerceIn(0f, 1f),
                            stepMessage = message,
                        )
                    },
                    artifacts = artifacts,
                    recordLookup = { completed[it] },
                )
                withTimeout(timeoutFor(test)) { test.run(ctx) }
            }
        } catch (timeout: TimeoutCancellationException) {
            TestResult.Skipped(SkipReason.TIMED_OUT, "Exceeded ${timeoutFor(test) / 1000}s budget")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            TestResult.Skipped(
                SkipReason.ERROR,
                error.message?.take(160) ?: error::class.java.simpleName,
            )
        }

        return TestRecord(
            testId = test.id,
            title = test.title,
            category = test.category,
            result = result,
            startedAtEpochMs = startedAt,
            durationMs = clock() - startedAt,
            guided = test.requiresUserAction,
        )
    }

    /** Guided tests wait on a human, so they get a far longer ceiling than automatic ones. */
    private fun timeoutFor(test: DiagnosticTest): Long =
        if (test.requiresUserAction) GUIDED_TIMEOUT_MS
        else maxOf(test.estimatedDurationMs * 3, AUTOMATIC_MIN_TIMEOUT_MS)

    private companion object {
        const val GUIDED_TIMEOUT_MS = 5 * 60_000L
        const val AUTOMATIC_MIN_TIMEOUT_MS = 20_000L
    }
}
