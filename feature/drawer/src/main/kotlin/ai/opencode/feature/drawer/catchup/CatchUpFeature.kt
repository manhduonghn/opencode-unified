package ai.opencode.feature.drawer.catchup

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalFeatures
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sound.SoundEffect
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.composer.DraftState
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

typealias CatchUpDisposition = CatchUpFeature.Disposition

/**
 * The Catch Up stack's queue, Undo history, Mark All receipts and the All Caught Up finish.
 * `HomeFeature` composes it and coordinates through the transient `last…`/`exiting…` records.
 */
class CatchUpFeature : Reducer<CatchUpFeature.State, CatchUpFeature.Action> {
    enum class Disposition { markRead, keepUnread }

    /** Enough to put an unread session back as it was if a Mark All receipt fails. */
    data class ReadBackup(
        /** The idle watermark the receipt acknowledges. */
        val idle: Long,
        val viewedMilliseconds: Long? = null,
        val locallyViewedMilliseconds: Long? = null,
        val readDate: Instant? = null,
        val wasManuallyUnread: Boolean = false,
    )

    data class Stack(
        /** Sessions still to show; the first is the open card. */
        val queue: List<String>,
        /** Cards already moved along, newest last, for Undo. */
        val handled: List<Handled> = emptyList(),
        /** Where the reader was, restored on leaving. */
        val returnSessionID: String? = null,
        val returnComposerSessionID: String? = null,
        /**
         * The drawer-local stand-in for the session's draft state, until `:feature:session` ports
         * it.
         */
        val returnComposer: DraftState? = null,
    ) {
        val composerTargetSessionID: String? get() = returnComposerSessionID ?: returnSessionID

        data class Handled(
            val sessionID: String,
            val disposition: Disposition,
            val idleMilliseconds: Long?,
            val updatedAt: Instant,
            val neededAttention: Boolean,
        ) {
            /** New activity since the card was handled brings it back this pass. */
            fun isSupersededBy(session: OpenCodeSession, needsAttention: Boolean): Boolean {
                if (needsAttention && !neededAttention) return true
                val idle = session.idleMilliseconds
                val handledIdle = idleMilliseconds
                if (idle != null && handledIdle != null) return idle > handledIdle
                return session.updatedAt > updatedAt
            }
        }

        fun dismissFront(session: OpenCodeSession, disposition: Disposition, neededAttention: Boolean): Stack {
            if (queue.firstOrNull() != session.id) return this
            return copy(
                queue = queue.drop(1),
                handled = handled + Handled(
                    sessionID = session.id,
                    disposition = disposition,
                    idleMilliseconds = session.idleMilliseconds,
                    updatedAt = session.updatedAt,
                    neededAttention = neededAttention,
                ),
            )
        }

        fun syncQueue(
            sessionsByID: Map<String, OpenCodeSession>,
            candidates: List<OpenCodeSession>,
            needsCatchUp: (OpenCodeSession) -> Boolean,
            needsAttention: (String) -> Boolean,
        ): Stack {
            val front = queue.firstOrNull()
            val next = queue.filter { id ->
                val session = sessionsByID[id] ?: return@filter false
                id == front || needsCatchUp(session)
            }.toMutableList()
            val queued = next.toHashSet()
            // Latest wins, as `Dictionary(uniquingKeysWith: { _, latest in latest })`.
            val handledByID = handled.associateBy { it.sessionID }
            for (session in candidates) {
                if (session.id in queued) continue
                val past = handledByID[session.id]
                if (past != null && !past.isSupersededBy(session, needsAttention(session.id))) continue
                next += session.id
            }
            return copy(queue = next)
        }
    }

    data class State(
        val stack: Stack? = null,
        /**
         * Mark All's in-flight receipts, keyed by session ID: the row looks read at once, and a
         * failed receipt puts it back if nothing else changed it.
         */
        val markAllReceipts: Map<String, ReadBackup> = emptyMap(),
        /** Transient record of the card popped by [Action.UndoTapped] for parent coordination. */
        val lastUndone: Stack.Handled? = null,
        /** Transient record of the stack exited by [Action.ExitTapped] for parent coordination. */
        val exitingStack: Stack? = null,
        /** Transient record of the receipt settled by [Action.MarkAllReceiptResponse] for parent coordination. */
        val lastResolvedReceipt: ResolvedReceipt? = null,
        /**
         * Catch Up just finished by its last card: the drawer's Catch Up row reads All Caught Up
         * until it rests (`CatchUpFinish.kt`).
         */
        val justFinished: Boolean = false,
        /** Real finishes so far: the row plays each one once, by this identity. */
        val finishes: Int = 0,
    ) {
        data class ResolvedReceipt(val sessionID: String, val backup: ReadBackup, val succeeded: Boolean)

        /** The record and the state without it. */
        fun consumeLastUndone(): Pair<Stack.Handled?, State> = lastUndone to copy(lastUndone = null)

        fun consumeExitingStack(): Pair<Stack?, State> = exitingStack to copy(exitingStack = null)

        fun consumeLastResolvedReceipt(): Pair<ResolvedReceipt?, State> = lastResolvedReceipt to copy(lastResolvedReceipt = null)
    }

