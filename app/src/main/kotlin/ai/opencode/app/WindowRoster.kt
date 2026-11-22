package ai.opencode.app

import java.lang.ref.WeakReference

/** A window's part in the app, decided by [WindowRoster]. */
interface WindowRosterMember {
    /** The window's role changed after it joined. */
    fun rosterAssigned(role: WindowRoster.Role)

    /** The window gained or lost the app-wide work ([AppFeature.Action.isAppWide]), which [AppWideWork] runs. */
    fun rosterOwnsAppWideWork(owns: Boolean)
}

/**
 * Driven by windows connecting and disconnecting. The first connected window is primary; on a
 * tablet others are secondary with their own store. The app-wide owner is the connected primary,
 * else the first connected secondary.
 */
class WindowRoster(
    /** The device has several windows (a tablet). */
    val multipleWindows: Boolean,
) {
    enum class Role { Primary, Secondary }

    private class Entry(member: WindowRosterMember, var role: Role) {
        val member = WeakReference(member)
        var connected = true
        var ownsAppWide = false
        val isConnectedPrimary: Boolean get() = role == Role.Primary && connected
    }

    private val entries = ArrayList<Entry>()

    /** After every change, even one that leaves the owner where it was: the owner may show another store now ([AppWideWork]). */
    var onSettle: (() -> Unit)? = null

    val members: List<WindowRosterMember> get() = entries.mapNotNull { it.member.get() }

    val connectedMembers: List<WindowRosterMember> get() = entries.filter { it.connected }.mapNotNull { it.member.get() }
    val primary: WindowRosterMember? get() = entries.firstOrNull { it.isConnectedPrimary }?.member?.get()
    val appWideOwner: WindowRosterMember? get() = entries.firstOrNull { it.ownsAppWide }?.member?.get()

    /** A window's root appeared. Its role is returned, not sent. */
    fun join(member: WindowRosterMember): Role {
        purge()
        index(member)?.let { return entries[it].role }
        // One window: whatever was there is stale.
        if (!multipleWindows) entries.clear()
        val role = if (primary != null) Role.Secondary else Role.Primary
        entries += Entry(member, role)
        settle()
        return role
    }

    /**
     * The window's view is in a task. Members that were in that task already are stale and
     * leave; their primary role passes to [member].
     */
    fun attached(member: WindowRosterMember, replacing: List<WindowRosterMember> = emptyList()) {
        purge()
        var inheritsPrimary = false
        for (old in replacing) {
            if (old === member) continue
            val index = index(old) ?: continue
            if (entries[index].isConnectedPrimary) inheritsPrimary = true
            entries.removeAt(index)
        }
        val index = index(member) ?: return settle()
        entries[index].connected = true
        val othersHavePrimary = entries.indices.any { it != index && entries[it].isConnectedPrimary }
        if (inheritsPrimary) {
            assign(Role.Primary, index)
        } else if (entries[index].role == Role.Primary && othersHavePrimary) {
            assign(Role.Secondary, index)
        }
        settle()
    }

    /** The window's task went. */
    fun detached(member: WindowRosterMember) {
        purge()
        val index = index(member) ?: return
        if (!entries[index].connected) return
        entries[index].connected = false
        settle()
    }

    /** A window's state went away. */
    fun left(member: WindowRosterMember) {
        index(member)?.let { entries.removeAt(it) }
        purge()
        settle()
    }

    private fun index(member: WindowRosterMember): Int? = entries.indexOfFirst { it.member.get() === member }.takeIf { it >= 0 }

    private fun purge() {
        entries.removeAll { it.member.get() == null }
    }

    private fun assign(role: Role, index: Int) {
        if (entries[index].role == role) return
        entries[index].role = role
        entries[index].member.get()?.rosterAssigned(role)
    }

    private fun settle() {
        val owner = entries.indexOfFirst { it.isConnectedPrimary }.takeIf { it >= 0 }
            ?: entries.indexOfFirst { it.connected }.takeIf { it >= 0 }
        val changed = ArrayList<Pair<WindowRosterMember, Boolean>>()
        for (index in entries.indices) {
            val owns = index == owner
            if (entries[index].ownsAppWide == owns) continue
            entries[index].ownsAppWide = owns
            entries[index].member.get()?.let { changed += it to owns }
        }
        // Losses first, so two windows never both think they own it.
        for ((member, owns) in changed.sortedBy { it.second }) member.rosterOwnsAppWideWork(owns)
        onSettle?.invoke()
    }
}
