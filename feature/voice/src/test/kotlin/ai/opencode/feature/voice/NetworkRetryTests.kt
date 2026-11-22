package ai.opencode.feature.voice

import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.retry.RetryPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class NetworkRetryTests {
    private sealed interface RetryScopeProbe {
        data object Start : RetryScopeProbe
        data object Next : RetryScopeProbe
    }

    @Test fun actionsALoopThatOwnsItsRetriesSendsStartEffectsOutsideIt() = runTest {
        // An effect inherits its starter's task-locals: without care, everything
        // a reply wait's actions start would be sent once, never retried.
        // The loop runs inside an operation's budget; neither reaches them.
        val seen = LockIsolated(listOf<String>())
        val scope: suspend () -> String = {
            val policy = if (NetworkRetry.policy.current() == RetryPolicy.once) "once" else "default"
            val budget = if (NetworkRetry.budget.current() == null) "no budget" else "budget"
            "$policy, $budget"
        }
        val store = TestStore(
            0,
            Reducer<Int, RetryScopeProbe> { state, action ->
                when (action) {
                    RetryScopeProbe.Start -> Reduced(
                        state,
                        runOwningRetries { send ->
                            seen.append(scope())
                            send(RetryScopeProbe.Next)
                        },
                    )
                    RetryScopeProbe.Next -> Reduced(state + 1, Effect.run { seen.append(scope()) })
                }
            },
            this,
        )
        NetworkRetry.budget.withValue(RetryBudget(30.seconds, TestClock())) { store.send(RetryScopeProbe.Start) }
        store.receive(RetryScopeProbe.Next) { 1 }
        store.finish()
        assertEquals(listOf("once, budget", "default, no budget"), seen.value)
    }
}