    sealed interface Action {
        data object Tapped : Action
        data class CardDismissed(val sessionID: String, val disposition: Disposition) : Action
        data object UndoTapped : Action
        data object ExitTapped : Action
        /** The open card's title: leaves the stack for that session. */
        data object OpenTapped : Action
        /** The drawer row's menu: reads every unread session, leaving waiting ones. */
        data object MarkAllReadTapped : Action
        data class MarkAllReceiptResponse(val sessionID: String, val idle: Long, val succeeded: Boolean) : Action
        /** The last card's flight is away and nothing is left: Catch Up closes itself, back to the drawer it was opened from. */
        data class Finished(val reducedMotion: Boolean) : Action
        /** The row's 0 becomes a check. */
        data object FinishChecked : Action
        /** The row has rested and folds away. */
        data object FinishRested : Action
        /** Unread or waiting work arrived before the row rested: the finish ends there, without its `.success`. */
        data object WorkArrived : Action
    }

    enum class CancelID { markAllReceipts, finish }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.UndoTapped -> {
            val stack = state.stack
            val card = stack?.handled?.lastOrNull()
            if (stack == null || card == null) {
                Reduced(state)
            } else {
                val queue = listOf(card.sessionID) + stack.queue.filter { it != card.sessionID }
                dependency(HapticsClient).play(Haptic.selection)
                Reduced(state.copy(stack = stack.copy(handled = stack.handled.dropLast(1), queue = queue), lastUndone = card))
            }
        }

        Action.ExitTapped -> {
            val exiting = state.stack
            if (exiting == null) Reduced(state) else Reduced(state.copy(stack = null, exitingStack = exiting))
        }

        is Action.Finished, Action.FinishChecked, Action.FinishRested, Action.Tapped, Action.WorkArrived ->
            reduceFinish(state, action)

        is Action.MarkAllReceiptResponse -> {
            // Reading or unreading the session since then supersedes the receipt.
            val backup = state.markAllReceipts[action.sessionID]
            if (backup == null || backup.idle != action.idle) {
                Reduced(state)
            } else {
                Reduced(
                    state.copy(
                        markAllReceipts = state.markAllReceipts - action.sessionID,
                        lastResolvedReceipt = State.ResolvedReceipt(action.sessionID, backup, action.succeeded),
                    ),
                )
            }
        }

        is Action.CardDismissed, Action.OpenTapped, Action.MarkAllReadTapped -> Reduced(state)
    }

    /**
     * The finish's own actions: [Action.Finished] starts the timeline, which plays `.success` at
     * the check and rests; a tap or arriving work ends it early.
     */
    private fun reduceFinish(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.Finished -> {
            val exiting = state.stack
            if (exiting == null || exiting.queue.isNotEmpty()) {
                Reduced(state)
            } else {
                val timeline = CatchUpFinishTimeline.current(action.reducedMotion)
                val check = timeline.checkTime
                val rest = timeline.restEnd
                val clock = dependency(Clock)
                Reduced(
                    state.copy(stack = null, exitingStack = exiting, justFinished = true, finishes = state.finishes + 1),
                    Effect.run<Action> { send ->
                        clock.sleep(check.seconds)
                        send(Action.FinishChecked)
                        clock.sleep((rest - check).seconds)
                        send(Action.FinishRested)
                    }.cancellable(CancelID.finish, cancelInFlight = true),
                )
            }
        }

        Action.FinishChecked -> {
            // Ended early (a tap or new work) means no `.success`.
            if (state.justFinished) {
                dependency(HapticsClient).play(Haptic.success)
                if (dependency(ExperimentalFeatures)(ExperimentalFeature.soundEffects)) {
                    dependency(SoundEffectsClient).play(SoundEffect.catchUpDone)
                }
            }
            Reduced(state)
        }

        Action.FinishRested -> Reduced(state.copy(justFinished = false))

        Action.Tapped, Action.WorkArrived ->
            if (!state.justFinished) Reduced(state)
            else Reduced(state.copy(justFinished = false), Effect.cancel(CancelID.finish))

        else -> Reduced(state)
    }

    companion object {
        const val receiptConcurrency = 4

        /**
         * Acknowledges Mark All's receipts, [receiptConcurrency] at a time, answering each with
         * [Action.MarkAllReceiptResponse].
         */
        fun acknowledgeViews(
            receipts: List<Pair<String, Long>>,
            server: OpenCodeServer,
            openCodeClient: OpenCodeClient,
        ): Effect<Action> {
            if (receipts.isEmpty()) return Effect.none()
            return Effect.run<Action> { send ->
                val gate = Semaphore(receiptConcurrency)
                coroutineScope {
                    for ((sessionID, idle) in receipts) {
                        launch {
                            gate.withPermit {
                                val succeeded = try {
                                    openCodeClient.viewSession(server, sessionID, idle)
                                    true
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    false
                                }
                                if (!currentCoroutineContext().isActive) return@withPermit
                                send(Action.MarkAllReceiptResponse(sessionID, idle, succeeded))
                            }
                        }
                    }
                }
            }.cancellable(CancelID.markAllReceipts)
        }
    }
}
