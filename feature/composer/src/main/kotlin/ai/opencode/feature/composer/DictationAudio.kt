package ai.opencode.feature.composer

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.feature.voice.AudioSessionGeneration
import ai.opencode.feature.voice.AudioSessionOwner
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a recording ended, which decides how long its audio session stays
 * ready for the next press ([DictationHoldPolicy]).
 */
enum class DictationEnd(val rawValue: String) {
    /** Cancelled or discarded: nothing was kept. */
    Cancel("cancel"),

    /** Stopped into the draft, to edit or send later. */
    Insert("insert"),

    /** Sent as a message. */
    Send("send"),

    /** Interrupted (a phone call, a route change) or the recognizer failed: never held. */
    Failed("failed"),
}

/** Why a held session ends before its time runs out. */
enum class DictationHoldRelease(val rawValue: String) {
    /** The composer sent a message. Ends only a hold kept after an insert. */
    Sent("sent"),

    /** The person left the session. Ends only a hold kept after an insert. */
    Left("left"),

    /** A call is starting. */
    Call("call"),
    Expired("expired"),
    Background("background"),

    /** Another app started playing. */
    Silenced("silenced"),

    /** Someone else claimed the shared session ([AudioSessionGeneration]). */
    Claimed("claimed"),
    Interrupted("interrupted"),
    ;

    /** Whether it ends a hold kept after [end]. */
    fun ends(end: DictationEnd): Boolean = when (this) {
        Sent, Left -> end == DictationEnd.Insert
        else -> true
    }
}

/** How long the audio session stays ready after a recording, in seconds. */
object DictationAudioTunables {
    /** Hold after a cancel or short press (s). */
    const val cancelHold: Double = 10.0

    /** Hold after an insert (s). */
    const val insertHold: Double = 15.0

    /** Hold after an insert, media playing (s). */
    const val insertMediaHold: Double = 8.0

    /** Hold after a send (s). */
    const val sendHold: Double = 12.0

    /** Hold after a send, media playing (s). */
    const val sendMediaHold: Double = 0.0

    /** Hold after a second send, media playing (s). */
    const val sendMediaRepeatHold: Double = 6.0

    /** Short press (s). */
    const val shortPress: Double = 1.5

    /** Second press window (s). */
    const val repeatWindow: Double = 15.0
}

/**
 * A hold that outlives its use only moves any route reconfiguration later,
 * onto whatever plays. So the hold fits how the press ended.
 */
object DictationHoldPolicy {
    fun seconds(end: DictationEnd, recorded: Double, otherAudio: Boolean, backToBack: Boolean): Double {
        val t = DictationAudioTunables
        return when (end) {
            DictationEnd.Failed -> 0.0
            DictationEnd.Cancel -> t.cancelHold
            // A cancel or a quick press is usually followed by another try.
            DictationEnd.Insert -> when {
                recorded < t.shortPress -> t.cancelHold
                otherAudio -> t.insertMediaHold
                else -> t.insertHold
            }
            DictationEnd.Send -> when {
                recorded < t.shortPress -> t.cancelHold
                !otherAudio -> t.sendHold
                backToBack -> t.sendMediaRepeatHold
                else -> t.sendMediaHold
            }
        }
    }
}

/**
 * What dictation needs of the platform's audio. Dictation never requests audio focus, sets
 * `MODE_IN_COMMUNICATION` or starts Bluetooth SCO. Activation is its claim on the shared audio
 * ([AudioSessionOwner]); the preferred input goes to `AudioRecord.setPreferredDevice`. Sample rate
 * and buffer preferences move no hardware.
 */
interface DictationAudioSessionControlling {
    val isOtherAudioPlaying: Boolean
    val sampleRate: Double
    val ioBufferDuration: Double
    val preferredSampleRate: Double
    val preferredIOBufferDuration: Double
    val preferredInputPortType: String?
    val availableInputPortTypes: List<String>

    /** The current route, for content-free logging. */
    val outputPortTypes: List<String>
    val inputPortTypes: List<String>
    val isActive: Boolean

