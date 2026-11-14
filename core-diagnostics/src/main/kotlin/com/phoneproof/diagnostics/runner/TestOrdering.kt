package com.phoneproof.diagnostics.runner

import com.phoneproof.diagnostics.model.DiagnosticTest

/**
 * Orders tests so that anything declared in [DiagnosticTest.dependsOn] runs first.
 *
 * Kept free of Android types so the ordering rules can be unit-tested on the JVM — this logic
 * decides whether the counterfeit check ever sees the identity results, so it is worth testing
 * properly rather than only observing on a device.
 */
object TestOrdering {

    /**
     * Depth-first topological sort that preserves catalogue order for independent tests.
     *
     * Unknown and cyclic dependencies are tolerated rather than fatal: a broken declaration should
     * degrade one test, never prevent the whole diagnostic from running.
     */
    fun order(tests: List<DiagnosticTest>): List<DiagnosticTest> {
        val byId = tests.associateBy { it.id }
        val ordered = LinkedHashSet<DiagnosticTest>()
        val visiting = HashSet<String>()

        fun visit(test: DiagnosticTest) {
            if (test in ordered) return
            if (!visiting.add(test.id)) return // cycle — stop descending, keep the test
            test.dependsOn.forEach { dependencyId -> byId[dependencyId]?.let(::visit) }
            visiting.remove(test.id)
            ordered.add(test)
        }

        tests.forEach(::visit)
        return ordered.toList()
    }
}
