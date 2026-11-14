package com.phoneproof.diagnostics

import com.phoneproof.diagnostics.model.Category
import com.phoneproof.diagnostics.model.DiagnosticTest
import com.phoneproof.diagnostics.model.TestContext
import com.phoneproof.diagnostics.model.TestResult
import com.phoneproof.diagnostics.runner.TestOrdering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestOrderingTest {

    private class FakeTest(
        override val id: String,
        override val dependsOn: List<String> = emptyList(),
    ) : DiagnosticTest {
        override val title = id
        override val category = Category.IDENTITY
        override suspend fun run(ctx: TestContext): TestResult = TestResult.Unsupported("fake")
    }

    @Test
    fun `dependencies run before dependants`() {
        val identity = FakeTest("identity")
        val display = FakeTest("display")
        val counterfeit = FakeTest("counterfeit", dependsOn = listOf("identity", "display"))

        val ordered = TestOrdering.order(listOf(counterfeit, identity, display)).map { it.id }

        assertTrue(ordered.indexOf("identity") < ordered.indexOf("counterfeit"))
        assertTrue(ordered.indexOf("display") < ordered.indexOf("counterfeit"))
    }

    @Test
    fun `independent tests keep catalogue order`() {
        val tests = listOf(FakeTest("a"), FakeTest("b"), FakeTest("c"))
        assertEquals(listOf("a", "b", "c"), TestOrdering.order(tests).map { it.id })
    }

    @Test
    fun `unknown dependency is ignored rather than fatal`() {
        val test = FakeTest("counterfeit", dependsOn = listOf("does-not-exist"))
        assertEquals(listOf("counterfeit"), TestOrdering.order(listOf(test)).map { it.id })
    }

    @Test
    fun `cycle does not drop tests or hang`() {
        val a = FakeTest("a", dependsOn = listOf("b"))
        val b = FakeTest("b", dependsOn = listOf("a"))

        val ordered = TestOrdering.order(listOf(a, b)).map { it.id }

        assertEquals(2, ordered.size)
        assertTrue(ordered.containsAll(listOf("a", "b")))
    }

    @Test
    fun `every test appears exactly once`() {
        val tests = listOf(
            FakeTest("identity"),
            FakeTest("counterfeit", dependsOn = listOf("identity")),
            FakeTest("battery", dependsOn = listOf("identity")),
        )
        val ordered = TestOrdering.order(tests).map { it.id }
        assertEquals(ordered.size, ordered.toSet().size)
        assertEquals(3, ordered.size)
    }
}
