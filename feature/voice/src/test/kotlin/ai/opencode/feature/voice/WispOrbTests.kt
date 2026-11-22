package ai.opencode.feature.voice

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/**
 * The call's voice orb, Wisp's ([WispOrbSim], [WispOrbCells], `WispOrbShaders`).
 *
 * Android: the uniforms are one flat `FloatArray` of float4 slots, so the layout test checks the
 * slot indices the raster reads; the cell kernel runs on the CPU, so its test runs it directly
 * instead of through a Metal compute pipeline.
 */
class WispOrbTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun config(
        mood: FloatingCallMood,
        speaking: Boolean = false,
        muted: Boolean = false,
        scene: FloatingCallScene? = null,
    ) = LivingOrbConfig(mood = mood, isMuted = muted, isSpeaking = speaking, scene = scene)

    @Test fun callMomentsMapToWispStates() {
        assertEquals(WispOrbState.connecting, config(FloatingCallMood.connecting).wispState(userLevel = 0f))
        assertEquals(WispOrbState.listening, config(FloatingCallMood.live).wispState(userLevel = 0f))
        assertEquals(WispOrbState.listening, config(FloatingCallMood.live).wispState(userLevel = 0.5f))
        assertEquals(WispOrbState.speaking, config(FloatingCallMood.live, speaking = true).wispState(userLevel = 0f))
        assertEquals(WispOrbState.bothSpeaking, config(FloatingCallMood.thinking, speaking = true).wispState(userLevel = 0.5f))
        assertEquals(WispOrbState.speaking, config(FloatingCallMood.live, speaking = true, muted = true).wispState(userLevel = 0.5f))
        assertEquals(WispOrbState.thinking, config(FloatingCallMood.thinking).wispState(userLevel = 0f))
        assertEquals(WispOrbState.confirming, config(FloatingCallMood.needsInput).wispState(userLevel = 0f))
        assertEquals(WispOrbState.sleeping, config(FloatingCallMood.ended).wispState(userLevel = 0f))
        assertEquals(WispOrbState.confirming, config(FloatingCallMood.live, scene = FloatingCallScene.needsInput).wispState(userLevel = 0f))
        assertEquals(
            WispOrbState.speaking,
            config(FloatingCallMood.live, muted = true, scene = FloatingCallScene.bothSpeaking).wispState(userLevel = 0f),
        )
    }

    @Test fun toolNamesPickThinkingsChoreography() {
        assertEquals(WispOrbChoreo.reasoning, WispOrbChoreo.classify(null))
        assertEquals(WispOrbChoreo.scan, WispOrbChoreo.classify("read"))
        assertEquals(WispOrbChoreo.scan, WispOrbChoreo.classify("web_search"))
        assertEquals(WispOrbChoreo.reasoning, WispOrbChoreo.classify("mystery"))
        assertEquals(WispOrbChoreo.scan, WispOrbChoreo.classify("websearch"))
        assertEquals(WispOrbChoreo.spark, WispOrbChoreo.classify("bash"))
        assertEquals(WispOrbChoreo.reasoning, WispOrbChoreo.classify("task"))
    }

    @Test fun tunablesDefaultToWispsLook() {
        assertEquals(WispOrbTuning(), WispOrbTuning.current)
        assertEquals(40, WispOrbTuning().columns)
        assertEquals(WispOrbCoupling.harmonicTwist, WispOrbTunables.coupling.defaultValue)
    }

    /** Every field is a `float4`, in the shader's order. */
    @Test fun uniformsMatchTheShadersLayout() {
        assertEquals(63 * 4, WispOrbUniforms().data.size)
        assertEquals(30, WispOrbUniforms.circles)
        assertEquals(59, WispOrbUniforms.view)
    }

    @Test fun aVoiceLightsTheLensAndStaysFinite() {
        val sim = WispOrbSim()
        val tuning = WispOrbTuning()
        var frame = WispOrbUniforms()
        for (i in 0 until 240) {
            val level = 0.4f + 0.3f * sin(i * 0.2f)
            sim.step(1f / 120, user = level, agent = 0f, next = WispOrbState.listening, rm = false, tuning = tuning)
            frame = sim.frame(tuning, WispOrbPlacement(100f, 100f, 3f))
        }
        assertEquals(40f, frame[WispOrbUniforms.grid][0])
        assertTrue(frame[WispOrbUniforms.levels][0] > 0.05f)
        assertTrue(sim.userTrace.amplitude > 0.05f)
        assertTrue(frame.data.all { it.isFinite() })
    }

    @Test fun reduceMotionHoldsStill() {
        val sim = WispOrbSim()
        val tuning = WispOrbTuning()
        sim.step(0f, user = 0f, agent = 0.5f, next = WispOrbState.speaking, rm = true, tuning = tuning)
        val a = sim.frame(tuning, WispOrbPlacement(0f, 0f, 3f))
        sim.step(0.5f, user = 0f, agent = 0.5f, next = WispOrbState.speaking, rm = true, tuning = tuning)
        val b = sim.frame(tuning, WispOrbPlacement(0f, 0f, 3f))
        assertEquals(1f, a[WispOrbUniforms.tintBase][3])
        assertEquals(a[WispOrbUniforms.clock].toList(), b[WispOrbUniforms.clock].toList())
        assertTrue(b[WispOrbUniforms.phosphor][0] == 0f && b[WispOrbUniforms.phosphor][1] == 0f)
    }

    /** One frame through the cell kernel: cells inside the lens light, the corners stay dark, and nothing is NaN. */
    @Test fun theCellKernelLightsTheLens() {
        val sim = WispOrbSim()
        val tuning = WispOrbTuning()
        repeat(120) { sim.step(1f / 120, user = 0.6f, agent = 0.5f, next = WispOrbState.bothSpeaking, rm = false, tuning = tuning) }
        val uniforms = sim.frame(tuning, WispOrbPlacement(150f, 150f, 3f))
        val cols = tuning.columns
        val count = cols * cols
        val phosphor = FloatArray(count * 4)
        val traces = FloatArray(64 * 4)
        for (i in 0 until 64) {
            traces[i * 4] = sim.userTrace.newestAmplitude(i)
            traces[i * 4 + 1] = sim.userTrace.newestHarmonic(i)
            traces[i * 4 + 2] = sim.agentTrace.newestAmplitude(i)
            traces[i * 4 + 3] = sim.agentTrace.newestHarmonic(i)
        }
        val cells = FloatArray(count * WispOrbCells.floatsPerCell)
        WispOrbCells.evaluate(uniforms.data, phosphor, traces, cells)

        // dotR, haloR, dotA, haloA per cell, as Metal's geometry float4.
        val geo = (0 until count).map { i -> FloatArray(4) { cells[i * WispOrbCells.floatsPerCell + it] } }
        assertTrue(geo.all { g -> g.all { it.isFinite() } })
        assertEquals("The corner is outside the lens", 0f, geo[0][2])
        val centre = geo[(cols / 2) * cols + cols / 2]
        assertTrue(centre[2] > 0.05f && centre[0] > 0f)
        assertTrue("Both voices light some dots brightly", geo.maxOf { it[2] } > 0.5f)
    }
}