    fun setPreferredSampleRate(sampleRate: Double)
    fun setPreferredIOBufferDuration(duration: Double)
    fun setPreferredInput(portType: String)
    fun setActive(active: Boolean)

    /** The input device the recording should use, if one was preferred. */
    fun preferredInputDevice(): AudioDeviceInfo?
}

/** IOS `AVAudioSession.Port` raw values, so route logs read the same on both platforms. */
object DictationPort {
    const val builtInMic = "MicrophoneBuiltIn"
    const val headsetMic = "MicrophoneWired"
    const val usbAudio = "USBAudio"
    const val bluetoothHFP = "BluetoothHFP"
    const val bluetoothA2DP = "BluetoothA2DPOutput"
    const val bluetoothLE = "BluetoothLE"
    const val builtInSpeaker = "Speaker"
    const val builtInReceiver = "Receiver"
    const val headphones = "Headphones"
    const val hdmi = "HDMI"
    const val other = "Other"

    fun of(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> builtInMic
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (device.isSource) headsetMic else headphones
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> headphones
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> usbAudio
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> bluetoothHFP
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bluetoothA2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> bluetoothLE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> builtInSpeaker
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> builtInReceiver
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> hdmi
        else -> other
    }
}

class LiveDictationAudioSession(context: Context) : DictationAudioSessionControlling {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private var preferredRate = 0.0
    private var preferredBuffer = 0.0
    private var preferredInput: String? = null
    private var active = false

    override val isOtherAudioPlaying: Boolean get() = audioManager.isMusicActive
    override val sampleRate: Double
        get() = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toDoubleOrNull() ?: 0.0
    override val ioBufferDuration: Double
        get() {
            val frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toDoubleOrNull() ?: 0.0
            val rate = sampleRate
            return if (rate > 0) frames / rate else 0.0
        }
    override val preferredSampleRate: Double get() = synchronized(lock) { preferredRate }
    override val preferredIOBufferDuration: Double get() = synchronized(lock) { preferredBuffer }
    override val preferredInputPortType: String? get() = synchronized(lock) { preferredInput }
    override val availableInputPortTypes: List<String>
        get() = inputs().map(DictationPort::of).distinct()
    /**
     * Where media plays now: Android routes it to the highest-priority output
     * connected (wired or USB, then Bluetooth, then the speaker).
     */
    override val outputPortTypes: List<String>
        get() {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(DictationPort::of)
            val order = listOf(
                DictationPort.headphones, DictationPort.usbAudio, DictationPort.bluetoothA2DP,
                DictationPort.bluetoothLE, DictationPort.hdmi, DictationPort.builtInSpeaker,
            )
            return listOfNotNull(order.firstOrNull { it in outputs })
        }
    override val inputPortTypes: List<String>
        get() = listOf(preferredInputPortType ?: DictationPort.builtInMic)
    override val isActive: Boolean get() = synchronized(lock) { active }

    override fun setPreferredSampleRate(sampleRate: Double) = synchronized(lock) { preferredRate = sampleRate }
    override fun setPreferredIOBufferDuration(duration: Double) = synchronized(lock) { preferredBuffer = duration }
    override fun setPreferredInput(portType: String) {
        if (portType !in availableInputPortTypes) return
        synchronized(lock) { preferredInput = portType }
    }

    override fun setActive(active: Boolean) = synchronized(lock) { this.active = active }

    override fun preferredInputDevice(): AudioDeviceInfo? {
        val port = preferredInputPortType ?: return null
        return inputs().firstOrNull { DictationPort.of(it) == port }
    }

    private fun inputs(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
}

object DictationAudioSession {
    /**
     * A call that just ended releases the shared audio a moment later (after its
     * hang-up tail); claiming it first keeps that from cutting off dictation
     * mid-recording. Bumps [AudioSessionGeneration].
     */
    fun claim() {
        AudioSessionGeneration.bump()
    }

    /** The dictation mic is live, for diagnostics. */
    @Volatile
    var isRecording: Boolean = false
}

/** What ends a hold from outside. */
enum class DictationAudioEvent {
    /** The app's last activity stopped. */
    DidEnterBackground,

