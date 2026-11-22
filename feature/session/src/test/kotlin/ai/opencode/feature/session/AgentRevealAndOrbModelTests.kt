package ai.opencode.feature.session

import ai.opencode.feature.session.agentactivity.ActivityAccent
import ai.opencode.feature.session.agentactivity.ActivityGyro
import ai.opencode.feature.session.agentactivity.AgentActivityGlyphModel
import ai.opencode.feature.session.agentactivity.AgentActivityOrb
import ai.opencode.feature.session.agentactivity.GlyphModelCache
import ai.opencode.feature.session.agentactivity.Gyro
import ai.opencode.feature.session.agentactivity.GyroAfterglow
import ai.opencode.feature.session.agentactivity.GyroAngleKey
import ai.opencode.feature.session.agentactivity.GyroCell
import ai.opencode.feature.session.agentactivity.GyroField
import ai.opencode.feature.session.agentactivity.GyroPattern
import ai.opencode.feature.session.agentactivity.GyroSpringKey
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRevealAndOrbModelTests {
    @Test fun revisitingAnOrbKeepsItsSimulationAndBoundsRetention() {
        val cache = GlyphModelCache(::AgentActivityGlyphModel)
        val first = cache.model("server|session|summary")
        first.advance(now = 10.0, source = AgentActivitySource.Running, lab = null, reduceMotion = false)
        cache.model("server|other|summary")
        assertSame(first, cache.model("server|session|summary"))
        assertNotSame(first, cache.model("other-server|session|summary"))
        for (index in 0 until 65) cache.model("session-$index")
        assertNotSame(first, cache.model("server|session|summary"))
    }

    // region Status orb

    @Test fun finalStatesSettleAndStop() {
        for (activity in listOf(AgentActivity.done, AgentActivity.failed)) {
            val gyro = ActivityGyro(AgentActivity.thinking)
            gyro.set(activity)
            repeat(900) { if (!gyro.isResting) advance(gyro, 1.0 / 60) }
            assertTrue("$activity flashes, cools and comes to rest", gyro.isResting)
        }
    }

    /** One frame as the glyph draws it. */
    private fun advance(gyro: ActivityGyro, dt: Double): GyroField {
        gyro.step(dt)
        return gyro.render(GyroCell.seven, dt)
    }

    private fun frames(gyro: ActivityGyro, seconds: Double): List<GyroField> =
        (0 until (seconds * 60).roundToInt()).map { advance(gyro, 1.0 / 60) }

    private fun cell(row: Int, column: Int): Int = GyroCell.seven.indexOfFirst { it.row == row && it.column == column }

    @Test fun startingUpLeavesAnAfterglowThatOutlivesTheHandoff() {
        val gyro = ActivityGyro(AgentActivity.idle)
        gyro.set(AgentActivity.startup)
        frames(gyro, 1.2)
        assertEquals(0.35, gyro.phosphor.decay, 0.0)
        assertTrue(gyro.phosphor.amount(GyroPattern.condense) > 0.99)

        // Somewhere a dot is still glowing above what the field asks for: the phosphor lags behind.
        val field = gyro.fill(GyroCell.seven)
        val lit = gyro.render(GyroCell.seven, 0.0)
        assertTrue(lit.dots.zip(field.dots).any { (a, b) -> a.light > b.light + 0.02 })

        gyro.set(AgentActivity.thinking)
        frames(gyro, 0.1)
        assertTrue("The afterglow carries into Thinking", gyro.phosphor.decay > 0.25)
        assertTrue("The sparkles fade rather than vanish", gyro.phosphor.amount(GyroPattern.condense) > 0.3)
        frames(gyro, 3.0)
        assertEquals("Thinking has no afterglow of its own", 0.0, gyro.phosphor.decay, 0.0)
        assertEquals(0.0, gyro.phosphor.amount(GyroPattern.condense), 0.0)
    }

    @Test fun startingUpCondensesOntoThinkingsBand() {
        val gyro = ActivityGyro(AgentActivity.idle)
        gyro.set(AgentActivity.startup)
        frames(gyro, 0.1)
        assertTrue("Starting Up opens on sparkles, not the band", gyro.strokes[0][GyroSpringKey.k].value < 0.1)
        frames(gyro, 1.2)
        assertTrue("The band has condensed", gyro.strokes[0][GyroSpringKey.k].value > 0.85)
        // Already turning at Thinking's speed, so Thinking takes over in motion.
        assertTrue(abs(gyro.strokes[0][GyroAngleKey.theta].vel - Gyro.TAU / 1.8) < 0.05)
    }

    @Test fun doneSweepsItsFlashRoundTheRingThenCools() {
        val gyro = ActivityGyro(AgentActivity.streaming)
        gyro.set(AgentActivity.done)
        val top = cell(row = 0, column = 3)
        val bottom = cell(row = 6, column = 3)
        frames(gyro, 0.18)
        assertTrue("Nothing flashes before the dots land", gyro.phosphor.heat.all { it == 0.0 })
        frames(gyro, 0.1)
        assertTrue("The sweep starts at the top", gyro.phosphor.heat[top] > 0.1)
        assertEquals("and reaches the bottom later", 0.0, gyro.phosphor.heat[bottom], 0.0)
        frames(gyro, 0.2)
        assertTrue(gyro.phosphor.heat[bottom] > 0.05)

        val peak = frames(gyro, 0.2).maxOf { it.heat }
        assertTrue(peak > 0.5)
        val later = frames(gyro, 1.5).last().heat
        assertTrue("It cools over about 0.9 s", later < peak * 0.4 && later > 0.02)
        repeat(600) { if (!gyro.isResting) advance(gyro, 1.0 / 60) }
        assertTrue(gyro.isResting)
        assertTrue(gyro.render(GyroCell.seven, 1.0 / 60).heat < 0.005)
    }

    @Test fun failedEmberFlashesEveryDotTogetherAndFadesSlowly() {
        val gyro = ActivityGyro(AgentActivity.tool)
        gyro.set(AgentActivity.failed)
        frames(gyro, 0.25)
        val heated = gyro.phosphor.heat.count { it > 0 }
        assertEquals("All at once", GyroCell.seven.size, heated)
        frames(gyro, 0.1)
        val a = gyro.phosphor.heat[cell(row = 3, column = 3)]
        frames(gyro, 1.2)
        val b = gyro.phosphor.heat[cell(row = 3, column = 3)]
        // One decay length of 1.2 s.
        assertTrue(abs(b / a - exp(-1.0)) < 0.03)
    }

    @Test fun leavingDoneTheRingKeepsItsGreenAsItDims() {
        val gyro = ActivityGyro(AgentActivity.done)
        val settled = advance(gyro, 1.0 / 60)
        val ring = settled.dots.indices.filter { settled.dots[it].accent > 0.9 }
        assertTrue(ring.isNotEmpty())
        gyro.set(AgentActivity.idle)
        frames(gyro, 0.3)
        val raw = gyro.fill(GyroCell.seven)
        val drawn = gyro.render(GyroCell.seven, 0.0)
        // The engine's own green has nearly gone; the drawn one cools with Idle's 0.5 s afterglow.
        assertTrue((raw.weights[ActivityAccent.green] ?: 0.0) < 0.2)
        assertTrue((drawn.weights[ActivityAccent.green] ?: 0.0) > 0.45)
        for (i in ring) {
            assertTrue("Ring dot $i cools slowly", drawn.dots[i].light > raw.dots[i].light)
            assertTrue("Ring dot $i stays green as it dims", drawn.dots[i].accent > 0.4)
        }
        frames(gyro, 6.0)
        assertTrue((gyro.render(GyroCell.seven, 1.0 / 60).weights[ActivityAccent.green] ?: 0.0) < 0.005)
    }

    @Test fun idleShimmersWithoutABand() {
        val gyro = ActivityGyro(AgentActivity.idle)
        assertEquals("shimmer", gyro.design.id)
        assertEquals(GyroAfterglow(attack = 0.15, decay = 0.5), gyro.design.afterglow)
        assertEquals("No band", 0.0, gyro.strokes[0][GyroSpringKey.k].value, 0.0)
        assertTrue(gyro[ActivityGyro.Global.body].value == 0.68 && gyro[ActivityGyro.Global.glow].value == 0.14 && gyro[ActivityGyro.Global.ring].value == 0.0)

        val cells = GyroCell.seven
        // Each dot is 0.22 · s¹⁰ of its own sine: it fades in over 0.6 s, crests at 0.22, and few crest at once.
        assertTrue(cells.all { GyroPattern.shimmer.light(it, shape = 0.0, t = 0.0) == 0.0 })
        var peak = 0.0
        var lit = 0.0
        var samples = 0.0
        var t = 0.6
        while (t < 12) {
            val values = cells.map { GyroPattern.shimmer.light(it, shape = 0.0, t = t) }
            peak = max(peak, values.max())
            lit += values.count { it > 0.05 }.toDouble() / cells.size
            samples += 1
            t += 0.05
        }
        assertTrue(abs(peak - 0.22) < 0.005)
        assertTrue("Only the crests show", lit / samples < 0.35)
        val i = cells.indexOfFirst { it.row == 3 && it.column == 3 }
        val f = 0.14 + 0.1 * GyroPattern.hash(24 + 17)
        val s = 0.5 + 0.5 * sin(Gyro.TAU * (2 * f + GyroPattern.hash(24)))
        assertTrue(abs(GyroPattern.shimmer.light(cells[i], shape = 0.0, t = 2.0) - 0.22 * s.pow(10)) < 1e-12)

        // It glitters on: Idle never comes to rest.
        repeat(300) { advance(gyro, 1.0 / 60) }
        assertFalse(gyro.isResting)
    }

    @Test fun reduceMotionHoldsTheShimmerStill() {
        val gyro = ActivityGyro(AgentActivity.idle, reduceMotion = true)
        val first = advance(gyro, 1.0 / 60)
        frames(gyro, 2.0)
        val later = advance(gyro, 1.0 / 60)
        assertTrue(gyro.isResting)
        val raw = gyro.fill(GyroCell.seven)
        for ((i, cell) in GyroCell.seven.withIndex()) {
            assertEquals("Still", first.dots[i].light, later.dots[i].light, 0.0)
            val expected = min(1.2, raw.dots[i].light + 0.95 * GyroPattern.shimmer(cell, 2.4))
            assertTrue("The shimmer's pose at 2.4 s", abs(later.dots[i].light - expected) < 1e-9)
        }
    }

    @Test fun idleShimmerTurnsIntoStartingUpsSparkles() {
        val gyro = ActivityGyro(AgentActivity.idle)
        frames(gyro, 2.0)
        gyro.set(AgentActivity.startup)
        frames(gyro, 0.1)
        assertTrue(gyro.phosphor.amount(GyroPattern.condense) > 0.75)
        val shimmer = gyro.phosphor.amount(GyroPattern.shimmer)
        assertTrue("The shimmer fades out over 200 ms as the sparkles come in", shimmer > 0.4 && shimmer < 0.8)
        frames(gyro, 1.5)
        assertEquals(0.0, gyro.phosphor.amount(GyroPattern.shimmer), 0.0)
    }

    @Test fun firstSightOfAFinalStateNeverFlashes() {
        for (activity in listOf(AgentActivity.done, AgentActivity.failed)) {
            val gyro = ActivityGyro(activity)
            val peak = frames(gyro, 1.0).maxOf { it.heat }
            assertEquals("$activity shown settled", 0.0, peak, 0.0)
        }
    }

    @Test fun reduceMotionHasNoAfterglowOrFlash() {
        for ((from, to) in listOf(
            AgentActivity.streaming to AgentActivity.done,
            AgentActivity.tool to AgentActivity.failed,
            AgentActivity.idle to AgentActivity.startup,
        )) {
            val gyro = ActivityGyro(from, reduceMotion = true)
            gyro.set(to)
            for (field in frames(gyro, 2.0)) assertEquals(0.0, field.heat, 0.0)
            val raw = gyro.fill(GyroCell.seven)
            val drawn = gyro.render(GyroCell.seven, 1.0 / 60)
            if (to != AgentActivity.startup) {
                for ((d, r) in drawn.dots.zip(raw.dots)) assertTrue("$to: no afterglow", abs(d.light - r.light) < 1e-9)
            }
            assertTrue(gyro.phosphor.attack == 0.0 || to == AgentActivity.startup)
        }
    }

    @Test fun settledColoursAreDeepGreenAndWarmRedCoolingFromHot() {
        val rgb = AgentActivityOrb::RGB
        assertEquals(rgb(0.20, 0.80, 0.42), AgentActivityOrb.dark.accents[ActivityAccent.green]!!.first)
        assertEquals(rgb(1.0, 0.32, 0.24), AgentActivityOrb.dark.accents[ActivityAccent.red]!!.first)
        assertEquals(rgb(0.06, 0.55, 0.26), AgentActivityOrb.light.accents[ActivityAccent.green]!!.first)
        assertEquals(rgb(0.85, 0.17, 0.12), AgentActivityOrb.light.accents[ActivityAccent.red]!!.first)
        assertEquals(rgb(0.86, 1.0, 0.90), AgentActivityOrb.dark.hot(mapOf(ActivityAccent.green to 1.0))!!.first)
        assertEquals(rgb(1.0, 0.46, 0.36), AgentActivityOrb.light.hot(mapOf(ActivityAccent.red to 1.0))!!.first)
        assertNull("Only Done and Failed flash", AgentActivityOrb.dark.hot(mapOf(ActivityAccent.orange to 1.0)))

        // Settled, every lit dot of the ring is the accent, and nothing is hot.
        val gyro = ActivityGyro(AgentActivity.streaming)
        gyro.set(AgentActivity.done)
        repeat(900) { if (!gyro.isResting) advance(gyro, 1.0 / 60) }
        val field = gyro.render(GyroCell.seven, 1.0 / 60)
        assertTrue(field.heat < 0.005)
        assertTrue(field.dots.any { it.accent > 0.95 })
        assertTrue((field.weights[ActivityAccent.green] ?: 0.0) > 0.99)
    }

    @Test fun movingStatesKeepMovingAndReduceMotionHoldsStill() {
        for (activity in AgentActivity.entries.filter { !it.isFinal }) {
            val moving = ActivityGyro(activity)
            repeat(240) { advance(moving, 1.0 / 60) }
            assertFalse("$activity keeps moving", moving.isResting)

            val still = ActivityGyro(AgentActivity.idle, reduceMotion = true)
            still.set(activity)
            repeat(240) { if (!still.isResting) advance(still, 1.0 / 60) }
            assertTrue("$activity holds a still pose under Reduce Motion", still.isResting)
        }
    }

    @Test fun turningBandNeverDoublesBack() {
        // Thinking's band spins; entering Waiting, the chase must keep turning the same way.
        val gyro = ActivityGyro(AgentActivity.thinking)
        repeat(60) { gyro.step(1.0 / 60) }
        val direction = gyro.strokes[0][GyroAngleKey.theta].vel.sign
        gyro.set(AgentActivity.waiting)
        var last = gyro.strokes[0][GyroAngleKey.theta].value
        repeat(120) {
            gyro.step(1.0 / 60)
            val value = gyro.strokes[0][GyroAngleKey.theta].value
            if (direction > 0) assertTrue(value >= last - 1e-6) else assertTrue(value <= last + 1e-6)
            last = value
        }
    }

    @Test fun eachStateDrawsItsAccent() {
        val colored = mapOf(
            AgentActivity.waiting to ActivityAccent.orange, AgentActivity.failed to ActivityAccent.red,
            AgentActivity.done to ActivityAccent.green, AgentActivity.handoff to ActivityAccent.blue,
        )
        for (activity in AgentActivity.entries) {
            val gyro = ActivityGyro(activity)
            val field = gyro.fill(GyroCell.seven)
            assertEquals(GyroCell.seven.size, field.dots.size)
            for (accent in ActivityAccent.entries) {
                val weight = field.weights[accent] ?: 0.0
                assertTrue("$activity $accent", abs(weight - (if (colored[activity] == accent) 1.0 else 0.0)) < 0.01)
            }
        }
    }

    @Test fun subagentStatesComeFromAppStateAndTheLedger() {
        val now = AgentActivityClock.now()
        assertEquals(AgentActivity.failed, SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.failed).activity(now))
        assertEquals(AgentActivity.done, SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.done).activity(now))
        assertEquals(AgentActivity.handoff, SubagentActivity(sessionID = null, phase = SubagentActivity.Phase.spawning).activity(now))
        assertEquals(AgentActivity.waiting, SubagentActivity(sessionID = "s", phase = SubagentActivity.Phase.running, needsInput = true).activity(now))

        val ledger = AgentActivityLedger.shared
        val id = "ses_orb_${UUID.randomUUID()}"
        val subagent = SubagentActivity(sessionID = id, phase = SubagentActivity.Phase.running)
        ledger.record(OpenCodeServerEvent.ExecutionStarted(sessionID = id), now)
        assertEquals(AgentActivity.startup, subagent.activity(now + 0.5))
        assertEquals(AgentActivity.thinking, subagent.activity(now + 2))
        ledger.record(
            OpenCodeServerEvent.ContentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.ToolCalled(messageID = "m", toolID = "t", input = OpenCodeJSONValue.Null))),
            now + 3,
        )
        assertEquals(AgentActivity.tool, subagent.activity(now + 3.5))
        assertEquals(1, subagent.counts.tool)
        ledger.record(
            OpenCodeServerEvent.ContentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "m", ordinal = 0, delta = "hi"))),
            now + 6,
        )
        assertEquals(AgentActivity.streaming, subagent.activity(now + 6.2))
        assertEquals(AgentActivity.longWork, subagent.activity(now + 15))
    }

    /** A 0.55 s Done or Failed flash, then 0.34 s into the copy button. */
    @Test fun workingFinaleIsShortThenHandsOffToTheCopyButton() {
        assertTrue(abs(WorkingRun.Finale(id = 1, kind = WorkingRun.Finale.Kind.done, turn = null).duration - 0.89) < 1e-9)
        assertEquals(WorkingRun.SETTLE, WorkingRun.Finale(id = 1, kind = WorkingRun.Finale.Kind.pending, turn = null).duration, 0.0)
        assertTrue(abs(WorkingActivity.finaleRate - 1.2 / 0.55) < 1e-9)

        val start = 1000.0
        val done = WorkingActivity(phase = WorkingActivity.Phase.done, finaleStartedAt = start, handsOffToCopy = true)
        assertEquals("The flash plays out whole", 0.0, done.handoff(start + 0.3), 0.0)
        assertEquals(0.0, done.handoff(start + 0.55), 0.0)
        assertTrue(abs(done.handoff(start + 0.55 + 0.17) - 0.5) < 1e-9)
        assertEquals(1.0, done.handoff(start + 2), 0.0)
        assertEquals(0.0, WorkingActivity(phase = WorkingActivity.Phase.running).handoff(start), 0.0)
    }

    @Test fun workingHandoffBlursIntoTheCopyGlyphWithoutAJump() {
        fun pose(progress: Double, copy: Boolean = true, rm: Boolean = false) = WorkingHandoffPose.of(progress, handsOffToCopy = copy, reduceMotion = rm)
        val first = pose(0.0)
        assertTrue(first.indicatorOpacity == 1.0 && first.indicatorBlur == 0.0 && first.indicatorScale == 1.0 && first.copyOpacity == 0.0)
        val last = pose(1.0)
        assertTrue(last.indicatorOpacity == 0.0 && last.copyOpacity == 1.0 && last.copyBlur == 0.0 && last.copyScale == 1.0)
        assertTrue("It shrinks toward the copy glyph", abs(last.indicatorScale - 0.5) < 1e-9)
        // The two overlap: the glyph sharpens in while the indicator leaves.
        val middle = pose(0.5)
        assertTrue(middle.indicatorOpacity > 0.05 && middle.copyOpacity > 0.3)

        // Continuous: at 240 Hz no value steps far, so no frame jumps.
        var previous = first
        for (i in 1..240) {
            val p = pose(i / 240.0)
            assertTrue(abs(p.indicatorBlur - previous.indicatorBlur) < 0.05)
            assertTrue(abs(p.copyBlur - previous.copyBlur) < 0.05)
            assertTrue(abs(p.indicatorOpacity - previous.indicatorOpacity) < 0.02)
            assertTrue(abs(p.copyOpacity - previous.copyOpacity) < 0.02)
            previous = p
        }

        // No reply text: the line only leaves.
        assertEquals(0.0, pose(1.0, copy = false).copyOpacity, 0.0)
        // Reduce Motion: a cross-fade, nothing blurs or scales.
        for (i in 0..10) {
            val p = pose(i / 10.0, rm = true)
            assertTrue(p.indicatorBlur == 0.0 && p.copyBlur == 0.0 && p.indicatorScale == 1.0 && p.copyScale == 1.0)
        }
        assertEquals(1.0, pose(1.0, rm = true).copyOpacity, 0.0)
    }

    @Test fun workingOrbStartsUpThinksWaitsAndFinishes() {
        val now = AgentActivityClock.now()
        val running = WorkingActivity(phase = WorkingActivity.Phase.running, startedAt = now)
        assertEquals(AgentActivity.startup, running.activity(now + 0.5))
        assertEquals(AgentActivity.thinking, running.activity(now + 2))
        // Opening onto a run already going: no entrance.
        assertEquals(AgentActivity.thinking, WorkingActivity(phase = WorkingActivity.Phase.running, startedAt = null).activity(now))
        assertEquals(AgentActivity.waiting, WorkingActivity(phase = WorkingActivity.Phase.running, startedAt = now, needsInput = true).activity(now + 0.5))
        assertEquals(AgentActivity.done, WorkingActivity(phase = WorkingActivity.Phase.done, startedAt = now).activity(now + 9))
        assertEquals(AgentActivity.failed, WorkingActivity(phase = WorkingActivity.Phase.failed, startedAt = now).activity(now + 9))
        assertEquals(
            AgentActivity.thinking,
            AgentActivitySource.Session(running).activity(now + 2, lab = AgentActivityOverride(mode = AgentActivityOverride.Mode.State(AgentActivity.tool), start = now)),
        )
    }

    @Test fun workingRunOutcomeComesFromTheLastTurnTheServerAnswered() {
        val reply = OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-text-0", text = "")
        val hi = OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi")
        assertNull(WorkingRun.outcome(emptyList()))
        assertEquals(WorkingRun.Outcome.done, WorkingRun.outcome(listOf(hi, reply)))
        assertEquals(
            WorkingRun.Outcome.failed,
            WorkingRun.outcome(listOf(hi, reply, OpenCodeTranscriptItem.Info(id = "m2-error", text = "Run failed: ProviderError — overloaded"))),
        )
        assertEquals(WorkingRun.Outcome.interrupted, WorkingRun.outcome(listOf(hi, OpenCodeTranscriptItem.Info(id = "m2-error", text = "Run failed: aborted"))))
        // An earlier turn's error doesn't fail this one.
        assertEquals(
            WorkingRun.Outcome.done,
            WorkingRun.outcome(listOf(OpenCodeTranscriptItem.Info(id = "m0-error", text = "Run failed: ProviderError"), OpenCodeTranscriptItem.UserText(id = "m1", text = "Again"), reply)),
        )
        // Timeline motion audit (compaction-error f986): a later run in the same
        // turn answered after the error. Read as Failed, its finale hid the
        // earlier error row for 0.9 s and flashed Failed over a good reply.
        val error = OpenCodeTranscriptItem.RunError(id = "m2-error", error = OpenCodeRunError(type = "unknown", message = "Overloaded"))
        val later = OpenCodeTranscriptItem.AssistantMarkdown(id = "m3-text-0", text = "")
        assertEquals(WorkingRun.Outcome.done, WorkingRun.outcome(listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi"), reply, error, later)))
        assertEquals(WorkingRun.Outcome.failed, WorkingRun.outcome(listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi"), reply, error)))
        // A send the server never answered: nothing to flash for.
        assertNull(WorkingRun.outcome(listOf(OpenCodeTranscriptItem.UserText(id = "m0", text = "Hi"), reply, OpenCodeTranscriptItem.UserText(id = "m1", text = "Again"))))
    }

    private fun signal(running: Boolean, items: List<OpenCodeTranscriptItem>, session: String? = "s", interrupting: Boolean = false) =
        WorkingRun.Signal(sessionID = session, isRunning = running, isInterrupting = interrupting, items = items)

    /**
     * A stopped run ends on “Interrupted”: a neutral end, never Failed's flash,
     * even when the run's end reached the screen before Stop's own state did.
     */
    @Test fun aStoppedRunIsANeutralEndNotAFailure() {
        val user = OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi")
        val reply = OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-text-0", text = "")
        val stopped = OpenCodeTranscriptItem.Info(id = "m2-error", text = "Interrupted")
        assertEquals(WorkingRun.Outcome.interrupted, WorkingRun.outcome(listOf(user, reply, stopped)))
        assertEquals(WorkingRun.Outcome.interrupted, WorkingRun.outcome(listOf(user, stopped)))

        // Stopped from another client: the run ends without this screen's Stop.
        var run = WorkingRun()
        run = run.update(signal(false, emptyList()), signal(true, listOf(user)), items = listOf(user), failedSends = emptySet())
        run = run.update(signal(true, listOf(user)), signal(false, listOf(user, reply, stopped)), items = listOf(user, reply, stopped), failedSends = emptySet())
        assertNull("No Failed flash for a stop", run.finale)
    }

    /** Stop is on its way: the line reads Stopping… and the orb holds a calm pose. */
    @Test fun stoppingReadsStoppingAndHoldsTheOrb() {
        val now = AgentActivityClock.now()
        val stopping = WorkingActivity(phase = WorkingActivity.Phase.stopping, startedAt = now)
        assertEquals("Stopping…", stopping.title)
        assertEquals("Not Starting Up, even within its first moments", AgentActivity.idle, stopping.activity(now))
        assertEquals("Stopping…", WorkingActivity(phase = WorkingActivity.Phase.stopping, needsInput = true).title)
        assertEquals("Working", WorkingActivity(phase = WorkingActivity.Phase.running).title)
        assertEquals("Needs your input", WorkingActivity(phase = WorkingActivity.Phase.running, needsInput = true).title)
    }

    @Test fun workingRunStartsEndsAndElapses() {
        val user = OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi")
        val reply = OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-text-0", text = "")
        var run = WorkingRun()
        run = run.update(signal(false, emptyList()), signal(true, listOf(user)), items = listOf(user), failedSends = emptySet())
        assertTrue(run.startID == 1 && run.key == "s" && run.finale == null)
        run = run.elapsed(1)
        assertNull(run.startID)
        run = run.update(signal(true, listOf(user)), signal(false, listOf(user, reply)), items = listOf(user, reply), failedSends = emptySet())
        assertEquals(WorkingRun.Finale(id = 2, kind = WorkingRun.Finale.Kind.done, turn = "m1"), run.finale)
        run = run.elapsed(2)
        assertNull(run.finale)

        // Stopped: no flash.
        val more = listOf(user, reply, OpenCodeTranscriptItem.UserText(id = "m3", text = "More"), OpenCodeTranscriptItem.AssistantMarkdown(id = "m4-text-0", text = ""))
        run = run.update(signal(false, listOf(user, reply)), signal(true, more), items = more, failedSends = emptySet())
        run = run.update(signal(true, more, interrupting = true), signal(false, more), items = more, failedSends = emptySet())
        assertNull(run.finale)

        // Another session swapped in: nothing this screen saw.
        run = run.update(signal(false, more), signal(true, more), items = more, failedSends = emptySet())
        run = run.update(signal(true, more), signal(false, listOf(user, reply), session = "other"), items = listOf(user, reply), failedSends = emptySet())
        assertTrue(run.finale == null && run.startID == null && run.key == null)
        assertEquals("Numbers carry on", 4, run.counter)

        // A draft's run keys its orb by the run, so two drafts never share one.
        var draft = WorkingRun(counter = 7)
        draft = draft.update(signal(false, emptyList(), session = null), signal(true, listOf(user), session = null), items = listOf(user), failedSends = emptySet())
        assertEquals("draft-8", draft.key)
    }

    @Test fun subagentRunningBeforeTheStreamConnectedStillReachesLongWork() {
        val now = AgentActivityClock.now()
        val id = "ses_orb_${UUID.randomUUID()}"
        val subagent = SubagentActivity(sessionID = id, phase = SubagentActivity.Phase.running)
        // Its execution started before the stream connected: only content arrives.
        AgentActivityLedger.shared.record(
            OpenCodeServerEvent.ContentBatch(sessionID = id, events = listOf(OpenCodeLiveContentEvent.ReasoningDelta(messageID = "m", ordinal = 0))),
            now,
        )
        assertEquals(AgentActivity.thinking, subagent.activity(now + 1))
        assertEquals(AgentActivity.longWork, subagent.activity(now + 9))
    }

    // endregion
}
