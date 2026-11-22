package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.*
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.UUID
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.withLock

object VoiceAudioRoute {
    val externalOutputPorts = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HEARING_AID)
    fun isExternalOutput(port: Int) = port in externalOutputPorts
    fun hasExternalOutput(outputs: List<Int>) = outputs.any(::isExternalOutput)
    enum class Override { none, speaker }
    fun desiredOverride(atEar: Boolean, hasExternalOutput: Boolean, demo: Boolean = false) =
        if (demo || hasExternalOutput || atEar) Override.none else Override.speaker
}

/**
 * The platform seam for deterministic ownership/route/focus checks. Permission is asked here,
 * through the requester:app registers on [VoiceMicrophonePermission].
 */
interface VoiceAudioSessionControlling {
    val mode: Int
    val outputPorts: List<Int>
    val isInputAvailable: Boolean
    val outputLatency: Double get() = 0.0
    val ioBufferDuration: Double get() = 0.01
    val routeDescription: String
    suspend fun requestRecordPermission(): Boolean
    fun activate(demo: Boolean)
    fun deactivate()
    fun route(atEar: Boolean, demo: Boolean)
    fun observe(id: UUID, focus: (Int) -> Unit, route: () -> Unit)
    fun unobserve(id: UUID)
}

/** One Android focus request for all holders: an older call's last-release closure
 * must release the CURRENT request, not its own obsolete request. */
