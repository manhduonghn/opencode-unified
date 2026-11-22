package ai.opencode.core.store

import ai.opencode.core.log.logSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationCancellationTest {
    @Test fun unhandledErrorSummaryContainsOnlyClassChainAndTopFrame() {
        val cause = IllegalArgumentException("https://private.invalid/secret")
        val error = IllegalStateException("/private/server/path", cause)
        cause.initCause(error) // A cyclic cause chain must terminate too.
        error.addSuppressed(Exception("private suppressed message"))
        error.stackTrace = arrayOf(
            StackTraceElement("Client", "request", "/private/source.kt", 42),
            StackTraceElement("PrivateSecondFrame", "secret", "secret.kt", 7),
        )
        assertEquals("IllegalStateException <- IllegalArgumentException at Client.request:42", error.logSummary)
        error.stackTrace = emptyArray()
        assertEquals("IllegalStateException <- IllegalArgumentException", error.logSummary)
    }

    private data object WorkID
    private data object DetailSlot
    private data object StackSlot

    private data class Child(val id: Int, val ticks: Int = 0)
    private enum class ChildAction { Start, Cancel, Tick }
    private data class State(
        val detail: Child? = Child(0),
        val stack: List<Child> = listOf(Child(1), Child(2)),
    )
    private sealed interface Action {
        data class Detail(val action: PresentationAction<ChildAction>) : Action
        data class Element(val id: Int, val action: ChildAction) : Action
        data class Remove(val id: Int) : Action
        data object StartParent : Action
        data object CancelParent : Action
    }

    @Test fun siblingCancellationAndRemovalAreIsolated() = runTest {
        val active = mutableSetOf<Int>()
        val starts = mutableMapOf<Int, Int>()
        val pulses = (0..2).associateWith { Channel<Unit>(Channel.UNLIMITED) }
        val child = Reducer<Child, ChildAction> { state, action ->
            when (action) {
                ChildAction.Start -> Reduced(state, Effect.run<ChildAction> { send ->
                    active += state.id
                    starts[state.id] = starts.getOrDefault(state.id, 0) + 1
                    try {
                        for (pulse in pulses.getValue(state.id)) send(ChildAction.Tick)
                    } finally {
                        active -= state.id
                    }
                }.cancellable(WorkID, cancelInFlight = true))
                ChildAction.Cancel -> Reduced(state, Effect.cancel(WorkID))
                ChildAction.Tick -> Reduced(state.copy(ticks = state.ticks + 1))
            }
        }
        val parent = Reducer<State, Action> { state, action ->
            when (action) {
                is Action.Remove -> Reduced(state.copy(stack = state.stack.filterNot { it.id == action.id }))
                Action.StartParent -> Reduced(state, Effect.run<Action> {
                    active += -1
                    try { awaitCancellation() } finally { active -= -1 }
                }.cancellable(WorkID, cancelInFlight = true))
                Action.CancelParent -> Reduced(state, Effect.cancel(WorkID))
                else -> Reduced(state)
            }
        }
        val reducer = forEach(
            slot = StackSlot,
            parent = ifLet(
                id = DetailSlot,
                parent = parent,
                state = { it.detail },
                setState = { s, c -> s.copy(detail = c) },
                action = { (it as? Action.Detail)?.action },
                embed = { Action.Detail(it) },
                child = child,
                childID = { it.id },
            ),
            elements = { it.stack },
            setElements = { s, c -> s.copy(stack = c) },
            elementID = { it.id },
            action = { (it as? Action.Element)?.let { it.id to it.action } },
            embed = { id, action -> Action.Element(id, action) },
            child = child,
        )
        val store = TestStore(State(), reducer, this)
        store.send(Action.Detail(PresentationAction.Presented(ChildAction.Start)))
        store.send(Action.Element(1, ChildAction.Start))
        store.send(Action.Element(2, ChildAction.Start))
        runCurrent()
        assertEquals(setOf(0, 1, 2), active)

        // Restarting one sibling cancels only its previous run, not either other screen.
        store.send(Action.Element(1, ChildAction.Start))
        runCurrent()
        assertEquals(setOf(0, 1, 2), active)
        assertEquals(mapOf(0 to 1, 1 to 2, 2 to 1), starts)
        store.send(Action.StartParent)
        runCurrent()
        assertEquals(setOf(-1, 0, 1, 2), active)

        // A parent cancellation is not a cancellation in any child's navigation path.
        store.send(Action.CancelParent)
        runCurrent()
        assertEquals(setOf(0, 1, 2), active)
        for (id in 0..2) {
            pulses.getValue(id).send(Unit)
            if (id == 0) {
                store.receive(Action.Detail(PresentationAction.Presented(ChildAction.Tick))) {
                    it.copy(detail = it.detail!!.copy(ticks = 1))
                }
            } else {
                store.receive(Action.Element(id, ChildAction.Tick)) {
                    it.copy(stack = it.stack.map { c -> if (c.id == id) c.copy(ticks = 1) else c })
                }
            }
        }

        store.send(Action.Element(1, ChildAction.Cancel))
        runCurrent()
        assertEquals(setOf(0, 2), active)
        store.send(Action.Element(1, ChildAction.Start))
        runCurrent()
        assertEquals(setOf(0, 1, 2), active)
        store.send(Action.Remove(1)) { it.copy(stack = it.stack.filterNot { c -> c.id == 1 }) }
        runCurrent()
        assertEquals(setOf(0, 2), active)

        pulses.getValue(0).send(Unit)
        store.receive(Action.Detail(PresentationAction.Presented(ChildAction.Tick))) {
            it.copy(detail = it.detail!!.copy(ticks = 2))
        }
        pulses.getValue(2).send(Unit)
        store.receive(Action.Element(2, ChildAction.Tick)) {
            it.copy(stack = it.stack.map { c -> c.copy(ticks = 2) })
        }
        store.send(Action.Detail(PresentationAction.Dismiss)) { it.copy(detail = null) }
        runCurrent()
        assertEquals(setOf(2), active)
        store.send(Action.Remove(2)) { it.copy(stack = emptyList()) }
        store.finish()
        assertEquals(emptySet<Int>(), active)
    }

    private data class Group(val id: Int, val child: Child? = Child(0))
    private sealed interface GroupAction {
        data class ChildAction(val action: NavigationCancellationTest.ChildAction) : GroupAction
        data class Replace(val child: Child?) : GroupAction
    }
    private sealed interface GroupsAction {
        data class Element(val id: Int, val action: GroupAction) : GroupsAction
        data class Remove(val id: Int) : GroupsAction
    }

    @Test fun nestedOptionalPathsAndLifetimesAreIsolated() = runTest {
        var active = 0
        // Identical slot/child IDs under two different parents must not collide. Work without
        // an explicit ID must still end when the optional child or its ancestor is removed.
        val child = Reducer<Child, ChildAction> { state, action ->
            val work = Effect.run<ChildAction> {
                active++
                try { awaitCancellation() } finally { active-- }
            }
            Reduced(state, when (action) {
                ChildAction.Start -> Effect.concatenate(
                    Effect.send(ChildAction.Tick),
                    Effect.merge(work.cancellable(WorkID, cancelInFlight = true), work),
                )
                ChildAction.Cancel -> Effect.concatenate(
                    Effect.run<ChildAction> { yield() },
                    Effect.cancel(WorkID),
                )
                ChildAction.Tick -> Effect.none()
            })
        }
        val group = ifLet(
            parent = Reducer<Group, GroupAction> { state, action ->
                Reduced(if (action is GroupAction.Replace) state.copy(child = action.child) else state)
            },
            state = { it.child },
            setState = { s, c -> s.copy(child = c) },
            action = { (it as? GroupAction.ChildAction)?.action },
            embed = GroupAction::ChildAction,
            child = child,
            slot = DetailSlot,
            childID = { it.id },
        )
        val reducer = forEach(
            slot = StackSlot,
            parent = Reducer<List<Group>, GroupsAction> { state, action ->
                Reduced(if (action is GroupsAction.Remove) state.filterNot { it.id == action.id } else state)
            },
            elements = { it },
            setElements = { _, c -> c },
            elementID = { it.id },
            action = { (it as? GroupsAction.Element)?.let { it.id to it.action } },
            embed = GroupsAction::Element,
            child = group,
        )
        val store = TestStore(listOf(Group(1), Group(2)), reducer, this)
        for (id in 1..2) {
            store.send(GroupsAction.Element(id, GroupAction.ChildAction(ChildAction.Start)))
            store.receive(GroupsAction.Element(id, GroupAction.ChildAction(ChildAction.Tick)))
        }
        runCurrent()
        assertEquals(4, active)
        store.send(GroupsAction.Element(1, GroupAction.ChildAction(ChildAction.Cancel)))
        runCurrent()
        assertEquals(3, active)
        store.send(GroupsAction.Element(1, GroupAction.Replace(Child(3)))) {
            it.map { g -> if (g.id == 1) g.copy(child = Child(3)) else g }
        }
        runCurrent()
        assertEquals(2, active)
        store.send(GroupsAction.Element(1, GroupAction.ChildAction(ChildAction.Start)))
        store.receive(GroupsAction.Element(1, GroupAction.ChildAction(ChildAction.Tick)))
        runCurrent()
        assertEquals(4, active)
        store.send(GroupsAction.Element(1, GroupAction.Replace(null))) {
            it.map { g -> if (g.id == 1) g.copy(child = null) else g }
        }
        runCurrent()
        assertEquals(2, active)
        store.send(GroupsAction.Remove(2)) { it.filterNot { g -> g.id == 2 } }
        store.finish()
        assertEquals(0, active)
    }
}
