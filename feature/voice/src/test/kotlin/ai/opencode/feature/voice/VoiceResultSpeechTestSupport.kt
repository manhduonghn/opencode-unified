package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.TestStore
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Kotlin value returns copies. */
class Watch(var value: ReplyWatch = ReplyWatch(callPhase = ReplyWatch.CallPhase.live)) {
    fun step(event: ReplyWatch.Event): List<ReplyWatch.Effect> = value.transition(event).let { value = it.first; it.second }
    fun noteUserActed() { value = value.noteUserActed() }
    val watched get() = value.watched
    val completed get() = value.completed
    val delegatorReplies get() = value.delegatorReplies
    val deferredNotices get() = value.deferredNotices
    val delegatorsInFlight get() = value.delegatorsInFlight
    val pendingAnswers get() = value.pendingAnswers
}

fun VoiceFeature.State.stepping(event: ReplyWatch.Event): VoiceFeature.State =
    copy(replyWatch = replyWatch.transition(event).first)

fun VoiceFeature.State.recording(role: VoiceTranscriptTail.Role, text: String, id: String? = null): VoiceFeature.State =
    copy(tail = tail.record(role, text, id).tail)

/**
 * One test's shared values. A `VoiceFeature.State` reads `@Shared` values where they're used,
 * so it is built (and its shared values set) in the same in-memory store the TestStore gets.
 */
class SpeechTestShared {
    val store: SharedStore = SharedStore.inMemory()

    fun <R> run(block: () -> R): R =
        DependencyValues.withValues(DependencyValues.testing().update { this[SharedStore] = store }, block)
}

class Locked<T>(initial: T) {
    @Volatile private var current: T = initial
    val value: T get() = synchronized(this) { current }
    fun set(newValue: T) = synchronized(this) { current = newValue }
    fun <R> withValue(block: (T) -> Pair<T, R>): R = synchronized(this) { block(current).let { current = it.first; it.second } }
    fun update(block: (T) -> T) = synchronized(this) { current = block(current) }
}

/**
 * Effects launch here instead of the test's own scope, so a test that ends with work still
 * in flight cancels it (TCA's `skipInFlightEffects`) rather than the runner waiting on it.
 */
fun detachedScope(context: CoroutineContext): CoroutineScope = CoroutineScope(context + Job())

fun CoroutineScope.skipInFlightEffects() = cancel()

/** Polls until [condition] holds. */
suspend fun eventually(timeout: Duration = 5.seconds, condition: () -> Boolean) {
    withTimeout(timeout) { while (!condition()) delay(10) }
}

/** [eventually], reducing what effects send while it waits. */
suspend fun <S, A> TestStore<S, A>.eventuallyReducing(timeout: Duration = 5.seconds, condition: () -> Boolean) {
    withTimeout(timeout) {
        while (true) {
            skipReceivedActions()
            if (condition()) break
            delay(10)
        }
    }
}

suspend fun <S, A> TestStore<S, A>.receive(matches: (A) -> Boolean, timeout: Duration) =
    receive(matches, "matching action", timeout)

/** A tool's JSON output as a map. */
fun jsonObject(output: String): JsonObject = Json.parseToJsonElement(output) as JsonObject

val JsonObject.stringMap: Map<String, Any?>
    get() = mapValues { (_, value) ->
        when (value) {
            is JsonPrimitive -> if (value.isString) value.content else value.contentOrNull?.toIntOrNull() ?: value.contentOrNull
            is JsonArray -> value.map { it.jsonPrimitive.contentOrNull }
            else -> value
        }
    }

fun userText(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)

/** Android keeps the trimmed Markdown. */
fun assistantMarkdown(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text.trim())
