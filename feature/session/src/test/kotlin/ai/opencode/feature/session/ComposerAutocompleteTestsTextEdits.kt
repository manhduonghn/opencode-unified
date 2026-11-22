package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.makeStore
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleSkills
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.session
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A keystroke's single action and the draft's shape (perf audit finding 6). */
class ComposerAutocompleteTestsTextEdits {
    private fun Harness.seeded() = makeStore(
        exhaustive = false,
        configureState = {
            it.copy(autocomplete = it.autocomplete.copy(loadedCatalogDirectoryKey = session.directory, skills = sampleSkills))
        },
        configureDependencies = {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(findFiles = { _, _, _, _ -> emptyList() })
        },
    )

    /**
     * A keystroke is one action (`TextEdited`): the draft and the caret it left land in one pass,
     * exactly as the binding and `CaretChanged` did.
     */
    @Test fun aTypedEditAndItsCaretLandInOnePassAsTheBindingAndCaretDid() = sessionTest { h ->
        val apart = h.seeded()
        apart.send(Action.Binding(BindingAction.Draft("hello @kit world")))
        apart.send(Action.Autocomplete(ComposerAutocompleteAction.CaretChanged(10)))
        val together = h.seeded()
        together.send(
            Action.Autocomplete(
                ComposerAutocompleteAction.TextEdited("hello @kit world", ComposerAutocompleteAction.CaretUpdate.Moved(10)),
            ),
        )
        assertEquals("hello @kit world", together.state.draft)
        assertEquals(DraftShape("hello @kit world"), together.state.draftShape)
        assertEquals(apart.state.autocomplete, together.state.autocomplete)
        assertEquals("kit", together.state.autocomplete.activeTrigger?.query)

        // Typing on at the end moves the caret with the draft: no caret update.
        val plainApart = h.seeded()
        plainApart.send(Action.Binding(BindingAction.Draft("hi")))
        val plain = h.seeded()
        plain.send(Action.Autocomplete(ComposerAutocompleteAction.TextEdited("hi", ComposerAutocompleteAction.CaretUpdate.Unchanged)))
        assertEquals(plainApart.state.autocomplete, plain.state.autocomplete)
        assertTrue(plain.state.draftShape.hasText)
        h.skipInFlightEffects()
    }

    /** The draft's shape changes only when it fills or empties, or turns blank. */
    @Test fun draftShapeFollowsEmptinessAndBlankness() {
        assertEquals(DraftShape(), DraftShape(""))
        assertEquals(DraftShape(isEmpty = false), DraftShape(" \n\t"))
        assertTrue(DraftShape("a").hasText)
        assertTrue(DraftShape("\u00A0x\u2003").hasText)
        assertFalse("Like trimming whitespace and newlines", DraftShape("\u00A0\u2003").hasText)
    }
}
