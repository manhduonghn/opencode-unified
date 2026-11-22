package ai.opencode.feature.voice

import ai.opencode.core.tuning.Tunable
import kotlin.time.Duration

// The type and its `note…` updates are declared in `ReplyWatch.kt` so it keeps the name
// `ReplyWatch.QuietGate`; its policy lives here.

/** GPT-Live turn-taking, read when a call starts. */
object VoiceTurnTunables {
    val userQuietGap = Tunable("call.turns.userQuietGap", "Quiet Before Updates (s)", default = 2.0, range = 0.5..5.0, step = 0.1)
    val micVoiceLevel = Tunable("call.turns.micVoiceLevel", "Mic Voice Level", default = 0.35, range = 0.05..0.9, step = 0.05)
    val bargeIn = Tunable("call.turns.bargeIn", "Talk-Over Stop (s)", default = 0.6, range = 0.0..2.0, step = 0.1)
}

/** The gate for this call's timing. */
val VoiceCallTiming.quietGate: ReplyWatch.QuietGate
    get() = ReplyWatch.QuietGate(
        quietWindow = liveAppendQuiet,
        replyWait = liveAppendReplyWait,
        maxHold = liveAppendMaxHold,
        userQuietGap = liveUserQuietGap,
    )

fun ReplyWatch.QuietGate.delay(now: Duration): Duration? {
    if (quietWindow <= Duration.ZERO) return null
    if (assistantSpeaking) {
        return quietWindow
    }
    var mandatoryWait = Duration.ZERO
    lastAssistantSpeechAt?.let { spoke ->
        mandatoryWait = maxOf(mandatoryWait, quietWindow - (now - spoke))
    }
    userSpeechHold(now)?.let { userWait ->
        mandatoryWait = maxOf(mandatoryWait, userWait)
    }
    var fullWait = mandatoryWait
    lastUserSpeechAt?.let { spoke ->
        val awaitingReply = lastAssistantSpeechAt?.let { spoke > it } ?: true
        val window = if (awaitingReply) maxOf(replyWait, quietWindow) else quietWindow
        fullWait = maxOf(fullWait, window - (now - spoke))
    }
    val heldSince = heldSince
    if (maxHold > Duration.ZERO && heldSince != null) {
        val activeAssistantSpeech = assistantSpeechStartedAt?.let { maxOf(Duration.ZERO, now - it) } ?: Duration.ZERO
        val quietElapsed = maxOf(Duration.ZERO, (now - heldSince) - speakingDurationWhileHeld - activeAssistantSpeech)
        val remainingHold = maxOf(Duration.ZERO, maxHold - quietElapsed)
        fullWait = minOf(fullWait, maxOf(mandatoryWait, remainingHold))
    }
    return if (fullWait > Duration.ZERO) fullWait else null
}

/**
 * A delegation's own answer is the reply the user is owed, so it never waits for a reply to start:
 * only for speech in progress and the brief pauses after it. Once the user has asked again
 * ([pressed]), the voice agent talking without the answer holds it at most [pressedHold] (zero: no
 * limit) of that talk; the user's own words always hold it.
 */
fun ReplyWatch.QuietGate.answerDelay(
    now: Duration,
    readyAt: Duration,
    pressed: Boolean,
    pressedHold: Duration,
): Duration? {
    if (quietWindow <= Duration.ZERO) return null
    val userWait = userSpeechHold(now) ?: Duration.ZERO
    var assistantWait = Duration.ZERO
    val spoke = lastAssistantSpeechAt
    if (assistantSpeaking) {
        assistantWait = quietWindow
    } else if (spoke != null) {
        assistantWait = quietWindow - (now - spoke)
    }
    if (pressed && pressedHold > Duration.ZERO && assistantSpeaking) {
        val talking = now - maxOf(readyAt, assistantSpeechRunStartedAt ?: readyAt)
        assistantWait = minOf(assistantWait, maxOf(Duration.ZERO, pressedHold - talking))
    }
    val wait = maxOf(userWait, assistantWait)
    return if (wait > Duration.ZERO) wait else null
}

/**
 * Session context only: like [delay], and while a reply to the user's
 * last words is due, until it starts or [replyHold] after those words.
 */
fun ReplyWatch.QuietGate.contextDelay(now: Duration, replyHold: Duration): Duration? {
    val gate = delay(now) ?: Duration.ZERO
    var due = Duration.ZERO
    val spoke = lastUserSpeechAt
    if (quietWindow > Duration.ZERO && spoke != null && (lastAssistantSpeechAt?.let { spoke > it } ?: true)) {
        due = replyHold - (now - spoke)
    }
    val wait = maxOf(gate, due)
    return if (wait > Duration.ZERO) wait else null
}

/**
 * The user is speaking (their words or voice arrived within
 * [ReplyWatch.QuietGate.userQuietGap], or the brief pause without one):
 * how much longer to hold, or null. Nothing caps this.
 */
fun ReplyWatch.QuietGate.userSpeechHold(now: Duration): Duration? {
    if (quietWindow <= Duration.ZERO) return null
    val heard = listOfNotNull(lastUserSpeechAt, lastUserVoiceAt).maxOrNull() ?: return null
    val gap = if (userQuietGap > Duration.ZERO) userQuietGap else minOf(quietWindow, userPause)
    val left = gap - (now - heard)
    return if (left > Duration.ZERO) left else null
}

fun ReplyWatch.QuietGate.holdReason(now: Duration): String {
    if (assistantSpeaking) {
        return "assistant speaking"
    }
    if (userSpeechHold(now) != null) {
        return "user speaking"
    }
    val spoke = lastAssistantSpeechAt
    if (spoke != null && now - spoke < quietWindow) {
        return "assistant finished speaking"
    }
    return "waiting after user speech"
}
