package ai.opencode.core.haptics

import ai.opencode.core.testing.TestIsolationRule
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator
import java.io.File

/**
 * `aRequestArrivingInTheBackgroundPlaysNothing` reads Android's foreground gate
 * (`ProcessLifecycleOwner` + `Vibrator`), so it runs under Robolectric in
 * [LiveHapticsFrameworkTests].
 */
class LiveHapticsTests {
    @get:Rule val isolation = TestIsolationRule()

    /**
     * Android's equivalents are the `Vibrator` APIs and Compose/View haptic feedback; only
     * `HapticsClient.kt` and the voice call's pattern engine may use them.
     */
    @Test
    fun noDirectFeedbackGeneratorsOrSensoryFeedbackOutsideHapticsClientAndShareExtension() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
        val pattern = Regex("""\bVibrator(Manager)?\b|\bVibrationEffect\b|performHapticFeedback|LocalHapticFeedback|HapticFeedbackConstants""")
        val allowPattern = Regex("""//\s*correct-allow:\s*haptics\s*(?:--|—|-)\s*\S.*?\(\s*expires\s+\d{4}-\d{2}-\d{2}\s*,\s*approved by\s+[A-Za-z][A-Za-z0-9 _.-]*\s*\)""")
        val allowed = setOf(
            "core/src/main/kotlin/ai/opencode/core/haptics/HapticsClient.kt",
            "feature/voice/src/main/kotlin/ai/opencode/feature/voice/VoiceHapticEngine.kt",
        )
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }
            .forEach { file ->
                val rel = file.relativeTo(root).path
                if (rel in allowed) return@forEach
                file.readLines().forEachIndexed { index, raw ->
                    val code = raw.substringBefore("//")
                    if (pattern.containsMatchIn(code) && !allowPattern.containsMatchIn(raw)) {
                        violations += "$rel:${index + 1}: ${code.trim()}"
                    }
                }
            }
        assertTrue(
            "Use dependency(HapticsClient) or HapticEffect (core/haptics/HapticsClient.kt) instead of raw Vibrator or haptic feedback: $violations",
            violations.isEmpty(),
        )
    }

    /**
     * The + menu opening and choosing its rows feel as light as a photo pick, lighter than `.tap`'s
     * impact.
     */
    @Test
    fun thePlusMenusTapsAreAsLightAsAPick() {
        assertEquals(Haptic.selection.feedback, Haptic.lightTap.feedback)
        assertTrue(Haptic.lightTap.feedback != Haptic.tap.feedback)
    }
}

/** [LiveHapticsTests]' foreground gate under Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveHapticsFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val app = RuntimeEnvironment.getApplication()
    private val vibrator = app.getSystemService(VibratorManager::class.java).defaultVibrator

    /** The process lifecycle as the system drives it (in tests nothing attaches it to activities). */
    private fun process(state: Lifecycle.State) {
        (ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry).currentState = state
    }

    @After fun backgroundAgain() = process(Lifecycle.State.CREATED)

    @Test
    fun aRequestArrivingInTheBackgroundPlaysNothing() {
        shadowOf(vibrator).setSupportedPrimitives(
            listOf(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_LOW_TICK),
        )
        val haptics = LiveHaptics(app)
        process(Lifecycle.State.CREATED)
        haptics.play(Haptic.attention)
        assertTrue(shadowOf(vibrator).primitiveSegmentsInPrimitiveEffects.isEmpty())
        assertFalse(shadowOf(vibrator).isVibrating)

        process(Lifecycle.State.RESUMED)
        haptics.play(Haptic.attention)
        // `attention` is a rigid impact at 0.7: one tick.
        assertEquals(
            listOf(ShadowVibrator.PrimitiveEffect(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 0)),
            shadowOf(vibrator).primitiveSegmentsInPrimitiveEffects,
        )
    }
}
