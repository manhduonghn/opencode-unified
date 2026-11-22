package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeProject
import org.junit.Assert.fail
import org.junit.Test

class FirstComputerPairingTests {
    private fun project(directory: String, name: String? = null) = OpenCodeProject(id = directory, name = name ?: directory, directory = directory)

    // region Starter prompts

    @Test fun aStarterPromptIsTypedInForThePersonToSend() = sessionTest { h ->
        val state = h.state {
            SessionFeature.State.draft(server = testServer, draftProject = project("/web"), projects = listOf(project("/web")))
        }.copy(starterPrompts = SessionFeature.State.firstSessionStarters)
        val prompt = SessionFeature.State.firstSessionStarters[0]
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(sendPrompt = { _, _, _, _, _, _, _ -> fail("A starter prompt must not send") })
        }
        store.send(Action.StarterPromptTapped(prompt)) {
            it.copy(draft = prompt, composerFocusRequest = 1, starterPrompts = emptyList())
        }
        // Retired: a second tap finds nothing listed.
        store.send(Action.StarterPromptTapped(SessionFeature.State.firstSessionStarters[1]))
        store.finish()
    }

    @Test fun typingRetiresTheStarterPromptsForGood() = sessionTest { h ->
        val state = h.state {
            SessionFeature.State.draft(server = testServer, draftProject = project("/web"), projects = listOf(project("/web")))
        }.copy(starterPrompts = SessionFeature.State.firstSessionStarters)
        val store = h.store(state)
        store.send(Action.Binding(BindingAction.Draft("Hi"))) { it.copy(draft = "Hi", starterPrompts = emptyList()) }
        // Erasing it doesn't bring them back.
        store.send(Action.Binding(BindingAction.Draft(""))) { it.copy(draft = "") }
        store.finish()
    }

    // endregion
}
