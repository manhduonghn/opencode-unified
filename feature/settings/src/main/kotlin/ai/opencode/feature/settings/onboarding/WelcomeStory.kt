package ai.opencode.feature.settings.onboarding

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.strings.localized
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableSpring
import ai.opencode.core.tuning.TuningContext
import ai.opencode.core.tuning.TuningStore
import ai.opencode.feature.settings.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The welcome scene's timing and springs. */
object WelcomeTunables {
    val words = Tunable("welcome.story.words", "Dictation, words a second", default = 3.2, range = 1.5..6.0, step = 0.1)
    val stream = Tunable("welcome.story.stream", "Reply, words a second", default = 14.0, range = 6.0..30.0, step = 1.0)
    val travel = Tunable("welcome.story.travel", "Message travel", default = 0.75, range = 0.3..1.4, step = 0.05)
    val browse = Tunable("welcome.story.browse", "Browser before the edit", default = 0.8, range = 0.3..2.0, step = 0.05)
    val proof = Tunable("welcome.story.proof", "Edited page before screenshot", default = 0.6, range = 0.2..2.0, step = 0.05)
    val hold = Tunable("welcome.story.hold", "Hold the result", default = 1.6, range = 0.5..6.0, step = 0.1)
    val lead = Tunable("welcome.story.lead", "Wait before dictating", default = 1.5, range = 0.0..4.0, step = 0.1)
    val dim = Tunable("welcome.story.dim", "Unfocused device dim", default = 0.42, range = 0.0..0.8, step = 0.01)

    val devices = TunableSpring("welcome.motion.devices", "Devices settle", duration = 0.8, bounce = 0.12)
    val intro = TunableSpring("welcome.motion.intro", "Copy entrance", duration = 0.6, bounce = 0.0)
    val focus = TunableSpring("welcome.motion.focus", "Focus shift", duration = 0.65, bounce = 0.0)
    val enter = TunableSpring("welcome.motion.enter", "Rows enter", duration = 0.42, bounce = 0.0)
    val lift = TunableSpring("welcome.motion.lift", "Bubble lift", duration = 0.52, bounce = 0.08)
    val grow = TunableSpring("welcome.motion.grow", "Button grows", duration = 0.48, bounce = 0.15)
    val replace = TunableSpring("welcome.motion.replace", "Loop restart", duration = 0.55, bounce = 0.0)
    val step = TunableSpring("welcome.motion.step", "Next step", duration = 0.55, bounce = 0.0)
    val pearlArc = Tunable("welcome.motion.pearlArc", "Light arc lift", default = 0.25, range = 0.05..0.5, step = 0.01)
    val shotArc = Tunable("welcome.motion.shotArc", "Screenshot arc lift", default = 0.16, range = 0.02..0.4, step = 0.01)
    val shotScale = Tunable("welcome.motion.shotScale", "Screenshot apex lift", default = 0.09, range = 0.0..0.25, step = 0.01)

    /** Lists every knob up front: most are read only inside the running story. */
    fun register() {
        val store = TuningStore.shared
        store.register(words, stream, travel, browse, proof, hold, lead, dim)
        store.register(pearlArc, shotArc, shotScale)
        for (spring in listOf(devices, intro, focus, enter, lift, grow, replace, step)) store.register(spring)
    }
}

val TuningContext.Companion.welcome: TuningContext get() = TuningContext("welcome", label = "Welcome")

/**
 * The story the scene tells, as times in story seconds: the person dictates a task on the phone and
 * sends it; OpenCode on the laptop reads the repo, opens OpenCode's own dark-mode site in a
 * browser, edits `Hero.tsx`, watches the “Download for Android” button spring open and takes a
 * screenshot; the screenshot and reply land on the phone; the person dictates “Ship it” and the
 * laptop runs `git push`. Every frame is a pure function of time, so the loop never accumulates
 * state and Reduce Motion draws the proof moment.
 */
