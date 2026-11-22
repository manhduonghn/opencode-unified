package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pushing and popping a subagent flips the session screen's `isCovered`, and the composer's text
 * field must survive it (a recreated field drops the keyboard and the draft's selection). Android
 * hosts it in a Robolectric activity and compares the composer `BasicTextField`'s semantics node
 * id, which lives as long as its layout node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerIdentityTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun pushingAndPoppingSubagentKeepsComposerStructuralIdentity() {
        val state = makeSessionState(
            id = "ses_identity",
            title = "Identity check",
            isRunning = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Queued", delivery = OpenCodeDelivery.Queue)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        // The test sends the toggled state as `PreviewStateReplaced`, and every other action is
        // ignored.
        val sessionStore = Store(state, Reducer<SessionFeature.State, SessionFeature.Action> { current, action ->
            if (action is SessionFeature.Action.PreviewStateReplaced) {
                Reduced(action.state)
            } else {
                Reduced(current)
            }
        }, scope = scope)
        fun toggleCovered() {
            sessionStore.send(SessionFeature.Action.PreviewStateReplaced(sessionStore.state.copy(isCovered = !sessionStore.state.isCovered)))
        }
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        try {
            view.setContent { OpenCodeAppTheme { SessionView(store = sessionStore) } }
            pump()
            val initialTextID = findTextField(view)?.id
            assertNotNull("The composer's text field is on screen", initialTextID)

            // Push subagent (`isCovered` flips false -> true).
            toggleCovered()
            pump()
            assertEquals(true, sessionStore.state.isCovered)
            assertEquals("Pushing a subagent must not recreate the composer's text field", initialTextID, findTextField(view)?.id)

            // Pop subagent (`isCovered` flips true -> false).
            toggleCovered()
            pump()
            assertEquals(false, sessionStore.state.isCovered)
            assertEquals("Popping a subagent must not recreate the composer's text field", initialTextID, findTextField(view)?.id)
        } finally {
            activity.finish()
            scope.cancel()
        }
    }

    /**
     * Recreating the activity for a theme or font-scale change keeps the retained store, so
     * `SessionLifecycle` must neither re-send `Action.Task` nor treat the recreated activity's
     * `ON_START` as the screen leaving.
     */
    @Test fun activityRecreationDoesNotRestartSessionTaskOrDropVisibility() {
        val recorded = mutableListOf<SessionFeature.Action>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val initial = makeSessionState(id = "ses_recreate", title = "Streaming", isRunning = true)
        val sessionStore = Store(initial, Reducer<SessionFeature.State, SessionFeature.Action> { current, action ->
            if (action is SessionFeature.Action.PreviewStateReplaced) {
                Reduced(action.state)
            } else {
                recorded += action
                when (action) {
                    is SessionFeature.Action.VisibilityChanged -> Reduced(current.copy(isVisible = action.isVisible))
                    is SessionFeature.Action.SceneActiveChanged -> Reduced(current.copy(isSceneActive = action.isActive))
                    else -> Reduced(current)
                }
            }
        }, scope = scope)
        val app = RuntimeEnvironment.getApplication()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? ComponentActivity)?.setContent { SessionLifecycle(sessionStore) }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        try {
            controller.setup()
            pump()
            assertEquals(1, recorded.count { it == SessionFeature.Action.Task })
            assertEquals(true, sessionStore.state.isVisible)
            assertEquals(
                emptyList<SessionFeature.Action>(),
                recorded.filter { it == SessionFeature.Action.VisibilityChanged(false) || it == SessionFeature.Action.SceneEnteredBackground },
            )

            recorded.clear()
            controller.recreate()
            pump()
            assertEquals("Activity recreation must not re-send SessionFeature.Action.Task", 0, recorded.count { it == SessionFeature.Action.Task })
            assertEquals(
                "Activity recreation must not send VisibilityChanged(false) or SceneEnteredBackground",
                emptyList<SessionFeature.Action>(),
                recorded.filter {
                    it == SessionFeature.Action.VisibilityChanged(false) ||
                        it == SessionFeature.Action.SceneActiveChanged(false) ||
                        it == SessionFeature.Action.SceneEnteredBackground
                },
            )

            // Switching to a new visit on the same store still starts that screen's task.
            recorded.clear()
            sessionStore.send(SessionFeature.Action.PreviewStateReplaced(sessionStore.state.copy(visit = sessionStore.state.visit + 1)))
            pump()
            assertEquals("A new taskIdentity still starts Action.Task once", 1, recorded.count { it == SessionFeature.Action.Task })
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            controller.pause().stop().destroy()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Lets a few frames run. */
    private fun pump() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
        }
    }

    /** The composer's editable field, found through the semantics tree. */
    private fun findTextField(view: ComposeView): SemanticsNode? {
        val root = findRoot(view) ?: return null
        return findEditable(root.semanticsOwner.unmergedRootSemanticsNode)
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findRoot(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findEditable(node: SemanticsNode): SemanticsNode? {
        if (node.config.getOrNull(SemanticsProperties.EditableText) != null) return node
        for (child in node.children) {
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun makeSessionState(
        id: String,
        title: String,
        isRunning: Boolean = false,
        queuedInputs: List<OpenCodePendingInput> = emptyList(),
    ): SessionFeature.State {
        val state = SessionFeature.State.session(
            server = testServer,
            session = testSession(id = id, title = title),
            cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Hello")),
            isRunning = isRunning,
        )
        return state.copy(readsOnView = false, queuedInputs = queuedInputs)
    }
}