    /** An activity started again. */
    WillEnterForeground,

    /** Other audio started playing. */
    OtherAudioBegan,

    /** The audio mode turned to a call. */
    InterruptionBegan,

    /** The audio server died (`ERROR_DEAD_OBJECT`). */
    MediaServicesReset,

    /** An audio device came or went. */
    RouteChanged,
}

/**
 * Dictation's use of the shared audio: set up for a recording, then held ready
 * for a while after it ([DictationHoldPolicy]) or released.
 *
 * Dictation listens alongside whatever is playing, and never as a call: no
 * audio focus, no communication mode, no Bluetooth SCO, and a wired or USB mic
 * if there is one, else the phone's own.
 *
 * Every change runs in order on one serial executor, never on the main thread.
 */
class DictationAudio(private val environment: Environment) {
    class Environment(
        val session: DictationAudioSessionControlling,
        /** Who owns the shared audio ([AudioSessionGeneration]): a call or a press claiming it bumps it. */
        val generation: () -> Int,
        /** Seconds on a monotonic clock. */
        val uptime: () -> Double,
        val sleep: suspend (Duration) -> Unit,
        /** Registers the observer of [DictationAudioEvent]s. */
        val observe: ((DictationAudioEvent) -> Unit) -> Unit,
        /** Where the session is changed, in order. `null` changes it on the calling thread. */
        val queue: ExecutorService?,
        /** One content-free line: port types, counts, durations. */
        val log: (String) -> Unit,
        /** Shared activation with calls and media. `null` acts directly in tests. */
        val owner: AudioSessionOwner? = null,
    ) {
        companion object {
            fun live(context: Context): Environment = Environment(
                session = LiveDictationAudioSession(context),
                generation = { AudioSessionGeneration.value },
                uptime = { SystemClock.uptimeMillis() / 1000.0 },
                sleep = { delay(it) },
                observe = { handler -> LiveDictationAudioEvents.observe(context, handler) },
                queue = Executors.newSingleThreadExecutor { Thread(it, "ai.opencode.dictation-audio") },
                log = { line -> AppLog.voice.info(line) },
                owner = dependency(AudioSessionOwner),
            )

            /** Acts on nothing: for previews and tests. */
            fun inert(): Environment = Environment(
                session = InertDictationAudioSession(),
                generation = { 0 },
                uptime = { SystemClock.uptimeMillis() / 1000.0 },
                sleep = { delay(it) },
                observe = {},
                queue = null,
                log = {},
            )
        }
    }

    /** The sample rate and I/O buffer dictation asked for; `0` asks for none. */
    data class Preferred(val rate: Double, val buffer: Double)

    /** A session kept active after a recording. */
    data class Hold(
        val id: Int,
        val end: DictationEnd,
        val seconds: Double,
        val otherAudio: Boolean,
        val generation: Int,
        val startedAt: Double,
        val preferred: Preferred,
    )

    private data class Recording(
        val id: Int,
        val generation: Int,
        val startedAt: Double,
        val stoppedAt: Double? = null,
        val backToBack: Boolean,
        val preferred: Preferred,
        /** What happened while it ran or finalized that rules out a hold after it. */
        val pending: List<DictationHoldRelease> = emptyList(),
    )

    /** Reentrant. */
    private val lock = Any()
    private var recording: Recording? = null
    private var held: Hold? = null
    private var audioHolder: AudioSessionOwner.Holder? = null
    private var timer: Job? = null
    private var lastEnded: Double? = null
    private var nextID = 0
    private var isBackground = false
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Up front: a recording interrupted, or the app leaving, before any
        // hold must rule one out.
        environment.observe(::handle)
    }

    /** The session held now, if any. */
    val hold: Hold? get() = synchronized(lock) { held }

    /** Recording, finalizing, or holding the session: a call starting waits for [endHoldAndWait]. */
    val isInUse: Boolean get() = synchronized(lock) { recording != null || held != null }

    /** Runs [work] in order with every other change to the session. */
    private fun perform(work: () -> Unit) {
        val queue = environment.queue ?: return synchronized(lock) { work() }
        queue.execute { synchronized(lock) { work() } }
    }