data class WelcomeStory(
    val words: Double = 3.2,
    val stream: Double = 14.0,
    val travel: Double = 0.75,
    val browse: Double = 0.8,
    val proof: Double = 0.6,
    val hold: Double = 1.6,
    val dim: Double = 0.42,
    val enter: MotionSpring = MotionSpring(0.42, 0.0),
    val lift: MotionSpring = MotionSpring(0.52, 0.08),
    val grow: MotionSpring = MotionSpring(0.48, 0.15),
    val focus: MotionSpring = MotionSpring(0.65, 0.0),
    val replace: MotionSpring = MotionSpring(0.55, 0.0),
    val pearlArc: Double = 0.25,
    val shotArc: Double = 0.16,
    val shotScale: Double = 0.09,
) {
    // The prompt.
    val listen = 0.5

    /** The first word lands this long after the mic opens. */
    val firstWord = 0.35
    val promptEnd: Double get() = listen + firstWord + PROMPT_WORDS.size / words
    val send: Double get() = promptEnd + 0.28
    val launch: Double get() = send + 0.08
    val arrive: Double get() = launch + travel
    val titled: Double get() = arrive + 0.9

    // The laptop's work.
    val explore: Double get() = arrive + 0.3
    val open: Double get() = explore + 0.52
    val edit: Double get() = open + browse
    val grown: Double get() = edit + 0.28
    val shot: Double get() = grown + proof
    val close: Double get() = shot + 0.42
    val deskReply: Double get() = shot + 0.35

    // The screenshot comes back.
    val back: Double get() = shot + 0.06
    val land: Double get() = back + travel
    val phoneReply: Double get() = land + 0.16
    val streaming: Double get() = REPLY_WORDS.size / stream
    val replied: Double get() = phoneReply + streaming

    // “Ship it”.
    val again: Double get() = replied + 0.5
    val shipEnd: Double get() = again + firstWord + SHIP_WORDS.size / words
    val send2: Double get() = shipEnd + 0.24
    val launch2: Double get() = send2 + 0.08
    val arrive2: Double get() = launch2 + travel
    val run2: Double get() = arrive2 + 0.36

    val end: Double get() = run2 + hold
    val loop: Double get() = end + replace.duration + 0.2

    /** The proof moment, for Reduce Motion: the screenshot and the reply on both screens. */
    val still: Double get() = replied + 0.3

    /** A row's entrance progress, from story time [start]. */
    fun entered(t: Double, at: Double, spring: MotionSpring? = null): Double = progress(t - at, spring ?: enter)

    /** Words heard of an utterance whose mic opened at [from]: fractional, so each word can fade in as it's heard. */
    fun heard(t: Double, from: Double): Double = max(0.0, (t - from - firstWord) * words + 1)

    /** The scene at story time [t]. [focusOn] fades the focus dim in after the entrance; [reduced] drops the lights and dims. */
    fun frame(t: Double, focusOn: Double = 1.0, reduced: Boolean = false): WelcomeFrame {
        val loopFade = if (reduced) 0.0 else entered(t, end, replace)
        val live = 1 - loopFade

        // The phone's composer: the mic opens, words arrive, ↑ sends.
        val secondUtterance = t >= again - 0.2
        val listenAt = if (secondUtterance) again else listen
        val sendAt = if (secondUtterance) send2 else send
        val wordCount = if (secondUtterance) SHIP_WORDS.size else PROMPT_WORDS.size

        // The eye: the phone while dictating, the laptop while it works, the phone for the proof
        // and “Ship it”, the laptop as it ships.
        val laptopFocus = if (reduced) 0.0 else
            (entered(t, send + 0.1, focus) - entered(t, back + 0.22, focus) + entered(t, send2 + 0.1, focus)) * live

        // The browser: it opens on the dark OpenCode page, hot-reloads the edit as the button
        // grows, flashes the screenshot, and recedes as the screenshot lifts toward the phone.
        val reloadProgress = ((t - edit) / 0.28).clamped01()

        return WelcomeFrame(
            transcript = live,
            loopBlur = 4 * loopFade,
            pearlArc = pearlArc,
            shotArc = shotArc,
            shotScale = shotScale,
            utterance = if (secondUtterance) 1 else 0,
            dictation = entered(t, listenAt) * (1 - entered(t, sendAt)),
            heard = min(wordCount + 2.0, heard(t, listenAt)),
            draft = 1 - (entered(t, sendAt) * 4).clamped01(),
            // The words' line opens over the bar as the first word is heard.
            lines = entered(t, listenAt + firstWord - 0.08) * (1 - entered(t, sendAt + 0.05)),
            voice = if (reduced) 0.0 else voice(t - listenAt - firstWord + 0.12, wordCount, words) * (1 - entered(t, sendAt)),

            // The phone's transcript.
            prompt = if (t >= send) entered(t, send, lift) else 0.0,
            toolCount = when {
                t >= land -> 5
                t >= edit -> 4
                t >= open -> 3
                t >= explore -> 2
                else -> 0
            },
            tools = entered(t, explore),
            // The screenshot's slot opens while it flies; once it lands the resting tile is 100%
            // opaque immediately underneath the cooling carrier so two half-alpha layers never dip.
            screenshotSlot = entered(t, back + 0.12),
            screenshot = if (t >= land) 1.0 else 0.0,
            reply = entered(t, phoneReply),
            replyWords = (t - phoneReply) * stream,
            working = entered(t, send + 0.24) * (1 - entered(t, replied)),
            ship = if (t >= send2) entered(t, send2, lift) else 0.0,
            working2 = entered(t, send2 + 0.24),
            named = t >= titled && live > 0.5,

            // The laptop.
            deskPrompt = entered(t, arrive - 0.06),
            explore = entered(t, explore),
            explored = entered(t, open - 0.1),
            browse = entered(t, open),
            edit = entered(t, edit),
            edited = entered(t, grown),
            screenshotRow = entered(t, shot),
            deskWords = (t - deskReply) * stream,
            deskReply = entered(t, deskReply),
            deskShip = entered(t, arrive2 - 0.06),
            run = entered(t, run2),
            deskWorking = (entered(t, arrive) * (1 - entered(t, deskReply + streaming)) + entered(t, arrive2)) * live,
            deskNamed = entered(t, titled) * live,
            followUp = entered(t, arrive) * live,
            browser = entered(t, open) * (1 - entered(t, close)),
            reload = 1 - (1 - reloadProgress).pow(2.2),
            reloading = ((t - edit) / 0.06).clamped01() * (1 - ((t - grown - 0.04) / 0.24).clamped01()),
            buttonGrow = entered(t, grown, grow),
            buttonSheen = if (reduced) 0.0 else ((t - grown - 0.05) / 0.46).clamped01(),
            browserRecede = if (reduced) 0.0 else entered(t, back),
            flash = if (reduced) 0.0 else flash(t - shot),

            laptopDim = if (reduced) 0.0 else dim * focusOn * (1 - laptopFocus),
            phoneDim = if (reduced) 0.0 else dim * focusOn * laptopFocus,
            outbound = if (reduced) null else flight(t - launch, live),
            inbound = if (reduced) null else flight(t - back, live),
            outbound2 = if (reduced) null else flight(t - launch2, live),
        )
    }

    /** A flight [tau] seconds after it leaves, or null when it isn't in the air or landing. */
    private fun flight(tau: Double, multiplier: Double): WelcomeFrame.Flight? {
        if (tau < 0 || tau > travel + WelcomeFrame.Flight.AFTERGLOW || multiplier <= 0.001) return null
        return WelcomeFrame.Flight(tau = tau, travel = travel, opacity = multiplier)
    }

    companion object {
        val PROMPT: String get() = localized(R.string.settings_onboarding_story_prompt)
        val SHIP_IT: String get() = localized(R.string.settings_onboarding_story_ship_it)
        val REPLY: String get() = localized(R.string.settings_onboarding_story_reply)
        val TITLE: String get() = localized(R.string.settings_onboarding_story_title)
        const val PROJECT = "opencode"
        val PROMPT_WORDS: List<String> get() = words(PROMPT)
        val SHIP_WORDS: List<String> get() = words(SHIP_IT)
        val REPLY_WORDS: List<String> get() = words(REPLY)

        fun words(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

        /** The tuned story. */
        val tuned: WelcomeStory
            get() = WelcomeStory(
                words = WelcomeTunables.words.value, stream = WelcomeTunables.stream.value,
                travel = WelcomeTunables.travel.value, browse = WelcomeTunables.browse.value,
                proof = WelcomeTunables.proof.value, hold = WelcomeTunables.hold.value, dim = WelcomeTunables.dim.value,
                enter = WelcomeTunables.enter.spring, lift = WelcomeTunables.lift.spring, grow = WelcomeTunables.grow.spring,
                focus = WelcomeTunables.focus.spring, replace = WelcomeTunables.replace.spring,
                pearlArc = WelcomeTunables.pearlArc.value, shotArc = WelcomeTunables.shotArc.value,
                shotScale = WelcomeTunables.shotScale.value,
            )

        /** Spring progress (0 → 1, overshooting with bounce) [tau] seconds after it starts. */
        fun progress(tau: Double, spring: MotionSpring): Double =
            if (tau <= 0) 0.0 else SpringSolver.evaluate(from = 0.0, to = 1.0, spring = spring, elapsed = tau).first

        /** The mic's level [tau] seconds into an utterance of [words] words: a syllable-paced envelope while speaking, silence before and after. */
        fun voice(tau: Double, words: Int, rate: Double): Double {
            val span = words / rate
            if (tau <= 0 || tau >= span + 0.15) return 0.0
            val envelope = (tau / 0.12).clamped01() * (1 - ((tau - span) / 0.15).clamped01())
            val syllables = 0.5 + 0.5 * sin(tau * rate * 2.1 * PI * 2)
            val phrase = 0.75 + 0.25 * sin(tau * 1.3 * PI * 2 + 0.6)
            return envelope * (0.28 + 0.6 * syllables * phrase)
        }

        /** The screenshot's flash: up in a few frames, cooling smoothly over a third of a second. */
        fun flash(tau: Double): Double {
            if (tau <= 0) return 0.0
            val cool = 1 - ((tau - 0.04) / 0.34).clamped01()
            return (tau / 0.04).clamped01() * cool * cool
        }
    }
}

/** One drawn moment of the story: what each device shows, mostly 0…1 progress values. */
data class WelcomeFrame(
    val transcript: Double = 1.0,
    val loopBlur: Double = 0.0,
    val pearlArc: Double = 0.25,
    val shotArc: Double = 0.16,
    val shotScale: Double = 0.09,

    // Phone composer.
    val utterance: Int = 0,
    val dictation: Double = 0.0,
    val heard: Double = 0.0,
    val draft: Double = 1.0,
    val lines: Double = 0.0,
    val voice: Double = 0.0,

    // Phone transcript.
    val prompt: Double = 0.0,
    val toolCount: Int = 0,
    val tools: Double = 0.0,
    val screenshotSlot: Double = 0.0,
    val screenshot: Double = 0.0,
    val reply: Double = 0.0,
    val replyWords: Double = 0.0,
    val working: Double = 0.0,
    val ship: Double = 0.0,
    val working2: Double = 0.0,
    val named: Boolean = false,

    // Laptop.
    val deskPrompt: Double = 0.0,
    val explore: Double = 0.0,
    val explored: Double = 0.0,
    val browse: Double = 0.0,
    val edit: Double = 0.0,
    val edited: Double = 0.0,
    val screenshotRow: Double = 0.0,
    val deskReply: Double = 0.0,
    val deskWords: Double = 0.0,
    val deskShip: Double = 0.0,
    val run: Double = 0.0,
    val deskWorking: Double = 0.0,
    val deskNamed: Double = 0.0,
    val followUp: Double = 0.0,
    val browser: Double = 0.0,
    val reload: Double = 0.0,
    val reloading: Double = 0.0,
    val buttonGrow: Double = 0.0,
    val buttonSheen: Double = 0.0,
    val browserRecede: Double = 0.0,
    val flash: Double = 0.0,

    val laptopDim: Double = 0.0,
    val phoneDim: Double = 0.0,
    val outbound: Flight? = null,
    val inbound: Flight? = null,
    val outbound2: Flight? = null,
) {
    /** A message's light in the air: [tau] seconds since it left a trip of [travel] seconds. */
    data class Flight(val tau: Double, val travel: Double, val opacity: Double) {
        companion object {
            /** How long the light glows where it landed. */
            const val AFTERGLOW = 0.45
        }
    }
}

/** Clamped to 0…1. */
internal fun Double.clamped01(): Double = min(max(this, 0.0), 1.0)
