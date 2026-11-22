package ai.opencode.feature.session

import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSessionToolsTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    private val modelSwitched: (Action) -> Boolean = { action ->
        val presented = ((action as? Action.Voice)?.action as? PresentationAction.Presented)?.action
        presented is VoiceFeature.Action.Delegate && presented.action is VoiceFeature.DelegateAction.ModelSwitched
    }

    @Test fun theScreenFollowsOnlyItsOwnSessionsSwitch() = sessionTest { h ->
        val session = OpenCodeSession(id = "ses_fable", serverTitle = "Fable refactor", projectID = "ios", directory = "/Users/kit/opencode-ios", updatedAt = distantPast)
        val state = h.state {
            State.session(server = server, session = session, cachedItems = emptyList())
                .copy(voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live))
        }
        val store = h.store(state)
        store.exhaustive = false
        val opus = OpenCodeModelRef(id = "claude-opus-4-6", providerID = "anthropic", variant = "max")
        store.send(Action.voicePresented(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = "ses_docs", model = opus)))
        assertNull(store.state.currentModel)
        store.send(Action.voicePresented(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = "ses_fable", model = opus)))
        store.receive(modelSwitched, "voice.presented.delegate.modelSwitched")
        assertEquals(opus, store.state.currentModel)
        assertEquals("A visit to a subagent and back keeps it", opus, store.state.session?.model)

        // On a draft, the call's first request creates its session with it.
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val draft = h.state {
            State.draft(server = server, draftProject = project, projects = listOf(project))
                .copy(voice = VoiceFeature.State(server, draftDirectory = "/project", model = null).withPhase(VoiceFeature.State.Phase.Live))
        }
        val draftStore = h.store(draft)
        draftStore.exhaustive = false
        draftStore.send(Action.voicePresented(VoiceFeature.Action.DelegatorSwitchedModel(sessionID = null, model = opus)))
        draftStore.receive(modelSwitched, "voice.presented.delegate.modelSwitched")
        assertEquals(opus, draftStore.state.currentModel)
        assertEquals(opus, draftStore.state.voice?.pendingModel)
    }
}
