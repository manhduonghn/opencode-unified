package ai.opencode.core.testing

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.tuning.AnimationTimeScale
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestIsolationGuardTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun setScale(scale: Double) = AnimationTimeScale.shared.setScale(scale, bypassAccessCheck = true)

    @Test
    fun catchesAndResetsDisabledAnimationsAndTimeScale() = runTest {
        val violations = TestIsolationGuard.capturingViolations("leakingAnimationsTest()") {
            setScale(0.25)
        }
        assertTrue(violations.toString(), violations.any { it.contains("AnimationTimeScale.shared.scale") && it.contains("0.25") })
        assertEquals(1.0, AnimationTimeScale.shared.scale, 0.0)
    }

    @Test
    fun catchesAndResetsAmbientSharedMutation() = runTest {
        val violations = TestIsolationGuard.capturingViolations("leakingSharedPinTest()") {
            shared(SharedKeys.pinnedSessionKeys).set(listOf("leaked-pin-key"))
            shared(SharedKeys.developerMode).set(true)
        }
        assertTrue(violations.toString(), violations.any { it.contains("Shared.pinnedSessionKeys") && it.contains("leaked-pin-key") })
        assertTrue(violations.toString(), violations.any { it.contains("Shared.developerMode") && it.contains("true") })
        DependencyValues.withValues(DependencyValues.root) {
            assertTrue(shared(SharedKeys.pinnedSessionKeys).value.isEmpty())
            assertFalse(shared(SharedKeys.developerMode).value)
        }
    }

    @Test
    fun interleavingAsyncScopesSerializeAndNestedScopesDoNotResetLiveState() = runTest {
        val gate = TestIsolationGuard.ScopeGate()
        val timeline = mutableListOf<String>()

        val first = async {
            TestIsolationGuard.capturingViolations("firstInterleavingAsyncTest()", gate) {
                timeline += "first:start"
                setScale(0.25)
                delay(25)
                assertEquals(0.25, AnimationTimeScale.shared.scale, 0.0)
                AnimationTimeScale.shared.reset()
                timeline += "first:end"
            }
        }
        val second = async {
            TestIsolationGuard.capturingViolations("secondInterleavingAsyncTest()", gate) {
                timeline += "second:start"
                setScale(0.5)
                delay(25)
                assertEquals(0.5, AnimationTimeScale.shared.scale, 0.0)
                AnimationTimeScale.shared.reset()
                timeline += "second:end"
            }
        }
        assertTrue(first.await().isEmpty())
        assertTrue(second.await().isEmpty())
        assertTrue(
            timeline.toString(),
            timeline == listOf("first:start", "first:end", "second:start", "second:end") ||
                timeline == listOf("second:start", "second:end", "first:start", "first:end"),
        )

        val outerViolations = TestIsolationGuard.capturingViolations("outerTestWithNestedScope()") {
            setScale(0.5)
            val nested = TestIsolationGuard.beginTest("nestedChildScope()", isTopLevelScope = false)
            delay(10)
            val nestedViolations = TestIsolationGuard.endTest(nested)
            assertTrue("Nested scope must not check probes while outer scope is active", nestedViolations.isEmpty())
            assertEquals("Nested scope must not reset live state under outer scope", 0.5, AnimationTimeScale.shared.scale, 0.0)
            AnimationTimeScale.shared.reset()
        }
        assertTrue(outerViolations.isEmpty())
    }

    @Test
    fun parameterizedAsyncCasesDoNotInterleaveAcrossSuspensionPoints() = runTest {
        for (scale in listOf(0.25, 0.75)) {
            setScale(scale)
            try {
                delay(20)
                assertEquals(scale, AnimationTimeScale.shared.scale, 0.0)
            } finally {
                AnimationTimeScale.shared.reset()
            }
        }
    }
}