class LiveVoiceAudioSession(private val context: Context) : VoiceAudioSessionControlling {
    val manager = context.getSystemService(AudioManager::class.java)
    private val listeners = mutableMapOf<UUID, Pair<(Int) -> Unit, () -> Unit>>()
    private var request: AudioFocusRequest? = null
    private var savedMode: Int? = null
    private var registered = false
    private var observedMode: Int? = null
    private val deviceIDs = mutableSetOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val modeListener = AudioManager.OnModeChangedListener {
        val changed = synchronized(this) {
            val current = manager.mode
            (registered && current != observedMode).also { observedMode = current }
        }
        if (changed) routeChanged()
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val changed = synchronized(this@LiveVoiceAudioSession) {
                addedDevices.map { deviceIDs.add(it.id) }.any { it }
            }
            if (changed) routeChanged()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val changed = synchronized(this@LiveVoiceAudioSession) {
                removedDevices.map { deviceIDs.remove(it.id) }.any { it }
            }
            if (changed) routeChanged()
        }
    }
    private fun routeChanged() { synchronized(this) { listeners.values.map { it.second } }.forEach { it() } }
    override val mode get() = manager.mode
    override val outputPorts get() = listOfNotNull(manager.communicationDevice?.type)
    override val isInputAvailable get() = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).isNotEmpty()
    override val routeDescription get() = VoiceAudioQuality.route(manager)
    override suspend fun requestRecordPermission(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        return dependency(VoiceMicrophonePermission).request()
    }
    @Synchronized override fun activate(demo: Boolean) {
        if (savedMode == null) {
            val current = manager.mode
            savedMode = if (current == AudioManager.MODE_NORMAL || current == AudioManager.MODE_IN_COMMUNICATION) current else AudioManager.MODE_NORMAL
        }
        val old = request
        if (old != null) runCatching { manager.abandonAudioFocusRequest(old) }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes(demo)).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> synchronized(this) { listeners.values.map { it.first } }.forEach { it(change) } }, handler).build()
        request = focus // Retain teardown responsibility on a refused request too.
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw VoiceAudioError.engineRejected("Activating call audio", "audio focus denied")
        val mode = if (demo) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
        observedMode = mode // Ignore our own category write, including its async callback.
        runCatching { manager.mode = mode }.onFailure { error ->
            throw VoiceAudioError.engineRejected("Activating call audio", error.message ?: error.javaClass.simpleName)
        }
    }
    @Synchronized override fun deactivate() {
        runCatching { manager.clearCommunicationDevice() }
        val restore = savedMode; savedMode = null
        if (restore != null) {
            observedMode = restore
            val current = runCatching { manager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
            if (current == AudioManager.MODE_NORMAL || current == AudioManager.MODE_IN_COMMUNICATION) {
                runCatching { manager.mode = restore }
            }
        }
        val activeRequest = request; request = null
        if (activeRequest != null) runCatching { manager.abandonAudioFocusRequest(activeRequest) }
    }
    override fun route(atEar: Boolean, demo: Boolean) {
        if (demo) { runCatching { manager.clearCommunicationDevice() }; return }
        val available = runCatching { manager.availableCommunicationDevices }.getOrElse { emptyList() }
        val current = runCatching { manager.communicationDevice }.getOrNull()
        val builtin = available.firstOrNull {
            it.type == if (atEar) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val external = current?.takeIf { cur -> VoiceAudioRoute.isExternalOutput(cur.type) && available.any { it.id == cur.id } }
            ?: available.firstOrNull { VoiceAudioRoute.isExternalOutput(it.type) }
        val desired = external ?: builtin
        if (desired != null) {
            if (current?.id != desired.id) {
                val applied = runCatching { manager.setCommunicationDevice(desired) }.getOrElse { error ->
                    AppLog.voice.notice("Couldn’t select communication device (${error.javaClass.simpleName})")
                    false
                }
                if (!applied && external != null && builtin != null && current?.id != builtin.id) {
                    runCatching { manager.setCommunicationDevice(builtin) }
                }
            }
        } else {
            runCatching { manager.clearCommunicationDevice() }
        }
    }
    @Synchronized override fun observe(id: UUID, focus: (Int) -> Unit, route: () -> Unit) {
        listeners[id] = focus to route
        if (!registered) {
            observedMode = manager.mode
            deviceIDs.clear()
            // registerAudioDeviceCallback immediately reports every existing device.
            deviceIDs.addAll(manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).map { it.id })
            manager.registerAudioDeviceCallback(devices, handler)
            manager.addOnModeChangedListener(context.mainExecutor, modeListener)
            // A selected communication device is an output override. Our route() writes it too.
            registered = true
        }
    }
    @Synchronized override fun unobserve(id: UUID) {
        listeners.remove(id)
        if (listeners.isEmpty() && registered) {
            manager.unregisterAudioDeviceCallback(devices)
            manager.removeOnModeChangedListener(modeListener)
            registered = false
            deviceIDs.clear()
        }
    }
    companion object Key : DependencyKey<VoiceAudioSessionControlling> {
        override val liveValue by lazy { LiveVoiceAudioSession(dependency(AppContext)) }
        override val testValue get() = error("Override VoiceAudioSessionControlling for audio engine tests")
        fun attributes(demo: Boolean) = AudioAttributes.Builder()
            .setUsage(if (demo) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }
}

/** WebRTC ADM owns the hardware in external mode. It MUST honor these controls
 * synchronously/off the main thread and stop its hardware before returning stop.
 * Samples supplied to feedCapture/recordExternalPlayback are borrowed until return;
 * only completed 100-ms capture chunks are copied into the bounded capture stream. */
interface VoiceExternalAudioControl {
    fun setMuted(muted: Boolean)
    fun setPlaybackVolume(volume: Float)
    fun flush()
    fun interrupted(interrupted: Boolean)
    fun stop()
}

/** Debug lab supplies synthetic input/preroll without depending on a future lab type. */
interface VoiceAudioLabHooks {
    val isScriptedDemo: Boolean get() = false
    val playbackPreroll: Long? get() = null
    val input: ByteArray? get() = null
    val inputDelay: Double get() = 0.0
    companion object Key : DependencyKey<VoiceAudioLabHooks?> {
        override val liveValue: VoiceAudioLabHooks? = null
        override val testValue: VoiceAudioLabHooks? = null
    }
}

class VoiceAudioEngine(
    atEar: Boolean = false,
    private val demo: Boolean = VoiceAudioDemoMode.isEnabled,
    private val clock: Clock = dependency(Clock),
    private val session: VoiceAudioSessionControlling = dependency(LiveVoiceAudioSession),
    owner: AudioSessionOwner = dependency(AudioSessionOwner),
    private val claimSession: () -> Int = AudioSessionGeneration::bump,
    private val note: (VoiceCallLog.Note.Kind, String) -> Unit = dependency(VoiceCallLogClient)::noteCurrent,
    externalOwner: Boolean = false,
    externalControl: VoiceExternalAudioControl? = null,
    private val lab: VoiceAudioLabHooks? = dependency(VoiceAudioLabHooks) ?: VoiceLabDemo.audioHooks,
) {
    @Volatile var externalOwner = externalOwner; private set
    @Volatile private var externalControl = externalControl
    private val id = dependency(UuidClient).next()
    private val holder = owner.holder(session::deactivate)
    /** Audio in Calls: the music player, attached only while that experiment is on. */
    @Volatile private var callAudio: CallAudioPlayer? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            if (error !is CancellationException) {
                AppLog.voice.error("Call audio background task failed (${error.javaClass.simpleName})")
            }
        },
    )
    private val control = kotlinx.coroutines.sync.Mutex()
    private val lock = Any()
    private val trackLock = Any()
    private val levels = VoiceAudioLevels.shared
    private val sounds = VoiceCallSounds.shared
    private val quality = VoiceAudioQuality(clock)
    private val gate = CaptureGate()
    private val capture = Channel<ByteArray>(50)
    private val events = Channel<VoiceAudioEvent>(Channel.UNLIMITED)
    private val forwarder = CaptureForwarder(gate, capture, quality, levels)
    private val playback = PlayedAudioTracker()
    private val scheduler = PlaybackScheduler(lab?.playbackPreroll ?: PlaybackScheduler.initialPreroll)
    private val renderedGaps = RenderedGapMeter()
    private var scheduledBuffers = 0
    private var underruns = 0
    private data class Buffer(val samples: FloatArray, val start: Long, val end: Long)
    private val queue = ArrayDeque<Buffer>()
    private var queueEnd = 0L
    private var writtenFrames = 0L
    private var playedFrames = 0L
    private var externalFrames = 0L
    @Volatile private var playbackEpoch = 0
    private var generation = 0
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val effects = mutableListOf<AudioEffect>()
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var qualityTask: Job? = null
    private var labTask: Job? = null
    private var rampTask: Job? = null
    private val cueJobs = mutableSetOf<Job>()
    /** Cues alternate between two players (as Wisp's do), so one never waits for or cuts off another. */
    private val lastCues = arrayOfNulls<Job>(2)
    private var nextCuePlayer = 0
    private val cueTracks = mutableSetOf<AudioTrack>()
    /** The connecting loop's own track, so letting it go never cuts a cue. */
    private var loopTrack: AudioTrack? = null
    /** The connecting loop while it should play, to resume after an interruption. */
    @Volatile private var loopSamples: FloatArray? = null
    private var cueCommands: Channel<CueCommand>? = null
    private var cueTask: Job? = null

    private sealed interface CueCommand {
        class Play(val raw: FloatArray) : CueCommand
        /** Dropped while the agent speaks. */
        class Tool(val raw: FloatArray) : CueCommand
        /** Starts the loop, or with null lets it go. */
        class Loop(val raw: FloatArray?) : CueCommand
    }
    private var muteOutput = false
    private var telemetry = false
    @Volatile var atEar = atEar; private set
    @Volatile var running = false; private set
    @Volatile private var alive = false
    /** Audio was taken and hasn't come back. */
    @Volatile private var interrupted = false
    /** Between an interruption's start and its end. */
    @Volatile private var interruptionInProgress = false
    private var didConnect = false
    private var mediaReset = false
    private var stopped = false
    @Volatile private var activationCancelled = false
    var rampGeneration = 0; private set
    @Volatile var playbackVolume = 1f; private set

    suspend fun start(): VoiceAudioSession {
        try { return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            generation = claimSession()
            sounds.pendingStart {
                if (!session.requestRecordPermission()) throw VoiceAudioError.microphoneDenied
                currentCoroutineContext().ensureActive()
                session.observe(id, { change -> scope.launch { handleInterruption(change != AudioManager.AUDIOFOCUS_GAIN) } },
                    { scope.launch { handleConfigurationChange() } })
                holder.activate { session.activate(demo); session.route(atEar, demo) }
                if (!session.isInputAvailable) {
                    note(VoiceCallLog.Note.Kind.audio, "⚠︎ Audio hardware not ready (no audio input available); retrying · ${session.routeDescription}")
                    clock.sleep(300.milliseconds)
                    if (!session.isInputAvailable) throw VoiceAudioError.formatUnavailable
                }
                currentCoroutineContext().ensureActive()
                levels.reset()
                muteOutput = VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_MUTE_OUTPUT") != null
                telemetry = sounds.isTelemetryEnabled
                if (!externalOwner) buildHardware()
                else requireNotNull(externalControl) { "external owner requires VoiceExternalAudioControl" }
                running = true; alive = true
                holder.withActive { if (!externalOwner) startHardwareThreads() }
                currentCoroutineContext().ensureActive()
                startQualityReports()
                // Audio in Calls: an agent's track plays on this call's route until it stops.
                callAudio = CallAudioPlayer.shared.also { it.attach(id, demo) }
            }
            // One ordered queue, so the loop has let go before the connect cue plays.
            val commands = Channel<CueCommand>(Channel.UNLIMITED)
            cueCommands?.close(); cueCommands = commands
            cueTask?.cancel(); cueTask = scope.launch { for (command in commands) perform(command) }
            sounds.startCallClock(
                id, session.outputLatency + session.ioBufferDuration,
                reportFirstCue = { first -> AppLog.voice.notice(first.logLine); note(VoiceCallLog.Note.Kind.audio, first.logLine) },
                loop = { commands.trySend(CueCommand.Loop(it)) },
                playTool = { commands.trySend(CueCommand.Tool(it)) },
            ) { commands.trySend(CueCommand.Play(it)) }
            VoiceAudioSession(capture.receiveAsFlow(), events.receiveAsFlow(), ::enqueue, ::flush, ::setMuted, ::stop,
                ::setAtEar, ::noteConnected, ::cancelAudioSession, quality, this@VoiceAudioEngine)
        }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                running = false; alive = false; callAudio?.detach(id); releaseHardware(); session.unobserve(id)
                sounds.endCallClock(id, false); sounds.cancelPendingStart()
                holder.release(); capture.close(); events.close(); scope.cancel()
            }
            throw error
        }
    }
    @SuppressLint("MissingPermission") // Permission checked before this on every start.
    private fun buildHardware() {
        val inputSize = AudioRecord.getMinBufferSize(24000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outputSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (inputSize <= 0 || outputSize <= 0) throw VoiceAudioError.formatUnavailable
        record = AudioRecord.Builder().setAudioSource(if (demo) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(PCM.transportFormat).setBufferSizeInBytes(max(inputSize, PCM.chunkFrames * 4)).build()
        track = AudioTrack.Builder().setAudioAttributes(LiveVoiceAudioSession.attributes(demo)).setAudioFormat(PCM.playbackFormat)
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(max(outputSize, 480 * 4)).build()
        if (record?.state != AudioRecord.STATE_INITIALIZED || track?.state != AudioTrack.STATE_INITIALIZED) throw VoiceAudioError.formatUnavailable
        if (!demo) {
            val audioID = record!!.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioID)?.let { it.enabled = true; effects.add(it) }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioID)?.let { it.enabled = true; effects.add(it) }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioID)?.let { it.enabled = true; effects.add(it) }
        } else note(VoiceCallLog.Note.Kind.audio, "Demo Mode: voice plays as media, without echo cancellation")
        if (telemetry) renderedGaps.enable()
    }
    private fun startHardwareThreads() {
        val input = record ?: return; val output = track ?: return
        if (!interrupted) { input.startRecording(); output.play() }
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val samples = ShortArray(1200)
            try { while (alive) {
                if (interrupted) { Thread.sleep(10); continue }
                val n = input.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (n > 0) { if (!interrupted && lab?.input == null) forwarder.receive(samples, n, 24000, 1) }
                else if (alive && !interrupted && n < 0) { hardwareFailed(n); break }
            } } catch (_: Exception) { if (alive && !interrupted) hardwareFailed(AudioRecord.ERROR_DEAD_OBJECT) }
        }, "voice-capture").apply { start() }
        playbackThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try { renderLoop(output) } catch (_: Exception) { if (alive && !interrupted) hardwareFailed(AudioTrack.ERROR_DEAD_OBJECT) }
        }, "voice-playback").apply { start() }
    }
    private fun renderLoop(output: AudioTrack) {
        // Continuous silence keeps a sample timeline during network gaps. Fixed
        // render buffers, no per-buffer PCM allocation on this thread.
        val samples = FloatArray(240); val voice = FloatArray(240)
        val meterRing = Array(32) { FloatArray(240) }; val meterEnds = LongArray(32)
        var meterWrite = 0; var meterRead = 0; var lastHead = 0L; var wraps = 0L
        var seenEpoch = playbackEpoch
        while (alive) {
            if (interrupted) { Thread.sleep(10); continue }
            samples.fill(0f); voice.fill(0f)
            val epoch: Int
            synchronized(lock) {
                if (seenEpoch != playbackEpoch) {
                    seenEpoch = playbackEpoch; lastHead = 0; wraps = 0; meterRead = 0; meterWrite = 0
                }
                val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                if (head < lastHead) wraps += 1L shl 32
                lastHead = head; playedFrames = wraps + head
                while (meterRead != meterWrite && meterEnds[meterRead] <= playedFrames) {
                    levels.updateAssistant(meterRing[meterRead]); renderedGaps.add(meterRing[meterRead]); meterRead = (meterRead + 1) % meterRing.size
                }
                var drained = false
                while (queue.isNotEmpty() && queue.first().end <= playedFrames) { queue.removeFirst(); drained = queue.isEmpty() }
                if (drained) { playback.reset(); levels.clearAssistant(); events.trySend(VoiceAudioEvent.playbackDrained) }
                for (buffer in queue) {
                    val start = max(writtenFrames, buffer.start); val end = min(writtenFrames + samples.size, buffer.end)
                    if (end <= start) continue
                    buffer.samples.copyInto(voice, (start - writtenFrames).toInt(), (start - buffer.start).toInt(), (end - buffer.start).toInt())
                }
                for (i in samples.indices) {
                    samples[i] = (voice[i] * playbackVolume).coerceIn(-1f, 1f)
                }
                epoch = playbackEpoch
            }
            if (muteOutput) samples.fill(0f)
            var offset = 0
            while (offset < samples.size && alive && !interrupted) {
                val n = synchronized(trackLock) {
                    if (epoch != playbackEpoch) 0 else output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                }
                if (n < 0) { if (alive) hardwareFailed(n); return }
                if (n == 0) break
                offset += n
            }
            synchronized(lock) {
                if (epoch != playbackEpoch) { seenEpoch = playbackEpoch; lastHead = 0; wraps = 0; meterRead = 0; meterWrite = 0 }
                else {
                    writtenFrames += offset
                    voice.copyInto(meterRing[meterWrite]); meterEnds[meterWrite] = writtenFrames
                    meterWrite = (meterWrite + 1) % meterRing.size
                    if (meterWrite == meterRead) meterRead = (meterRead + 1) % meterRing.size
                }
            }
        }
    }
    private fun hardwareFailed(code: Int) {
        AppLog.voice.error("Call audio hardware failed ($code)")
        if (code == AudioRecord.ERROR_DEAD_OBJECT) scope.launch { handleMediaReset() }
        else scope.launch { handleInterruption(true) }
    }
    private fun startQualityReports() {
        note(VoiceCallLog.Note.Kind.audio, (if (atEar && !demo && !VoiceAudioRoute.hasExternalOutput(session.outputPorts)) "At the ear: earpiece · " else "") + session.routeDescription)
        quality.setPreroll(scheduler.preroll / PCM.sampleRate * 1000)
        qualityTask = scope.launch {
            while (isActive) {
                clock.sleep(VoiceAudioQuality.window); reportQuality()
                levels.takeEchoWindow()?.let { note(VoiceCallLog.Note.Kind.audio, it) }
                callAudio?.takeLeakWindow()?.let { note(VoiceCallLog.Note.Kind.audio, it) }
            }
        }
    }
    private fun reportQuality() { quality.takeWindow()?.let { note(VoiceCallLog.Note.Kind.audio, (if (it.problem) "⚠︎ " else "") + it.text) } }
    private fun playCue(samples: FloatArray) {
        if (!running || interrupted || samples.isEmpty()) return
        // A cue must outlive a prewarm/RTC/WS hardware handoff. Its short-lived
        // AudioTrack never owns a microphone and is stopped only with this call.
        val job = synchronized(lock) {
            val player = nextCuePlayer
            nextCuePlayer = (nextCuePlayer + 1) % lastCues.size
            val previous = lastCues[player]
            scope.launch(start = CoroutineStart.LAZY) {
                previous?.join() // Each AVAudioPlayerNode schedules its cues in FIFO order.
                playStandaloneCue(samples)
            }.also { cueJobs.add(it); lastCues[player] = it }
        }
        job.invokeOnCompletion { synchronized(lock) { cueJobs.remove(job) } }
        job.start()
    }
    /**
     * A tool cue never plays over the agent's speech: while any is queued it's dropped. With
     * WebRTC's ADM owning playback there's no queue here, so the agent's audible level stands in.
     */
    private fun playToolCue(samples: FloatArray) {
        val speaking = synchronized(lock) { queue.isNotEmpty() } || (externalOwner && levels.snapshot().assistant >= VoiceEchoWindow.quiet)
        if (speaking || levels.isMuted) return
        playCue(samples)
    }
    private suspend fun perform(command: CueCommand) {
        when (command) {
            is CueCommand.Play -> playCue(command.raw)
            is CueCommand.Tool -> playToolCue(command.raw)
            is CueCommand.Loop -> command.raw?.let { startLoop(it) } ?: releaseLoop()
        }
    }
    /** Loops [raw] seamlessly: it's exactly one period, zero at both ends. */
    private fun startLoop(raw: FloatArray) {
        loopSamples = raw
        stopLoopTrack()
        if (!running || interrupted || raw.isEmpty()) return
        runCatching {
            val output = AudioTrack.Builder().setAudioAttributes(LiveVoiceAudioSession.attributes(demo)).setAudioFormat(PCM.playbackFormat)
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(raw.size * 4).build()
            output.write(raw, 0, raw.size, AudioTrack.WRITE_BLOCKING)
            output.setLoopPoints(0, raw.size, -1)
            synchronized(lock) {
                loopTrack = output
                output.setVolume(if (muteOutput) 0f else playbackVolume)
            }
            holder.withActive { output.play() }
        }.onFailure { if (it !is CancellationException) AppLog.voice.error("Couldn’t play the connecting loop (${it.javaClass.simpleName})") }
    }
    /** Lets the loop go over [VoiceCallSounds.loopRelease] (Wisp's 30 ms) so it doesn't click, before the next cue plays. */
    private suspend fun releaseLoop() {
        loopSamples = null
        val output = synchronized(lock) { loopTrack } ?: return
        if (output.playState != AudioTrack.PLAYSTATE_PLAYING) { stopLoopTrack(); return }
        val steps = 6
        for (step in 1..steps) {
            delay((VoiceCallSounds.loopRelease / steps).seconds)
            if (loopSamples != null) return
            synchronized(lock) { if (loopTrack === output) output.setVolume((1 - step.toFloat() / steps) * (if (muteOutput) 0f else playbackVolume)) }
        }
        stopLoopTrack()
    }
    private fun stopLoopTrack() {
        val output = synchronized(lock) { loopTrack.also { loopTrack = null } } ?: return
        runCatching { output.stop() }; output.release()
    }
    private fun stopCues() {
        cueCommands?.close(); cueCommands = null
        cueTask?.cancel(); cueTask = null
        loopSamples = null
        stopLoopTrack()
    }
    /** Move the same call engine between AudioRecord/Track and WebRTC's ADM.
     * Stop the previous hardware synchronously before the caller activates the
     * next ADM. Keep the session, gate, mute, lab cursor, cues and consumers alive. */
    internal suspend fun useExternalOwner(next: VoiceExternalAudioControl?) = withContext(Dispatchers.IO + NonCancellable) {
        control.withLock {
            check(running && !stopped) { "The voice connection is closed." }
            if (externalOwner == (next != null)) return@withLock
            alive = false
            if (externalOwner) externalControl?.stop() else releaseHardware()
            while (capture.tryReceive().isSuccess) { /* No old-transport telemetry on the new uplink. */ }
            forwarder.reset()
            synchronized(lock) {
                resetPlaybackQueue(); externalFrames = 0
                levels.clearAssistant()
                externalControl = next; externalOwner = next != null
            }
            if (!externalOwner) buildHardware()
            alive = true
            if (!externalOwner) {
                if (interrupted) startHardwareThreads() else holder.withActive { startHardwareThreads() }
            }
            next?.setMuted(levels.isMuted)
            next?.setPlaybackVolume(playbackVolume)
            next?.interrupted(interrupted)
        }
    }
    suspend fun enqueue(pcm: ByteArray, itemID: String?) = withContext(Dispatchers.IO) {
        val samples = PCM.playbackBuffer(pcm) ?: return@withContext
        VoiceLabEnvironment.string("OPENCODE_LAB_VOICE_OUTPUT_DUMP")?.let { path ->
            runCatching { File(path).appendBytes(pcm) }
        }
        synchronized(lock) {
            if (!running || interrupted || externalOwner) return@synchronized
            // AVAudioPlayerNode's sample clock is the render timeline, not the
            // hardware head. Never schedule into samples already sent to AudioTrack.
            val now = writtenFrames
            val decision = scheduler.schedule(samples.size.toLong(), itemID, PCM.hasSpeechEnergy(pcm), now)
            quality.assistantArrived(samples.size / PCM.sampleRate * 1000, queue.sumOf { (it.end - max(now, it.start)).coerceAtLeast(0) / PCM.sampleRate * 1000 })
            val start = when (val placement = decision.placement) {
                PlaybackScheduler.Placement.queued -> queueEnd
                is PlaybackScheduler.Placement.start -> placement.atFrame
            }
            queueEnd = start + samples.size
            queue.addLast(Buffer(samples, start, queueEnd)); playback.enqueue(itemID, samples.size.toLong(), start)
            scheduledBuffers++
            decision.underrunFrames?.let { underruns++; quality.assistantStarved(it / PCM.sampleRate * 1000, scheduler.preroll / PCM.sampleRate * 1000) }
        }
    }
    /** External ADM microphone callback. May be 48-kHz stereo; converted and batched
     * to 24-kHz mono. Hardware mute MUST also be honored by externalControl. */
    fun feedCapture(samples: ShortArray, count: Int = samples.size, sampleRate: Int = 24000, channels: Int = 1) {
        if (running && !interrupted && lab?.input == null) forwarder.receive(samples, count, sampleRate, channels)
    }
    /** Remote sink callback AFTER playout, never network arrival. Float mono samples
     * are normalized -1..1 at 24kHz; a transport converting another rate does so before
     * this call. Call externalPlaybackDrained when WebRTC's speech hangover ends. */
    fun recordExternalPlayback(samples: FloatArray, itemID: String?, count: Int = samples.size) = synchronized(lock) {
        if (!running || interrupted || !externalOwner) return@synchronized
        playback.enqueue(itemID, count.toLong(), externalFrames)
        externalFrames += count; levels.updateAssistant(samples, count = count); renderedGaps.add(samples, count)
    }
    fun externalPlaybackDrained() = synchronized(lock) { playback.reset(); levels.clearAssistant(); events.trySend(VoiceAudioEvent.playbackDrained); Unit }
    suspend fun flush(): Int = withContext(Dispatchers.IO) {
        // Lock ordering is always trackLock then lock, never around a blocking join.
        synchronized(trackLock) {
            synchronized(lock) {
                val played = playback.playedMilliseconds(if (externalOwner) externalFrames else playedFrames, PCM.sampleRate, session.outputLatency)
                resetPlaybackQueue()
                if (externalOwner) externalControl?.flush()
                else track?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }?.let {
                    runCatching { it.pause(); it.flush(); if (alive && !interrupted) it.play() }
                }
                levels.clearAssistant(); played
            }
        }
    }
    private fun resetPlaybackQueue() {
        playbackEpoch++; queue.clear(); queueEnd = 0; scheduler.stop(); playback.reset(); quality.assistantFlushed()
        playedFrames = 0; writtenFrames = 0
    }
    suspend fun setMuted(muted: Boolean) { levels.isMuted = muted; externalControl?.setMuted(muted) }
    suspend fun setAtEar(value: Boolean) = withContext(Dispatchers.IO) { control.withLock {
        if (value == atEar) return@withLock
        atEar = value
        if (!running || demo || VoiceAudioRoute.hasExternalOutput(session.outputPorts)) return@withLock
        if (atEar) cancelSpeakerRamp() else beginSpeakerRamp()
        try {
            holder.withActive { session.route(atEar, demo) }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            return@withLock
        } catch (error: Exception) {
            cancelSpeakerRamp()
            AppLog.voice.error("Couldn’t route call audio (${error.javaClass.simpleName})")
            return@withLock
        }
        note(VoiceCallLog.Note.Kind.audio, (if (atEar) "At the ear: earpiece · " else "Away from the ear: speaker · ") + session.routeDescription)
    } }
    suspend fun noteConnected() {
        synchronized(lock) { if (!running || didConnect) return; didConnect = true; gate.open() }
        sounds.noteConnected(id)
        lab?.input?.let { samples -> labTask = scope.launch {
            val start = clock.now(); var n = 0; var offset = 0
            while (isActive) {
                n++; val target = start + (n * 0.1).seconds; clock.sleep(maxOf(Duration.ZERO, target - clock.now()))
                val chunk = ByteArray(PCM.chunkFrames * 2)
                if (n * 0.1 > lab.inputDelay && offset < samples.size) { val end = min(samples.size, offset + chunk.size); samples.copyInto(chunk, 0, offset, end); offset = end }
                if (!levels.isMuted) { levels.updateUser(chunk); forwarder.emit(chunk) }
            }
        } }
    }
    private fun beginSpeakerRamp() {
        rampTask?.cancel(); rampGeneration++; val generation = rampGeneration
        setPlaybackVolume(speakerRampStartVolume)
        note(VoiceCallLog.Note.Kind.audio, "Ramping speaker gain (280 ms)")
        rampTask = scope.launch { for (step in 1..speakerRampSteps) {
            clock.sleep(speakerRampDuration / speakerRampSteps)
            stepSpeakerRamp(generation, if (step == speakerRampSteps) 1f else speakerRampStartVolume + (1 - speakerRampStartVolume) * step / speakerRampSteps, step == speakerRampSteps)
        } }
    }
    fun stepSpeakerRamp(generation: Int, volume: Float, finished: Boolean) {
        if (!running || generation != rampGeneration) return
        setPlaybackVolume(volume); if (finished) rampTask = null
    }
    fun cancelSpeakerRamp() { rampGeneration++; rampTask?.cancel(); rampTask = null; setPlaybackVolume(1f) }
    private fun setPlaybackVolume(volume: Float) {
        if (playbackVolume == volume) return
        playbackVolume = volume; externalControl?.setPlaybackVolume(volume)
        synchronized(lock) { (cueTracks + listOfNotNull(loopTrack)).forEach { it.setVolume(if (muteOutput) 0f else volume) } }
    }
    suspend fun handleConfigurationChange() = control.withLock {
        if (!running || interruptionInProgress || activationCancelled) return@withLock
        // A reactivation that failed when the interruption ended retries on the next change.
        if (interrupted) { recoverFromInterruption(); return@withLock }
        val external = VoiceAudioRoute.hasExternalOutput(session.outputPorts)
        if (!demo && !atEar && !external) beginSpeakerRamp() else cancelSpeakerRamp()
        try {
            holder.withActive { session.route(atEar, demo) }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
        } catch (error: Exception) {
            AppLog.voice.error("Couldn’t route call audio (${error.javaClass.simpleName})")
        }
        if (!demo && session.mode != AudioManager.MODE_IN_COMMUNICATION) {
            try {
                holder.activate { session.activate(demo); session.route(atEar, demo) }
                note(VoiceCallLog.Note.Kind.audio, "⚠︎ Call audio session restored · ${session.routeDescription}")
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                cancelSpeakerRamp()
                return@withLock
            } catch (error: Exception) {
                cancelSpeakerRamp()
                AppLog.voice.error("Couldn’t restore call audio (${error.javaClass.simpleName})")
                return@withLock
            }
        } else {
            // A fallback to the built-in output (`oldDeviceUnavailable`) counts as the ear's route,
            // and following an external device as a route change.
            val route = when { demo || external -> "Route changed · "; atEar -> "At the ear: earpiece · "; else -> "Away from the ear: speaker · " }
            note(VoiceCallLog.Note.Kind.audio, route + session.routeDescription)
        }
        events.trySend(VoiceAudioEvent.routeChanged)
    }
    suspend fun handleInterruption(began: Boolean) = control.withLock {
        if (!running || activationCancelled) return@withLock
        if (began) {
            if (interruptionInProgress) return@withLock
            interruptionInProgress = true
            interrupted = true; holder.invalidateActivation(); cancelSpeakerRamp(); flush()
            runCatching { record?.stop() }
            synchronized(lock) { cueJobs.toList() }.forEach { it.cancel() }
            val loop = loopSamples; stopLoopTrack(); loopSamples = loop
            externalControl?.interrupted(true)
            callAudio?.interrupted(id, true)
            note(VoiceCallLog.Note.Kind.audio, "⚠︎ Audio interrupted (another app or call took it)")
            levels.reset(); events.trySend(VoiceAudioEvent.interrupted)
        } else {
            interruptionInProgress = false
            if (!interrupted) return@withLock
            recoverFromInterruption()
        }
    }
    /** `interrupted` stays set until this succeeds. */
    private fun recoverFromInterruption() = try {
        holder.activate { session.activate(demo); session.route(atEar, demo) }
        cancelSpeakerRamp()
        holder.withActive { record?.startRecording(); synchronized(trackLock) { track?.play() }; externalControl?.interrupted(false) }
        callAudio?.interrupted(id, false)
        interrupted = false
        loopSamples?.let { startLoop(it) }
        events.trySend(VoiceAudioEvent.resumed)
        Unit
    } catch (error: Exception) { AppLog.voice.error("Couldn’t resume call audio (${error.javaClass.simpleName})") }
    suspend fun handleMediaReset() { mediaReset = true; holder.release(); if (running) { events.trySend(VoiceAudioEvent.mediaServicesReset); stop() } }
    private fun cancelAudioSession() { activationCancelled = true; holder.release() }
    suspend fun stop() = withContext(Dispatchers.IO + NonCancellable) {
        val backstop = scope.launch { clock.sleep(CallAudioTeardown.deadline); cancelAudioSession() }
        control.lock()
        try {
            if (stopped) return@withContext
            stopped = true; running = false; alive = false; gate.close(); cancelSpeakerRamp(); qualityTask?.cancel(); labTask?.cancel()
            val playingCues = synchronized(lock) { cueJobs.toList().also { cueJobs.clear(); lastCues.fill(null) } }
            playingCues.forEach { it.cancelAndJoin() }
            stopCues()
            callAudio?.detach(id)
            reportQuality(); releaseHardware(); session.unobserve(id); externalControl?.stop()
            capture.close(); events.close()
            renderedGaps.counter?.let { sounds.recordPlayback(VoiceCallSounds.PlaybackTelemetry(scheduledBuffers, underruns, it.gaps, (it.gapSamples / PCM.sampleRate * 1000).roundToInt())) }
            val superseded = AudioSessionGeneration.value != generation
            if (!superseded) levels.reset()
            val end = sounds.endCallClock(id, !superseded && didConnect && !mediaReset && !activationCancelled)
            if (end != null && AudioSessionGeneration.value == generation) {
                sounds.noteEndTailStarted(session.outputLatency + session.ioBufferDuration)
                playStandaloneCue(end, tail = true)
            }
            clock.sleep(if (end == null) 500.milliseconds else 250.milliseconds)
            dependency(VoiceHapticEngine).finish()
        } finally { holder.release(); backstop.cancel(); sounds.flushDebugLogAsync(); scope.cancel(); control.unlock() }
    }
    private fun releaseHardware() {
        // Detach under trackLock -> lock before stopping/releasing so concurrent flush()
        // never calls pause() on an uninitialized AudioTrack.
        val (input, output) = synchronized(trackLock) {
            synchronized(lock) {
                playbackEpoch++
                val currentRecord = record
                val currentTrack = track
                record = null
                track = null
                currentRecord to currentTrack
            }
        }
        val cap = captureThread.also { captureThread = null }
        val play = playbackThread.also { playbackThread = null }
        runCatching { input?.stop() }; runCatching { output?.stop() }
        cap?.takeIf { it != Thread.currentThread() }?.join(1000)
        play?.takeIf { it != Thread.currentThread() }?.join(1000)
        // Release unblocks a driver read/write that refused stop, then join it.
        effects.forEach { runCatching { it.release() } }; effects.clear()
        runCatching { input?.release() }; runCatching { output?.release() }
        cap?.takeIf { it != Thread.currentThread() && it.isAlive }?.join(1000)
        play?.takeIf { it != Thread.currentThread() && it.isAlive }?.join(1000)
    }
    private suspend fun playStandaloneCue(samples: FloatArray, tail: Boolean = false) = withContext(Dispatchers.IO) {
        // A cue that can't get a track is skipped, never a crash in the engine's scope.
        val output = try {
            AudioTrack.Builder().setAudioAttributes(LiveVoiceAudioSession.attributes(demo)).setAudioFormat(PCM.playbackFormat)
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 4).build()
        } catch (error: Exception) {
            AppLog.voice.error("Couldn’t play a call cue (${error.javaClass.simpleName})")
            return@withContext
        }
        try {
            output.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (telemetry) sounds.recordMixerBuffer(samples)
            synchronized(lock) {
                cueTracks.add(output)
                output.setVolume(if (muteOutput) 0f else playbackVolume)
            }
            holder.withActive { output.play() }
            if (tail) AudioSessionGeneration.onNext(generation) { runCatching { output.stop() } }
            clock.sleep((if (tail) VoiceCallSoundBank.endTailWait(samples) else samples.size / PCM.sampleRate).seconds)
        } finally {
            synchronized(lock) { cueTracks.remove(output) }
            runCatching { output.stop() }; output.release()
        }
    }
    companion object {
        val speakerRampDuration = 280.milliseconds
        const val speakerRampStartVolume = 0.15f
        const val speakerRampSteps = 7
    }
}

class CallAudioTeardown(
    val sleep: suspend (Duration) -> Unit, val finishTail: suspend () -> Unit,
    val finishHaptics: suspend () -> Unit, val release: () -> Unit,
) {
    suspend fun run(tail: Duration?) {
        try { if (tail != null) sleep(tail); finishTail(); sleep(if (tail == null) 500.milliseconds else 250.milliseconds); finishHaptics() }
        finally { release() }
    }
    companion object { val deadline = 3.seconds }
}
object VoiceAudioDemoMode { val isEnabled get() = shared(SharedKeys.developerMode).value && shared(SharedKeys.voiceDemoMode).value }