    // Recording

    /**
     * Sets the session up for a recording and activates it, then picks the mic.
     * A held session is taken as it is. Returns the recording's token for
     * [recordingStopped]. Never call it on the main thread.
     */
    fun activate(): Int {
        val queue = environment.queue ?: return synchronized(lock) { activateNow() }
        return try {
            queue.submit<Int> { synchronized(lock) { activateNow() } }.get()
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun activateNow(): Int {
        val session = environment.session
        val claimed = environment.generation()
        val owner = environment.owner
        if (audioHolder == null && owner != null) {
            val log = environment.log
            audioHolder = owner.holder { deactivate(session, log) }
        }
        takeHold()?.let { hold ->
            environment.log("dictation_session_reused end=${hold.end.rawValue} held=${seconds(since = hold.startedAt)}")
        }
        // Matches what the hardware already runs for what's playing; with
        // nothing playing, `0`, so a stale call rate is never pinned.
        val otherAudio = session.isOtherAudioPlaying
        val preferred = Preferred(
            rate = if (otherAudio) session.sampleRate else 0.0,
            buffer = if (otherAudio) session.ioBufferDuration else 0.0,
        )
        val configure = {
            if (session.preferredSampleRate != preferred.rate) session.setPreferredSampleRate(preferred.rate)
            if (session.preferredIOBufferDuration != preferred.buffer) session.setPreferredIOBufferDuration(preferred.buffer)
            session.setActive(true)
        }
        try {
            val holder = audioHolder
            if (holder != null) holder.activate(configure) else configure()
        } catch (error: Exception) {
            deactivateSession(lease = claimed)
            throw error
        }
        // The input is chosen once active: what's available depends on the route.
        selectInput(session)
        val now = environment.uptime()
        nextID += 1
        recording = Recording(
            id = nextID,
            generation = environment.generation(),
            startedAt = now,
            backToBack = lastEnded?.let { now - it < DictationAudioTunables.repeatWindow } ?: false,
            preferred = preferred,
        )
        environment.log(
            "dictation_route out=${describe(session.outputPortTypes)} in=${describe(session.inputPortTypes)}" +
                " other=${if (otherAudio) 1 else 0} preferred=${format(preferred.rate, preferred.buffer)}" +
                " format=${format(session.sampleRate, session.ioBufferDuration)}",
        )
        return nextID
    }

    /** A wired or USB mic when one is plugged in, otherwise the phone's own. Bluetooth mics never: they'd need SCO. */
    private fun selectInput(session: DictationAudioSessionControlling) {
        val available = session.availableInputPortTypes
        val port = listOf(DictationPort.headsetMic, DictationPort.usbAudio).firstOrNull { it in available }
            ?: DictationPort.builtInMic
        if (port !in available || session.preferredInputPortType == port) return
        session.setPreferredInput(port)
    }

    /** The input [selectInput] picked, for the recorder. */
    fun preferredInputDevice(): AudioDeviceInfo? = environment.session.preferredInputDevice()

    /** Recording [id]'s mic stopped. The session itself stays as it is until [release]. */
    fun recordingStopped(id: Int) {
        val now = environment.uptime()
        perform {
            val current = recording ?: return@perform
            if (current.id != id || current.stoppedAt != null) return@perform
            recording = current.copy(stoppedAt = now)
        }
    }

    /**
     * A recording is over. [owner] is the session token its press claimed
     * ([AudioSessionGeneration]). While that's still current, dictation owns the
     * session: it holds it for as long as [DictationHoldPolicy] says, or releases
     * it now. Once a call or a newer press has claimed it, only what dictation
     * asked for is cleared.
     */
    fun release(end: DictationEnd, owner: Int) {
        val now = environment.uptime()
        perform { releaseNow(end, owner, now) }
    }

    private fun releaseNow(end: DictationEnd, owner: Int, now: Double) {
        val ended = recording?.takeIf { it.generation == owner }
        val otherAudio = environment.session.isOtherAudioPlaying
        val other = if (otherAudio) 1 else 0
        if (environment.generation() != owner) {
            if (ended == null) return
            recording = null
            lastEnded = now
            releaseHolder()
            resetPreferences(ended.preferred)
            environment.log("dictation_session_released reason=claimed end=${end.rawValue} held=0s other=$other")
            return
        }
        recording = null
        lastEnded = now
        if (ended == null) {
            // It never got to the mic.
            takeHold()
            deactivateSession(lease = owner)
            return
        }
        // A call is about to take the audio: dictation lets go of what it asked
        // for and leaves the rest to the call.
        if (DictationHoldRelease.Call in ended.pending) {
            releaseHolder()
            resetPreferences(ended.preferred)
            environment.log("dictation_session_released reason=call end=${end.rawValue} held=0s other=$other")
            return
        }
        // Interrupted, left in the background, or the composer moved on meanwhile.
        val stopper = if (isBackground) DictationHoldRelease.Background else ended.pending.firstOrNull { it.ends(end) }
        val recorded = (ended.stoppedAt ?: now) - ended.startedAt
        val seconds = if (stopper == null) {
            DictationHoldPolicy.seconds(end, recorded, otherAudio, ended.backToBack)
        } else {
            0.0
        }
        if (seconds <= 0) {
            deactivateSession(lease = owner)
            environment.log(
                "dictation_session_released reason=${stopper?.rawValue ?: "immediate"} end=${end.rawValue} held=0s other=$other",
            )
            return
        }
        takeHold()
        nextID += 1
        val hold = Hold(
            id = nextID, end = end, seconds = seconds, otherAudio = otherAudio,
            generation = environment.generation(), startedAt = now, preferred = ended.preferred,
        )
        held = hold
        val sleep = environment.sleep
        timer = timerScope.launch {
            sleep((seconds * 1000).roundToLong().milliseconds)
            expire(hold.id)
        }
        environment.log(
            "dictation_session_held end=${end.rawValue} hold=${format(seconds)} other=$other" +
                " recorded=${format(recorded)}${if (ended.backToBack) " repeat=1" else ""}",
        )
    }

    /** A new press takes a held session as it is. */
    fun cancelHold() {
        perform { takeHold() }
    }

    /** Ends a held session early, if [reason] applies to it. A recording under way remembers it. */
    fun endHold(reason: DictationHoldRelease) {
        perform { endHoldNow(reason) }
    }

    /** [endHold], returning once it's done: a call starts only after. */
    suspend fun endHoldAndWait(reason: DictationHoldRelease) {
        val queue = environment.queue ?: return synchronized(lock) { endHoldNow(reason) }
        suspendCancellableCoroutine { continuation ->
            queue.execute {
                synchronized(lock) { endHoldNow(reason) }
                continuation.resume(Unit)
            }
        }
    }

    private fun endHoldNow(reason: DictationHoldRelease) {
        // The app leaving counts only if it's still away at the release.
        val current = recording
        if (current != null && reason != DictationHoldRelease.Background && reason != DictationHoldRelease.Expired) {
            recording = current.copy(pending = current.pending + reason)
        }
        val hold = held ?: return
        if (!reason.ends(hold.end)) return
        takeHold()
        // Released only while still dictation's: not under a call (which bumped
        // the generation) or a recording. Otherwise only its preferences go.
        val owned = reason != DictationHoldRelease.Claimed &&
            environment.generation() == hold.generation &&
            recording == null
        if (owned) {
            deactivateSession(lease = hold.generation)
        } else {
            releaseHolder()
            resetPreferences(hold.preferred)
        }
        environment.log(
            "dictation_session_released reason=${reason.rawValue} end=${hold.end.rawValue}" +
                " held=${seconds(since = hold.startedAt)} other=${if (environment.session.isOtherAudioPlaying) 1 else 0}",
        )
    }

    /** Capture couldn't start: lets the session go at once. */
    fun deactivate() {
        perform {
            val lease = recording?.generation ?: environment.generation()
            recording = null
            takeHold()
            deactivateSession(lease)
        }
    }

    private fun expire(id: Int) {
        perform {
            if (held?.id == id) endHoldNow(DictationHoldRelease.Expired)
        }
    }

    private fun takeHold(): Hold? {
        val hold = held
        held = null
        timer?.cancel()
        timer = null
        return hold
    }

    /**
     * Releases first, then clears what dictation preferred. Shared activation
     * remains while a call or media player still holds it.
     */
    private fun deactivateSession(lease: Int) {
        val session = environment.session
        if (environment.owner != null) {
            if (!releaseHolder()) return
            // A newer press/call owns the preferences, independently of activation.
            if (environment.generation() != lease) return
        } else {
            try {
                deactivate(session, environment.log)
            } catch (_: Exception) {
                return
            }
        }
        if (session.preferredSampleRate != 0.0) session.setPreferredSampleRate(0.0)
        if (session.preferredIOBufferDuration != 0.0) session.setPreferredIOBufferDuration(0.0)
    }

    private fun releaseHolder(): Boolean {
        val last = audioHolder?.release() ?: false
        audioHolder = null
        return last
    }

    /** Clears the sample rate and buffer dictation asked for while they're still what it asked. */
    private fun resetPreferences(preferred: Preferred) {
        val session = environment.session
        if (preferred.rate != 0.0 && session.preferredSampleRate == preferred.rate) session.setPreferredSampleRate(0.0)
        if (preferred.buffer != 0.0 && session.preferredIOBufferDuration == preferred.buffer) session.setPreferredIOBufferDuration(0.0)
    }

    // What ends a hold from outside

    /**
     * Leaving the app, another app starting to play, an interruption, and anyone
     * else claiming the session each end a hold at once, and rule one out after a
     * recording still under way.
     */
    fun handle(event: DictationAudioEvent) {
        when (event) {
            DictationAudioEvent.DidEnterBackground -> perform {
                isBackground = true
                endHoldNow(DictationHoldRelease.Background)
            }
            DictationAudioEvent.WillEnterForeground -> perform { isBackground = false }
            DictationAudioEvent.OtherAudioBegan -> endHold(DictationHoldRelease.Silenced)
            DictationAudioEvent.InterruptionBegan -> endHold(DictationHoldRelease.Interrupted)
            DictationAudioEvent.MediaServicesReset -> perform { resetNow() }
            // A route change: a call taking the audio has bumped the generation by then.
            DictationAudioEvent.RouteChanged -> checkOwner()
        }
    }

    /**
     * The audio server restarted: forgets the hold and the holder, and rules out
     * a hold after a recording under way, so the next press sets everything up again.
     */
    private fun resetNow() {
        recording?.let { recording = it.copy(pending = it.pending + DictationHoldRelease.Interrupted) }
        val hold = takeHold()
        releaseHolder()
        environment.log("dictation_session_reset held=${if (hold == null) 0 else 1} recording=${if (recording == null) 0 else 1}")
    }

    /** Forgets a hold whose session someone else has claimed, clearing only what dictation asked for. */
    fun checkOwner() {
        perform {
            val hold = held ?: return@perform
            if (environment.generation() != hold.generation) endHoldNow(DictationHoldRelease.Claimed)
        }
    }

    // Log formatting

    private fun seconds(since: Double): String = format(environment.uptime() - since)

    companion object Key : DependencyKey<DictationAudio> {
        /** Dictation's use of the shared audio; one per process. */
        override val liveValue: DictationAudio by lazy { DictationAudio(Environment.live(dependency(AppContext))) }
        override val testValue: DictationAudio get() = DictationAudio(Environment.inert())

        private fun deactivate(session: DictationAudioSessionControlling, log: (String) -> Unit) {
            try {
                session.setActive(false)
            } catch (error: Exception) {
                AppLog.voice.error("Couldn’t release dictation audio (${error.javaClass.simpleName})")
                log("audio_session_deactivate_failed source=dictation code=${error.javaClass.simpleName}")
                throw error
            }
        }

        /** `1.5s`, `10s`: up to one decimal, no grouping. */
        internal fun format(seconds: Double): String {
            val rounded = (seconds * 10).roundToInt() / 10.0
            val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString() else String.format(Locale.ROOT, "%.1f", rounded)
            return "${text}s"
        }

        /** `48000Hz/23ms`. */
        internal fun format(rate: Double, buffer: Double): String = "${rate.toInt()}Hz/${(buffer * 1000).roundToInt()}ms"

        private fun describe(ports: List<String>): String = if (ports.isEmpty()) "none" else ports.joinToString("+")
    }
}

/** Keeps no state and touches no hardware. */
private class InertDictationAudioSession : DictationAudioSessionControlling {
    override val isOtherAudioPlaying = false
    override val sampleRate = 0.0
    override val ioBufferDuration = 0.0
    private var rate = 0.0
    private var buffer = 0.0
    private var active = false
    override val preferredSampleRate: Double get() = rate
    override val preferredIOBufferDuration: Double get() = buffer
    override var preferredInputPortType: String? = null
    override val availableInputPortTypes = listOf(DictationPort.builtInMic)
    override val outputPortTypes = emptyList<String>()
    override val inputPortTypes = emptyList<String>()
    override val isActive: Boolean get() = active
    override fun setPreferredSampleRate(sampleRate: Double) { rate = sampleRate }
    override fun setPreferredIOBufferDuration(duration: Double) { buffer = duration }
    override fun setPreferredInput(portType: String) { preferredInputPortType = portType }
    override fun setActive(active: Boolean) { this.active = active }
    override fun preferredInputDevice(): AudioDeviceInfo? = null
}

/** The live sources of [DictationAudioEvent]s. */
private object LiveDictationAudioEvents {
    private val callModes = setOf(
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_CALL_SCREENING,
        AudioManager.MODE_CALL_REDIRECT,
        AudioManager.MODE_COMMUNICATION_REDIRECT,
    )

    fun isCallMode(mode: Int): Boolean = mode in callModes

    fun observe(context: Context, handler: (DictationAudioEvent) -> Unit) {
        val application = context.applicationContext as? Application
        val audioManager = context.getSystemService(AudioManager::class.java)
        val main = Handler(Looper.getMainLooper())
        // Background and foreground: the app's started activities.
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private val started = HashSet<Int>()
            override fun onActivityStarted(activity: Activity) {
                started += System.identityHashCode(activity)
                if (started.size == 1) handler(DictationAudioEvent.WillEnterForeground)
            }

            override fun onActivityStopped(activity: Activity) {
                started -= System.identityHashCode(activity)
                if (started.isEmpty() && !activity.isChangingConfigurations) handler(DictationAudioEvent.DidEnterBackground)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Another app starting to play.
        var playing = audioManager.isMusicActive
        audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                val now = audioManager.isMusicActive
                if (now && !playing) handler(DictationAudioEvent.OtherAudioBegan)
                playing = now
            }
        }, main)
        // A call taking the audio.
        audioManager.addOnModeChangedListener(Executor(main::post)) { mode ->
            if (isCallMode(mode)) handler(DictationAudioEvent.InterruptionBegan)
        }
        // Devices coming and going.
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = handler(DictationAudioEvent.RouteChanged)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = handler(DictationAudioEvent.RouteChanged)
        }, main)
    }
}

