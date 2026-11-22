package ai.opencode.feature.session

import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.AutocompleteTrigger
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerAutocompleteTextBridge
import ai.opencode.feature.composer.ComposerTextInputController
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.chip
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.makeStore
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.sampleSkills
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.server
import ai.opencode.feature.session.ComposerAutocompleteTestSupport.session
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A [TextFieldState] stands in for the field and [ComposerTextInputController.syncText] for
 * `updateUIView`'s sync. [Field.type] is the reader typing: it replaces the selection, then
 * publishes like the field's edit collector.
 */
class ComposerTextSyncTests {
    private class Field(
        var presented: String,
        var published: String = presented,
        var hasFocus: Boolean = false,
        var bridge: ComposerAutocompleteTextBridge? = null,
        var focusRequest: Int = 0,
        val onPublish: (String) -> Unit = {},
    ) {
        val state = TextFieldState()
        val controller = ComposerTextInputController()

        init {
            // The field's first composition: the state starts on the parent's draft.
            state.edit { replace(0, length, presented); placeCursorAtEnd() }
            sync()
        }

        val text: String get() = state.text.toString()

        fun sync() {
            controller.syncText(state, presented, currentText = { published }, focusRequest = focusRequest, hasFocus = hasFocus, bridge = bridge)
        }

        fun select(start: Int, end: Int = start) {
            state.edit { selection = TextRange(start, end) }
        }

        fun type(insert: String) {
            state.edit {
                val at = selection
                replace(at.min, at.max, insert)
                selection = TextRange(at.min + insert.length)
            }
            controller.publish(text) { new ->
                onPublish(new)
                published = new
            }
        }
    }

    private fun autocomplete(action: ComposerAutocompleteAction): Action = Action.Autocomplete(action)
    private fun SessionFeature.State.ac(transform: (ComposerAutocompleteState) -> ComposerAutocompleteState) = copy(autocomplete = transform(autocomplete))

    @Test fun choosingASuggestionAtTheEndPlacesTheCaretAfterTheChip() = sessionTest { h ->
        val store = h.makeStore(configureState = {
            it.withDraft("@kit").ac { a ->
                a.copy(
                    activeTrigger = AutocompleteTrigger(mode = AutocompleteTrigger.Mode.Mention, query = "kit", key = "at:0", utf16Start = 0, utf16End = 4),
                    rows = listOf(AutocompleteItem.skill(sampleSkills[0])),
                    selectedRowID = "skill:kit-motion",
                )
            }
        })
        val bridge = ComposerAutocompleteTextBridge()
        bridge.isEnabled = true
        val field = Field(presented = store.state.draft, hasFocus = true, bridge = bridge)
        field.select(4)

        val expectedChip = chip(0, AutocompleteItem.skill(sampleSkills[0]))
        store.send(autocomplete(ComposerAutocompleteAction.ConfirmSelection)) {
            it.withDraft("@kit-motion ").ac { a ->
                a.copy(chips = listOf(expectedChip), chooseGeneration = 1, lastChosenChip = expectedChip, lastChosenRowID = "skill:kit-motion")
                    .closePopover(dismissed = false)
            }
        }
        assertNull("nil means end, not preserve", store.state.autocomplete.caretUTF16Offset)
        bridge.caretUTF16Offset = store.state.autocomplete.caretUTF16Offset
        bridge.chips = store.state.autocomplete.chips
        field.presented = store.state.draft
        field.published = store.state.draft
        field.sync()
        assertEquals(TextRange(12), field.state.selection)
        field.type("x")
        assertEquals("@kit-motion x", field.text)
        store.finish()
    }

    @Test fun dictationIntoAFocusedComposerPlacesTheCaretAfterTheInsertedWords() = sessionTest { h ->
        var state = h.state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }
            .withDraft("Existing draft")
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationBase("Existing draft ")
        state = state.copy(autocomplete = state.autocomplete.setCaret(offset = 3, draftLength = state.draft.length))
        val bridge = ComposerAutocompleteTextBridge()
        bridge.isEnabled = true
        bridge.caretUTF16Offset = state.autocomplete.caretUTF16Offset
        val field = Field(presented = state.draft, hasFocus = true, bridge = bridge)
        field.select(3)

