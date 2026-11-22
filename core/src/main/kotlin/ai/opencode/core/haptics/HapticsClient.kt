package ai.opencode.core.haptics

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.log.AppLog
import android.media.AudioManager
import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Every haptic the app plays, named by what it means. Answer the press, not the animation;
 * navigation and closing stay quiet. Play only through [HapticsClient] or [HapticEffect].
 */
enum class Haptic {
    tap, lightTap, selection, confirm, cancel, threshold, snap, attention, success, warning, error;

    /** How each feels. The haptic table, mapped to Android composition primitives. */
    val feedback: HapticFeedback
        get() = when (this) {
            tap -> HapticFeedback.Impact(HapticFeedback.Style.Light)
            lightTap -> HapticFeedback.Selection
            selection -> HapticFeedback.Selection
            confirm -> HapticFeedback.Impact(HapticFeedback.Style.Medium, 0.75f)
            cancel -> HapticFeedback.Impact(HapticFeedback.Style.Soft, 0.85f)
            threshold -> HapticFeedback.Impact(HapticFeedback.Style.Light)
            snap -> HapticFeedback.Impact(HapticFeedback.Style.Soft, 0.7f)
            attention -> HapticFeedback.Impact(HapticFeedback.Style.Rigid, 0.7f)
            success -> HapticFeedback.Notification(HapticFeedback.Kind.Success)
            warning -> HapticFeedback.Notification(HapticFeedback.Kind.Warning)
            error -> HapticFeedback.Notification(HapticFeedback.Kind.Error)
        }
}

sealed interface HapticFeedback {
    enum class Style { Light, Medium, Heavy, Soft, Rigid }
    enum class Kind { Success, Warning, Error }

    data object Selection : HapticFeedback
    data class Impact(val style: Style, val intensity: Float = 1f) : HapticFeedback
    data class Notification(val kind: Kind) : HapticFeedback
}

/** Port of `HapticsClient` (`@Dependency(\.haptics)`). Voice call cues live in `:feature:voice`. */
class HapticsClient(
    val play: (Haptic) -> Unit = {},
    /** A touch that may end in this haptic began. */
    val prepare: (Haptic) -> Unit = {},
) {
    companion object Key : DependencyKey<HapticsClient> {
        override val liveValue: HapticsClient by lazy {
            val live = LiveHaptics(dependency(AppContext))
            HapticsClient(play = live::play, prepare = {})
        }
        override val testValue: HapticsClient = HapticsClient()
    }
}

/**
 * The views' way into the vocabulary: plays [haptic] when [trigger] changes and [condition] holds.
 */
@Composable
fun <T> HapticEffect(haptic: Haptic, trigger: T, condition: (old: T, new: T) -> Boolean = { _, _ -> true }) {
    val haptics = remember { dependency(HapticsClient) }
    val previous = remember { arrayOfNulls<Any?>(1).also { it[0] = trigger } }
    LaunchedEffect(trigger) {
        @Suppress("UNCHECKED_CAST")
        val old = previous[0] as T
        previous[0] = trigger
        if (old != trigger && condition(old, trigger)) haptics.play(haptic)
    }
}

/**
 * The app's only vibrator user. Plays nothing in the background or when the system's touch feedback
 * setting is off.
 */
internal class LiveHaptics(context: Context) {
    private val context = context.applicationContext
    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    private val primitives = vibrator.areAllPrimitivesSupported(
        Composition.PRIMITIVE_CLICK,
        Composition.PRIMITIVE_TICK,
        Composition.PRIMITIVE_LOW_TICK,
    )

    fun play(haptic: Haptic) {
        trace(haptic.name)
        if (!isActive() || !systemHapticsEnabled() || !vibrator.hasVibrator()) return
        runCatching { vibrator.vibrate(effect(haptic.feedback)) }
            .onFailure { AppLog.app.error { "Haptic ${haptic.name} failed: ${it::class.java.simpleName}" } }
    }

