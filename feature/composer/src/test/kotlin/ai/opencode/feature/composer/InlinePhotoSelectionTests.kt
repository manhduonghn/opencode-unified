package ai.opencode.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Items are strings standing in for the picker's `Uri`s, which the bookkeeping only compares. */
class InlinePhotoSelectionTests {
    private val first = "first"
    private val second = "second"

    private fun selection() = InlinePhotoSelection<String> { false }

    @Test fun picksGetStableIDsInTapOrder() {
        val selection = selection()
        selection.items = listOf(first)
        val added = selection.reconcile().added
        selection.items = listOf(first, second)
        val change = selection.reconcile()

        assertEquals(listOf(first), added.map { it.item })
        assertEquals(listOf(second), change.added.map { it.item })
        assertTrue(change.removed.isEmpty())
        assertEquals(listOf(added[0].id, change.added[0].id), selection.selectedImages.map { it.id })
    }

    @Test fun deselectingReportsTheAttachmentToRemove() {
        val selection = selection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }

        selection.items = listOf(second)
        val change = selection.reconcile()

        assertTrue(change.added.isEmpty())
        assertEquals(listOf(ids[0]), change.removed)
    }

    @Test fun removingALandedChipDeselectsItsItem() {
        val selection = selection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        assertTrue(selection.attachmentsChanged(ids.toSet()).isEmpty())

        val orphans = selection.attachmentsChanged(setOf(ids[1]))

        assertTrue(orphans.isEmpty())
        assertEquals(listOf(second), selection.items)
        assertEquals(listOf(ids[0]), selection.reconcile().removed)
    }

    @Test fun anItemStillImportingStaysSelected() {
        val selection = selection()
        selection.items = listOf(first)
        selection.reconcile()

        assertTrue(selection.attachmentsChanged(emptySet()).isEmpty())
        assertEquals(listOf(first), selection.items)
    }

    @Test fun anAttachmentLandingAfterItsDeselectionIsAnOrphan() {
        val selection = selection()
        selection.items = listOf(first)
        val id = selection.reconcile().added[0].id
        selection.items = emptyList()
        selection.reconcile()

        assertEquals(listOf(id), selection.attachmentsChanged(setOf(id)))
        assertTrue("An orphan is reported once", selection.attachmentsChanged(setOf(id)).isEmpty())
    }

    @Test fun sendingClearsTheSelection() {
        val selection = selection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        selection.attachmentsChanged(ids.toSet())

        selection.attachmentsChanged(emptySet())

        assertTrue(selection.items.isEmpty())
    }

    @Test fun failedImportsAreDeselected() {
        val selection = selection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }

        selection.drop(setOf(ids[1]))

        assertEquals(listOf(first), selection.items)
    }
}