        state = state.showDictation("dictated words")
        assertEquals(0, state.composerFocusRequest)
        assertNull(state.autocomplete.caretUTF16Offset)
        bridge.caretUTF16Offset = state.autocomplete.caretUTF16Offset
        field.presented = state.draft
        field.published = state.draft
        field.sync()
        assertEquals("Existing draft dictated words", field.text)
        assertEquals(TextRange(29), field.state.selection)
        field.type("!")
        assertEquals("Existing draft dictated words!", field.text)
    }

    @Test fun replacingAMiddleWordSurvivesALaggingLayoutSnapshotWithoutAutocomplete() {
        replacingAMiddleWordSurvivesALaggingLayoutSnapshot(autocomplete = false)
    }

    @Test fun replacingAMiddleWordSurvivesALaggingLayoutSnapshotWithAutocomplete() {
        replacingAMiddleWordSurvivesALaggingLayoutSnapshot(autocomplete = true)
    }

    private fun replacingAMiddleWordSurvivesALaggingLayoutSnapshot(autocomplete: Boolean) {
        val bridge = ComposerAutocompleteTextBridge()
        bridge.isEnabled = autocomplete
        val field = Field(presented = "the quick brown fox", bridge = if (autocomplete) bridge else null)

        // Double-tapping "quick" selects this range; the field replaces it before the parent's
        // next composition carries the new draft.
        field.select(4, 9)
        field.type("s")
        assertEquals("the s brown fox", field.published)
        field.sync()
        assertEquals("the s brown fox", field.text)
        assertEquals(TextRange(5), field.state.selection)

        field.presented = field.published
        field.sync()
        field.type("low")
        assertEquals("the slow brown fox", field.text)
        assertEquals("the slow brown fox", field.published)
        assertEquals(TextRange(8), field.state.selection)

        // A still-retained older snapshot cannot undo a later edit either.
        field.sync()
        assertEquals("the slow brown fox", field.text)
        assertEquals(TextRange(8), field.state.selection)

        // Real reducer replacements, including returning to an earlier value, still apply;
        // we don't blacklist strings that were once typed.
        field.published = "the quick brown fox"
        field.sync()
        assertEquals(field.published, field.text)
        field.published = ""
        field.sync()
        assertTrue(field.text.isEmpty())
        assertEquals(TextRange(0), field.state.selection)
    }

    @Test fun layoutReenteringBeforeTheBindingCommitsCannotOverwriteTheEdit() {
        var field: Field? = null
        // The parent recomposes (and the field syncs) before it takes the new draft.
        field = Field(presented = "the quick brown fox", onPublish = { field?.sync() })
        field.select(4, 9)
        field.type("s")
        field.type("low")
        assertEquals("the slow brown fox", field.text)
        assertEquals("the slow brown fox", field.published)
        assertEquals(TextRange(8), field.state.selection)
    }

    @Test fun focusedProgrammaticChangesPreserveAndClampSelectionUnlessFocusWasRequested() {
        val field = Field(presented = "🐈 quick brown fox", hasFocus = true)
        field.select(3, 8)

        // Echoing equal text does not collapse a selection, even with an old autocomplete caret
        // value (a selection is represented there as zero).
        val bridge = ComposerAutocompleteTextBridge()
        bridge.isEnabled = true
        bridge.caretUTF16Offset = 0
        field.bridge = bridge
        field.sync()
        assertEquals(TextRange(3, 8), field.state.selection)
        field.bridge = null

        field.published += "!"
        field.sync()
        assertEquals(TextRange(3, 8), field.state.selection)
        assertFalse(field.controller.pinsToEnd)
        field.published = "🐈 hi"
        field.sync()
        assertEquals(TextRange(3, 5), field.state.selection)
        field.published = "🐈"
        field.sync()
        assertEquals(TextRange(2), field.state.selection)

        // Quote/restore's explicit focus request places the caret at the end.
        field.published = "Quoted text"
        field.bridge = bridge
        field.focusRequest += 1
        field.sync()
        assertEquals(TextRange(11), field.state.selection)
        assertTrue(field.controller.pinsToEnd)
    }
}