/**
 * The microphone: the shared audio set up for dictation, an `AudioRecord` read
 * loop feeding [DictationInput], and the observers that end it from outside.
 */
object LiveCapture {
    /** The rates tried in order: the recognizer's own first, so nothing needs converting. */
    private val sampleRates = intArrayOf(16_000, 48_000, 44_100)

    /** Small buffers: the scope moves (and the first words are captured) as soon as the mic delivers. */
    private const val BUFFER_FRAMES = 1024

    @SuppressLint("MissingPermission") // Checked by the engine before every capture.
    fun start(
        context: Context,
        audio: DictationAudio,
        input: DictationInput,
        onEvent: (DictationCaptureEvent) -> Unit,
    ): DictationCapture {
        val recording = audio.activate()
        DictationLatency.mark("session_active")

        val rate = sampleRates.firstOrNull {
            AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
        }
        // No input at all (an emulator without a microphone, a route with none).
        if (rate == null) {
            audio.deactivate()
            throw DictationError.AudioFormatUnavailable()
        }
        val format = DictationAudioFormat(sampleRate = rate, channelCount = 1)
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, BUFFER_FRAMES * 2 * 4))
                .build()
        } catch (error: Exception) {
            audio.deactivate()
            throw if (error is SecurityException) DictationError.MicrophoneDenied() else DictationError.AudioFormatUnavailable()
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            audio.deactivate()
            throw DictationError.AudioFormatUnavailable()
        }
        audio.preferredInputDevice()?.let(record::setPreferredDevice)
        try {
            record.startRecording()
        } catch (error: Exception) {
            record.release()
            audio.deactivate()
            throw DictationError.AudioFormatUnavailable()
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audio.deactivate()
            throw DictationError.AudioFormatUnavailable()
        }
        DictationAudioSession.isRecording = true
        // The mic keeps going if the app leaves mid-recording.
        val appContext = context.applicationContext
        DictationRecordingService.start(appContext)

        val audioManager = context.getSystemService(AudioManager::class.java)
        val main = Handler(Looper.getMainLooper())
        // A call or another recorder taking the mic, and route changes (a headset plugged in)
        // mid-recording, end it.
        var routed: Int? = null
        val routing = AudioRecord.OnRoutingChangedListener { router ->
            val id = router.routedDevice?.id
            val previous = routed
            routed = id
            if (previous != null && id != previous) onEvent(DictationCaptureEvent.Interrupted)
        }
        record.addOnRoutingChangedListener(routing, main)
        val modes = AudioManager.OnModeChangedListener { mode ->
            if (LiveDictationAudioEvents.isCallMode(mode)) onEvent(DictationCaptureEvent.Interrupted)
        }
        audioManager.addOnModeChangedListener(Executor(main::post), modes)
        val recordings = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                if (mine.isClientSilenced) onEvent(DictationCaptureEvent.Interrupted)
            }
        }
        audioManager.registerAudioRecordingCallback(recordings, main)

        val running = Running(
            record = record,
            onStop = {
                DictationRecordingService.stop(appContext)
                audio.recordingStopped(recording)
            },
            removeObservers = {
                record.removeOnRoutingChangedListener(routing)
                audioManager.removeOnModeChangedListener(modes)
                audioManager.unregisterAudioRecordingCallback(recordings)
            },
        )
        running.startReading(BUFFER_FRAMES, format, input) {
            // The audio server died: this recorder will never deliver again.
            audio.handle(DictationAudioEvent.MediaServicesReset)
            onEvent(DictationCaptureEvent.Interrupted)
        }
        return DictationCapture(format = format, stop = running::stop)
    }

    /** The recorder, its read loop and observers, stopped once. */
    class Running(
        private val record: AudioRecord,
        private val onStop: () -> Unit,
        private val removeObservers: () -> Unit,
    ) {
        private val lock = Any()
        private var stopped = false
        private var thread: Thread? = null

        fun startReading(frames: Int, format: DictationAudioFormat, input: DictationInput, onDead: () -> Unit) {
            thread = Thread({
                val buffer = ShortArray(frames * format.channelCount)
                var firstAudio = true
                while (!synchronized(lock) { stopped }) {
                    val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val samples = buffer.copyOf(read)
                        DictationLevels.update(samples)
                        input.append(DictationBuffer(samples, format))
                        if (firstAudio) {
                            firstAudio = false
                            DictationLatency.mark("first_audio")
                        }
                    } else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        if (!synchronized(lock) { stopped }) onDead()
                        break
                    } else if (read < 0) {
                        break
                    }
                }
            }, "ai.opencode.dictation-capture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        fun stop() {
            synchronized(lock) {
                if (stopped) return
                stopped = true
            }
            removeObservers()
            runCatching { record.stop() }
            thread?.join(500)
            record.release()
            DictationAudioSession.isRecording = false
            onStop()
            DictationLevels.set(0f)
        }
    }
}