    /**
     * Records what a haptic request met, for Developer Mode traces: one content-free
     * `haptic_<kind>` mark while the diagnostics timeline or lab marks run.
     */
    private fun trace(kind: String) {
        if (!DiagnosticsRecorder.shared.isEnabled && !LabMarks.isEnabled) return
        val active = isActive()
        val audio = runCatching { AudioSessionHaptics.current(context) }.getOrNull() ?: return
        LabMarks.mark(AudioSessionHaptics.traceLine(kind, appActive = active, callActive = AudioSessionHaptics.isCallActive(), audio = audio))
    }

    private fun isActive(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun systemHapticsEnabled(): Boolean =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private fun effect(feedback: HapticFeedback): VibrationEffect {
        if (!primitives) return fallback(feedback)
        val composition = VibrationEffect.startComposition()
        when (feedback) {
            HapticFeedback.Selection -> composition.addPrimitive(Composition.PRIMITIVE_TICK, 0.6f)
            is HapticFeedback.Impact -> {
                val (primitive, base) = when (feedback.style) {
                    HapticFeedback.Style.Light -> Composition.PRIMITIVE_CLICK to 0.5f
                    HapticFeedback.Style.Medium -> Composition.PRIMITIVE_CLICK to 0.8f
                    HapticFeedback.Style.Heavy -> Composition.PRIMITIVE_CLICK to 1f
                    HapticFeedback.Style.Soft -> Composition.PRIMITIVE_LOW_TICK to 0.9f
                    HapticFeedback.Style.Rigid -> Composition.PRIMITIVE_TICK to 1f
                }
                composition.addPrimitive(primitive, (base * feedback.intensity).coerceIn(0f, 1f))
            }
            is HapticFeedback.Notification -> when (feedback.kind) {
                // UINotificationFeedbackGenerator's patterns: two rising taps, two even, three falling.
                HapticFeedback.Kind.Success -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.5f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
                HapticFeedback.Kind.Warning -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.9f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.9f, 140)
                HapticFeedback.Kind.Error -> composition
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 70)
                    .addPrimitive(Composition.PRIMITIVE_CLICK, 0.6f, 70)
            }
        }
        return composition.compose()
    }

    private fun fallback(feedback: HapticFeedback): VibrationEffect = VibrationEffect.createPredefined(
        when (feedback) {
            HapticFeedback.Selection -> VibrationEffect.EFFECT_TICK
            is HapticFeedback.Impact -> if (feedback.style == HapticFeedback.Style.Heavy) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
            is HapticFeedback.Notification -> if (feedback.kind == HapticFeedback.Kind.Success) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK
        },
    )
}

/** The audio half of a haptic's trace line. `audio=` is the `AudioManager` mode. */
object AudioSessionHaptics {
    data class AudioState(val category: String, val mode: String, val allowsHaptics: Boolean, val dictating: Boolean, val otherAudio: Boolean)

    /** Whether dictation is recording; set by the feature that records. */
    @Volatile var isDictating: () -> Boolean = { false }

    /** Whether a voice call holds the audio; set by the voice feature. */
    @Volatile var isCallActive: () -> Boolean = { false }

    fun current(context: Context): AudioState {
        val audio = context.getSystemService(AudioManager::class.java)
        val mode = when (audio?.mode) {
            AudioManager.MODE_NORMAL -> "Normal"
            AudioManager.MODE_RINGTONE -> "Ringtone"
            AudioManager.MODE_IN_CALL -> "InCall"
            AudioManager.MODE_IN_COMMUNICATION -> "InCommunication"
            AudioManager.MODE_CALL_SCREENING -> "CallScreening"
            null -> "-"
            else -> audio.mode.toString()
        }
        return AudioState(category = "-", mode = mode, allowsHaptics = true, dictating = isDictating(), otherAudio = audio?.isMusicActive == true)
    }

    /**
     * One content-free line: what was asked for, whether the app was active, and the audio state.
     */
    fun traceLine(kind: String, appActive: Boolean, callActive: Boolean, audio: AudioState): String =
        "haptic_$kind app=${if (appActive) "active" else "inactive"} audio=${audio.category}/${audio.mode}" +
            " allow=${if (audio.allowsHaptics) 1 else 0} dictating=${if (audio.dictating) 1 else 0}" +
            " call=${if (callActive) 1 else 0} other=${if (audio.otherAudio) 1 else 0}"
}
