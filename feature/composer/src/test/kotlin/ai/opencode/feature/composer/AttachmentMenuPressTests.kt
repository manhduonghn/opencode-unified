package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentMenuPressTests {
    @Test fun theDismissAreaIsMountedOnlyForWhatIsDrawn() {
        assertNull(ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = true, autocompleteDrawn = false))
        assertEquals(ComposerDismissTarget.Autocomplete, ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = true, autocompleteDrawn = true))
        assertEquals(ComposerDismissTarget.AttachmentMenu, ComposerDismissTarget.current(attachmentMenuOpen = true, autocompleteOpen = true, autocompleteDrawn = false))
        assertNull(ComposerDismissTarget.current(attachmentMenuOpen = false, autocompleteOpen = false, autocompleteDrawn = true))
    }

    @Test fun thePlusTurnsOnThePanelRoles() {
        assertEquals(Motion.panelOpen.animation<Float>(), AttachmentMenuMotion.open<Float>(reduceMotion = false))
        assertEquals(Motion.panelClose.animation<Float>(), AttachmentMenuMotion.close<Float>(reduceMotion = false))
        assertEquals(Motion.reduced.animation<Float>(reduceMotion = true), AttachmentMenuMotion.close<Float>(reduceMotion = true))
    }
}
