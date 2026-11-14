package com.phoneproof.diagnostics.model

/**
 * The contract every hardware check implements. One class per test, registered in
 * [com.phoneproof.diagnostics.registry.TestCatalogue].
 *
 * Implementations must never return [TestResult.Pass] speculatively: if the measurement could not
 * be taken, return [TestResult.Unsupported] with the concrete reason (missing sysfs node, API level,
 * absent hardware). Throwing is also safe — the runner converts an exception into a
 * [TestResult.Skipped] with [SkipReason.ERROR] rather than letting a run die.
 */
interface DiagnosticTest {
    /** Stable identifier, e.g. `battery.health`. Persisted in reports; never change it. */
    val id: String

    val title: String

    val category: Category

    /** True when a human must physically do something (touch, rotate, cover a sensor, speak). */
    val requiresUserAction: Boolean get() = false

    /** Runtime permissions this test needs. Requested immediately before the test runs. */
    val requiredPermissions: List<String> get() = emptyList()

    /** Rationale shown when asking for [requiredPermissions]. One short line. */
    val permissionRationale: String? get() = null

    /** Rough budget used to show progress and keep the full run inside its time target. */
    val estimatedDurationMs: Long get() = 1_000L

    /**
     * Tests whose results this one reads (via [TestContext.recordOf]). The runner orders execution
     * so dependencies complete first.
     */
    val dependsOn: List<String> get() = emptyList()

    suspend fun run(ctx: TestContext): TestResult
}
