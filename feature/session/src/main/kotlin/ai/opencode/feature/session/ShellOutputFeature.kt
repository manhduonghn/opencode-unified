package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.sheet.asClientError
import ai.opencode.feature.session.sheet.clientError
import ai.opencode.feature.session.sheet.clientResult
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Live output of one server shell. Polls only while its view is on screen, tails the newest bytes,
 * and keeps a bounded window of the capture.
 */
class ShellOutputFeature : Reducer<ShellOutputFeature.State, ShellOutputFeature.Action> {
    companion object {
        /** One read of the capture file; the server's default page size. */
        const val pageBytes = 64 * 1024
        /** How much of an existing capture the first read shows. */
        const val initialTailBytes = 256 * 1024
        /** The retained window while tailing; older bytes drop off the front. */
        const val retainedBytes = 1024 * 1024
        /** Load Earlier can grow the window up to this much, never further. */
        const val maximumRetainedBytes = 4 * 1024 * 1024
        val pollInterval = 1.seconds
        val retryInterval = 3.seconds
        val maximumRetryInterval = 30.seconds

        /**
         * A server that keeps failing is asked less often: 3, 6, 12, 24, then every 30 s. Traffic
         * into a dead route can stall a VPN tunnel.
         */
        fun retryDelay(afterFailures: Int): Duration {
            val doublings = minOf(maxOf(afterFailures - 1, 0), 4)
            val delay = retryInterval * (1 shl doublings)
            return minOf(delay, maximumRetryInterval)
        }
        /** Asks the server for the capture's size without reading any of it. */
        const val endOfCapture = 9_007_199_254_740_991L

        /** UTF-8 length without allocating. */
        internal fun utf8Count(text: String): Int {
            var count = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                count += when {
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> { index++; 4 }
                    else -> 3
                }
                index++
            }
            return count
        }
    }

    data class State(
        val server: OpenCodeServer,
        val directory: String,
        val shell: OpenCodeShell,
        /** The retained output; it ends at [endCursor]. */
        val output: String = "",
        /** [output]'s UTF-8 length, kept alongside it. */
        val outputUTF8Count: Int = 0,
        /** Absolute byte offset of the first retained byte. */
        val startCursor: Int = 0,
        /** The next byte to read; null until the tail has been located. */
        val endCursor: Int? = null,
        val retainLimit: Int = retainedBytes,
        val isLoadingEarlier: Boolean = false,
        val isConfirmingStop: Boolean = false,
        val isStopping: Boolean = false,
        /** The server no longer has this shell: stopped here or elsewhere, or expired. */
        val isUnavailable: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val id: String get() = shell.id
        val isLoaded: Boolean get() = endCursor != null
        val isRunning: Boolean get() = shell.status == OpenCodeShell.Status.Running && !isUnavailable
        val hasEarlierOutput: Boolean get() = startCursor > 0
        val canLoadEarlier: Boolean get() = hasEarlierOutput && outputUTF8Count < maximumRetainedBytes

        /** The same state with new [text] as its output. */
        internal fun withOutput(text: String, utf8Count: Int = utf8Count(text)): State = copy(output = text, outputUTF8Count = utf8Count)
    }

    sealed interface Action {
        /** The stop confirmation was dismissed. */
        data class IsConfirmingStopChanged(val isConfirmingStop: Boolean) : Action
        /** Sent by the view while it is on screen and the app is active; polling runs until [TaskEnded]. */
        data object Task : Action
        /** Android: the view left the screen or the app went inactive. */
        data object TaskEnded : Action
        data class TailLocated(val start: Int) : Action
        data class OutputReceived(val page: OpenCodeShellOutputPage) : Action
        data class StatusReceived(val shell: OpenCodeShell) : Action
        data class PollFailed(val error: OpenCodeClientError) : Action
        data object LoadEarlierTapped : Action
        data class EarlierOutputReceived(val start: Int, val result: Result<OpenCodeShellOutputPage>) : Action
        data object StopTapped : Action
        data object StopConfirmed : Action
        data class StopFinished(val error: OpenCodeClientError?) : Action
        data object CopyOutputTapped : Action
        /** Android: Share Output goes through [SessionExportClient.shareText], not the view. */
        data object ShareOutputTapped : Action
    }

    private enum class CancelID { Poll, Earlier, Stop }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.IsConfirmingStopChanged -> Reduced(state.copy(isConfirmingStop = action.isConfirmingStop))

        Action.Task -> {
            if (state.isUnavailable || state.isStopping ||
                !(state.shell.status == OpenCodeShell.Status.Running || !state.isLoaded)
            ) {
                Reduced(state)
            } else {
                Reduced(state, poll(state))
            }
        }

        Action.TaskEnded -> Reduced(state, Effect.cancel(CancelID.Poll))

        is Action.TailLocated ->
            if (state.endCursor != null) Reduced(state) else Reduced(state.copy(startCursor = action.start, endCursor = action.start))

        is Action.OutputReceived -> {
            val page = action.page
            val cleared = state.copy(errorMessage = null)
            val end = cleared.endCursor
            if (end == null || page.cursor <= end) {
                Reduced(cleared)
            } else {
                Reduced(trimFront(cleared.withOutput(cleared.output + page.output, cleared.outputUTF8Count + utf8Count(page.output)).copy(endCursor = page.cursor)))
            }
        }

        is Action.StatusReceived -> {
            val shell = action.shell
            var next = state.copy(
                errorMessage = null,
                shell = state.shell.copy(
                    status = shell.status,
                    exitCode = shell.exitCode,
                    startedAt = shell.startedAt ?: state.shell.startedAt,
                    completedAt = shell.completedAt,
                ),
            )
            if (!next.isRunning) next = next.copy(isConfirmingStop = false)
            Reduced(next)
        }

        is Action.PollFailed -> {
            if (action.error.statusCode == 404) {
                Reduced(state.copy(isUnavailable = true, isConfirmingStop = false, errorMessage = null, endCursor = state.endCursor ?: 0))
            } else {
                Reduced(state.copy(errorMessage = localized(R.string.session_shell_refresh_failed)))
            }
        }

        Action.LoadEarlierTapped -> {
            if (!state.canLoadEarlier || state.isLoadingEarlier || state.isUnavailable) {
                Reduced(state)
            } else {
                val start = maxOf(0, state.startCursor - initialTailBytes)
                val limit = state.startCursor - start
                val client = dependency(OpenCodeClient)
                val (server, directory, id) = Triple(state.server, state.directory, state.shell.id)
                Reduced(
                    state.copy(isLoadingEarlier = true),
                    Effect.run<Action> { send ->
                        send(Action.EarlierOutputReceived(start, clientResult { client.shellOutput(server, directory, id, start, limit) }))
                    }.cancellable(CancelID.Earlier, cancelInFlight = true),
                )
            }
        }

        is Action.EarlierOutputReceived -> {
            val next = state.copy(isLoadingEarlier = false)
            action.result.fold(
                onSuccess = { page ->
                    // The front moved while the page was in flight; it no longer joins up.
                    if (page.cursor != next.startCursor || action.start >= next.startCursor) {
                        Reduced(next)
                    } else {
                        val joined = next.withOutput(page.output + next.output, utf8Count(page.output) + next.outputUTF8Count)
                        Reduced(
                            joined.copy(
                                startCursor = action.start,
                                retainLimit = minOf(maximumRetainedBytes, maxOf(joined.retainLimit, joined.outputUTF8Count)),
                            ),
                        )
                    }
                },
                onFailure = { failure ->
                    if (failure.asClientError().statusCode == 404) {
                        Reduced(next.copy(isUnavailable = true, isConfirmingStop = false))
                    } else {
                        Reduced(next.copy(errorMessage = localized(R.string.session_shell_load_earlier_failed)))
                    }
                },
            )
        }

        Action.StopTapped ->
            if (!state.isRunning || state.isStopping) Reduced(state) else Reduced(state.copy(isConfirmingStop = true))

        Action.StopConfirmed -> {
            val next = state.copy(isConfirmingStop = false)
            if (!next.isRunning || next.isStopping) {
                Reduced(next)
            } else {
                dependency(HapticsClient).play(Haptic.confirm)
                val client = dependency(OpenCodeClient)
                val (server, directory, id) = Triple(next.server, next.directory, next.shell.id)
                val cursor = next.endCursor
                Reduced(
                    next.copy(isStopping = true, errorMessage = null),
                    Effect.merge(
                        Effect.cancel(CancelID.Poll),
                        Effect.run<Action> { send ->
                            // Stopping discards the server's capture: read what's left first.
                            if (cursor != null) {
                                clientResult { client.shellOutput(server, directory, id, cursor, pageBytes) }
                                    .getOrNull()?.let { send(Action.OutputReceived(it)) }
                            }
                            send(Action.StopFinished(clientError { client.stopShell(server, directory, id) }))
                        }.cancellable(CancelID.Stop, cancelInFlight = true),
                    ),
                )
            }
        }

        is Action.StopFinished -> {
            val next = state.copy(isStopping = false)
            val error = action.error
            if (error != null && error.statusCode != 404) {
                dependency(HapticsClient).play(Haptic.error)
                val failed = next.copy(errorMessage = localized(R.string.session_shell_stop_failed))
                Reduced(failed, poll(failed))
            } else {
                Reduced(next.copy(shell = next.shell.copy(status = OpenCodeShell.Status.Killed), isUnavailable = true))
            }
        }

        Action.CopyOutputTapped -> {
            if (state.output.isEmpty()) {
                Reduced(state)
            } else {
                dependency(HapticsClient).play(Haptic.tap)
                val output = state.output
                val pasteboard = dependency(PasteboardClient)
                Reduced(state, Effect.run { pasteboard.setString(output) })
            }
        }

        Action.ShareOutputTapped -> shareOutput(state)
    }

    private fun shareOutput(state: State): Reduced<State, Action> {
        if (state.output.isEmpty()) return Reduced(state)
        val (output, title) = state.output to state.shell.title
        val sessionExport = dependency(SessionExportClient)
        return Reduced(state, Effect.run { sessionExport.shareText(output, title) })
    }

    /**
     * Status first, then every byte up to the capture's current size: output read after an exit
     * is complete. Repeats while the command runs.
     */
    private fun poll(state: State): Effect<Action> {
        val (server, directory, id) = Triple(state.server, state.directory, state.shell.id)
        val initialCursor = state.endCursor
        val wasRunning = state.shell.status == OpenCodeShell.Status.Running
        val client = dependency(OpenCodeClient)
        val clock = dependency(Clock)
        val shellOutputMeter = dependency(ShellOutputMeterClient)
        return Effect.run<Action> { send ->
            var cursor = initialCursor
            var isRunning = wasRunning
            var failures = 0
            while (currentCoroutineContext().isActive) {
                try {
                    if (cursor == null) {
                        // Int.MAX_VALUE cursor: the route reads `cursor` as a JS number; see endOfCapture.
                        val probe = client.shellOutput(server, directory, id, endOfCaptureCursor, 1)
                        shellOutputMeter.record(id, probe.size)
                        val start = maxOf(0, probe.size - initialTailBytes)
                        send(Action.TailLocated(start))
                        cursor = start
                    }
                    if (isRunning) {
                        val shell = client.shell(server, directory, id)
                        send(Action.StatusReceived(shell))
                        isRunning = shell.status == OpenCodeShell.Status.Running
                    }
                    while (true) {
                        val current = cursor ?: break
                        val page = client.shellOutput(server, directory, id, current, pageBytes)
                        // The shell glyph's rate: the capture's size as each read finds it.
                        shellOutputMeter.record(id, page.size)
                        if (page.cursor <= current) break
                        send(Action.OutputReceived(page))
                        cursor = page.cursor
                        if (page.cursor >= page.size) break
                    }
                    if (!isRunning) return@run
                    failures = 0
                    clock.sleep(pollInterval)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    val error = failure.asClientError()
                    send(Action.PollFailed(error))
                    if (error.statusCode == 404) return@run
                    failures += 1
                    clock.sleep(retryDelay(afterFailures = failures))
                }
            }
        }.cancellable(CancelID.Poll, cancelInFlight = true)
    }

    /**
     * Drops the oldest output past the retained window, at a line boundary when one is near so
     * the first visible line is whole.
     */
    private fun trimFront(state: State): State {
        val excess = state.outputUTF8Count - state.retainLimit
        if (excess <= 0) return state
        val utf8 = state.output.encodeToByteArray()
        var cut = excess
        val searchEnd = minOf(cut + 4096, utf8.size)
        val newline = (cut until searchEnd).firstOrNull { utf8[it] == '\n'.code.toByte() }
        if (newline != null) {
            cut = newline + 1
        } else {
            // Never split a character: move to the next UTF-8 lead byte.
            while (cut < utf8.size && (utf8[cut].toInt() and 0xC0) == 0x80) cut++
        }
        return state.withOutput(utf8.decodeToString(cut, utf8.size), utf8.size - cut).copy(startCursor = state.startCursor + cut)
    }
}

/**
 * The capture-size probe's cursor. `OpenCodeClient.shellOutput` takes an `Int` cursor, so the
 * probe asks for the largest one it can send; any cursor past the end returns only the size.
 */
private const val endOfCaptureCursor = Int.MAX_VALUE
