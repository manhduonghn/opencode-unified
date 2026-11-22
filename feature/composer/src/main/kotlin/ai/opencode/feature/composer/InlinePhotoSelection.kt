package ai.opencode.feature.composer

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * The inline photo picker's continuous selection, mirrored onto composer attachments. Every picked
 * item gets a stable ID that becomes its image attachment's or video upload's ID, so either side
 * can remove the other: deselecting removes the chip, and removing the chip deselects the item.
 *
 * Items are the picker's content [Uri]s. [items] is Compose state, so the grid observes it; it is
 * ordered as the user tapped.
 */
@Stable
class InlinePhotoSelection<Item>(private val isVideo: (Item) -> Boolean) {
    data class Pick<Item>(val item: Item, val id: UUID, val isVideo: Boolean)

    data class Change<Item>(val added: List<Pick<Item>> = emptyList(), val removed: List<UUID> = emptyList())

    /** Bound to the picker. Ordered as the user tapped. */
    var items: List<Item> by mutableStateOf(emptyList())

    private val ids = LinkedHashMap<Item, UUID>()
    private var reconciled: List<Item> = emptyList()
    /** IDs that have shown up as composer attachments at least once. */
    private val landed = mutableSetOf<UUID>()
    /** IDs deselected before their import finished; removed once they arrive. */
    private val unlandedRemovals = mutableSetOf<UUID>()

    val selectedImages: List<Pick<Item>> get() = items.mapNotNull(::pick).filter { !it.isVideo }

    /** What changed since the last call, in tap order. */
    fun reconcile(): Change<Item> {
        val added = mutableListOf<Pick<Item>>()
        val removed = mutableListOf<UUID>()
        val current = items.toSet()
        for (item in reconciled) {
            if (item in current) continue
            val id = ids.remove(item) ?: continue
            if (!landed.remove(id)) unlandedRemovals += id
            removed += id
        }
        val previous = reconciled.toSet()
        for (item in items) {
            if (item in previous) continue
            val id = UUID.randomUUID()
            ids[item] = id
            added += Pick(item, id, isVideo(item))
        }
        reconciled = items
        return Change(added, removed)
    }

    /**
     * Deselects items whose attachment was removed in the composer (or sent). Items still
     * importing are kept: their attachment hasn't landed yet. Returns attachments whose item
     * was deselected before they landed; the composer removes those.
     */
    fun attachmentsChanged(present: Set<UUID>): List<UUID> {
        val selected = ids.values.toSet()
        landed += selected.intersect(present)
        val gone = ids.filter { it.value in landed && it.value !in present }.keys
        if (gone.isNotEmpty()) items = items.filter { it !in gone }
        val orphans = unlandedRemovals.intersect(present)
        unlandedRemovals -= orphans
        return orphans.toList()
    }

    /** Deselects items whose import failed, so the picker never shows a selection that has no chip. */
    fun drop(failed: Set<UUID>) {
        if (failed.isEmpty()) return
        items = items.filter { item -> ids[item]?.let { it in failed } != true }
    }

    private fun pick(item: Item): Pick<Item>? = ids[item]?.let { Pick(item, it, isVideo(item)) }
}

/** The picker's selection over content [Uri]s, typed by the resolver. */
fun InlinePhotoSelection(): InlinePhotoSelection<Uri> = InlinePhotoSelection { ComposerPickers.isVideo(it) }
