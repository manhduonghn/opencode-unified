package ai.opencode.feature.composer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Recording, one press at a time. The mic goes live first, so its first words are never clipped;
 * the model loads alongside and takes the audio heard meanwhile from [DictationInput]. Every member
 * runs on one single-threaded dispatcher and, like an actor, interleaves at suspension points.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveDictation(
    private val engine: DictationEngine,
    private val actor: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /**
     * One press's resources. A stop or cancel only ever reaches its own
     * recording; a newer press has a new one. Touched only on [actor].
     */
    private class Recording(
        val press: Int,
        val output: Channel<String>,
        val sessionToken: Int,
    ) {
        val input = DictationInput()
        var capture: DictationCapture? = null
        var recognition: Job? = null
        var model: DictationModel? = null

        /** The model load this press took, until its model is in hand. */
        var load: Deferred<DictationModel>? = null
        var results: Job? = null
        var hasWords = false
        var isReleased = false
        private var micStopped = false

        /**
         * Stops its mic once: a stop's finish and a later cancel's discard (a call
         * starting while it finalizes) can both reach it.
         */
        fun stopCapture() {
            if (micStopped) return
            micStopped = true
            capture?.stop()
        }

        /** Interrupted, or the recognizer failed: never held after. */
        var failed = false

        /** Its stream has finished: nothing left to discard. */
        var delivered = false
    }

    /** Every press's place in its lifecycle. Behind a lock because [begin] runs synchronously from the touch. */
    private class Presses {
        var last = 0

        /** The newest press. An older one still waiting to capture never does. */
        var newest = 0

        /** Stopped or cancelled: these never capture, whenever their recording gets to run. */
        val ended = HashSet<Int>()

        /** Each press's recording, started by [begin], until its [start] has adopted it or a cancel drops it. */
        val begun = HashMap<Int, Deferred<ReceiveChannel<String>>>()

        /** Who owned the audio as each press began. Anyone claiming it since makes the press yield it. */
        val sessionOwner = HashMap<Int, Int>()

        /** How each press ended, as the reducer said ([ends]). */
        val ends = HashMap<Int, DictationEnd>()

        /** Forgets presses long over. */
        fun prune() {
            val oldest = newest - 16
            ended.removeAll { it <= oldest }
            begun.keys.removeAll { it <= oldest }
            sessionOwner.keys.removeAll { it <= oldest }
            ends.keys.removeAll { it <= oldest }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + actor)
    private var current: Recording? = null

    /** Stopped recordings still finalizing, by press: a cancel (a call starting) discards them too. */
    private val finishing = HashMap<Int, Recording>()
    private val presses = Presses()

    /** The next press's model, loading or loaded. A press takes it, and its end starts loading the next. */
    private var warm: Deferred<DictationModel>? = null

    /**
     * When background prewarming ([prepare]) fails with `LocaleUnsupported`,
     * skip repeating it on every session switch while the locale is unchanged;
     * explicit mic presses still load directly and can recover.
     */
    private var prepareUnsupportedLocale: String? = null

    /**
     * Loads the next press's model, or joins the load under way. Never touches
     * the audio: this runs on every session switch, and a voice call may own it then.
     */
    suspend fun prepare() = withContext(actor) {
        val locale = Locale.getDefault().toLanguageTag()
        if (prepareUnsupportedLocale == locale) return@withContext
        try {
            warmModel(install = false).await()
            prepareUnsupportedLocale = null
        } catch (error: DictationError.LocaleUnsupported) {
            prepareUnsupportedLocale = locale
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
        } catch (_: Exception) {
        }
    }

    /**
     * [install]: whether this load may download the on-device language (a
     * system dialog on Android), which only a press asks for.
     */
    private fun warmModel(install: Boolean): Deferred<DictationModel> {
        warm?.let { return it }
        val task = scope.async { engine.loadModel(install) }
        keepWarm(task)
        return task
    }

    /**
     * The press's model: the warm one, or a new load that may install the
     * language. A warm-up that found the language missing gives way to that
     * install, so the first press that needs it downloads it.
     */
    private fun pressModel(): Deferred<DictationModel> {
        val joined = warm ?: return warmModel(install = true)
        val task = scope.async {
            try {
                joined.await()
            } catch (_: DictationModelNotInstalled) {
                engine.loadModel(true)
            }
        }
        // This press owns the joined load now. A deadline must cancel the actual
        // service wait, not just the coroutine awaiting it.
        task.invokeOnCompletion { if (it is CancellationException) joined.cancel() }
        keepWarm(task)
        return task
    }

    private fun keepWarm(task: Deferred<DictationModel>) {
        warm = task
        // A failed load is forgotten, so the next attempt retries.
        scope.launch {
            try {
                task.await()
            } catch (_: Throwable) {
                if (warm === task) warm = null
            }
        }
    }

    /** A new press: its recording starts now, off the main thread, and [start] adopts it. Returns the press's token. */
    fun begin(): Int {
        val owner = engine.sessionOwner()
        return synchronized(presses) {
            presses.last += 1
            val press = presses.last
            presses.newest = press
            presses.sessionOwner[press] = owner
            presses.begun[press] = scope.async { record(press) }
            presses.prune()
            press
        }
    }

    /** How [press] ends, for the hold after it; the last word before its session is released wins. */
    fun ends(press: Int, end: DictationEnd) {
        synchronized(presses) { presses.ends[press] = end }
    }

    /** Adopts [press]'s recording, never another press's. */
    suspend fun start(press: Int): ReceiveChannel<String> {
        val task = synchronized(presses) { presses.begun[press] } ?: throw CancellationException("No recording for the press")
        return try {
            withContext(actor) {
                try {
                    // The press's effect cancelled (leaving the screen): so is its recording.
                    val transcripts = task.await()
                    // Cancelled just as it started: nobody will read it, so end it here.
                    if (!currentCoroutineContext().isActive) {
                        withContext(NonCancellable) { cancelNow(press) }
                        throw CancellationException("Dictation press cancelled")
                    }
                    transcripts
                } finally {
                    synchronized(presses) { presses.begun.remove(press) }
                }
            }
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // `withContext` can give up before its block runs, so a recording could still reach
                // the mic with nobody to stop it: cancel it, wait for it to settle, then end the
                // press.
                task.cancel()
                withContext(NonCancellable) {
                    task.join()
                    cancel(press)
                }
            }
            throw cancellation
        }
    }

    /** Whether [press] still wants the mic: not stopped, cancelled or overtaken by a newer press. */
    private suspend fun isLive(press: Int): Boolean =
        currentCoroutineContext().isActive && synchronized(presses) { presses.newest == press && press !in presses.ended }

    /** Whether [press] may claim the audio: still live, and nobody else (a voice call) has claimed it since it began. */
    private suspend fun mayClaimSession(press: Int): Boolean =
        isLive(press) && synchronized(presses) { presses.sessionOwner[press] } == engine.sessionOwner()

    private suspend fun record(press: Int): ReceiveChannel<String> {
        DictationLatency.mark("client_start")
        if (!isLive(press)) throw CancellationException("Dictation press over")
        // A recording still running finishes on its own; the press doesn't wait.
        current?.let { previous ->
            current = null
            scope.launch { finish(previous) }
        }
        // Already granted (nearly always): straight on, with no hop off the dispatcher.
        if (!engine.isPermissionGranted()) {
            if (!engine.requestPermission()) throw DictationError.MicrophoneDenied()
        }
        // Stopped, cancelled or overtaken while the prompt was up, or a voice call
        // took the audio: never claim it from under the call.
        if (!mayClaimSession(press)) throw CancellationException("Dictation press yielded")

        val output = Channel<String>(Channel.UNLIMITED)
        val recording = Recording(press, output, engine.claimSession())
        if (!isLive(press)) {
            // Releases the session only while this press still owns it.
            release(recording)
            throw CancellationException("Dictation press over")
        }
        recording.capture = engine.startCapture(recording.input) { event ->
            when (event) {
                // Finalizing what was heard ends the stream, so the reducer leaves
                // the dictating state with the words in the draft, unsent.
                DictationCaptureEvent.Interrupted -> scope.launch { interrupt(recording) }
            }
        }
        current = recording
        DictationLatency.mark("mic_live")
        // When the consuming effect is cancelled without calling stop() (leaving
        // the screen), discard capture so the mic does not outlive it.
        output.invokeOnClose { cause ->
            if (cause is CancellationException) scope.launch { discard(recording) }
        }
        recording.recognition = scope.launch { recognize(recording) }
        return output
    }

    /**
     * Takes the warm model, hands it the audio heard so far, then streams the
     * transcript so far: finalized text plus the current volatile tail.
     */
    private suspend fun recognize(recording: Recording) {
        // Cancelled before it took a model: the next press takes it.
        if (recording.input.isDiscarded) return deliver(recording)
        val load = pressModel()
        warm = null
        recording.load = load
        try {
            val model = load.await()
            prepareUnsupportedLocale = null
            // Cancelled while it loaded: `release` handed the load back for the
            // next press, which may be using its model already.
            val format = recording.capture?.format
            if (recording.load !== load || recording.input.isDiscarded || format == null) {
                if (recording.load === load) handBack(recording)
                return deliver(recording)
            }
            recording.load = null
            recording.model = model
            val results = model.start(recording.input, format)
            if (recording.input.isDiscarded) return deliver(recording)
            DictationLatency.mark("model_ready")
            recording.results = scope.launch {
                var finalized = ""
                try {
                    for (result in results) {
                        DictationLatency.mark("first_words")
                        val text = finalized + result.text
                        if (result.isFinal) finalized = text
                        if (text.isNotBlank()) {
                            recording.hasWords = true
                            recording.output.trySend(text)
                        }
                    }
                    deliver(recording)
                } catch (cancellation: CancellationException) {
                    deliver(recording)
                    throw cancellation
                } catch (error: Throwable) {
                    deliver(recording, error)
                }
            }
        } catch (cancellation: CancellationException) {
            // Discarded while it loaded: the load goes back for the next press.
            if (recording.load === load) handBack(recording)
            deliver(recording)
            throw cancellation
        } catch (error: Throwable) {
            if (recording.load === load) recording.load = null
            deliver(recording, error)
        }
    }

    /** Gives the load [recording] took and never used back as the warm model, so the next press joins it. */
    private fun handBack(recording: Recording) {
        val load = recording.load ?: return
        recording.load = null
        if (warm == null) keepWarm(load)
    }

    /** Ends [recording]'s stream; a failure means no hold after it. */
    private fun deliver(recording: Recording, failure: Throwable? = null) {
        if (recording.delivered) return
        recording.delivered = true
        val error = when {
            recording.input.isDiscarded -> null
            !recording.hasWords && recording.model?.isInstallFallback == true &&
                (failure == null || failure is DictationError.Recognizer) -> DictationError.LocaleUnsupported()
            else -> failure
        }
        if (error != null) recording.failed = true
        recording.output.close(error.takeUnless { recording.hasWords })
    }

    /** Something outside stalled or changed the mic: what was heard is finalized, and the session isn't held after. */
    private suspend fun interrupt(recording: Recording) {
        if (!recording.input.isDiscarded) recording.failed = true
        finishIfCurrent(recording)
    }

    /** Stops capture and finalizes: the last words arrive on the stream, which then closes. */
    suspend fun stop(press: Int) = withContext(actor) {
        // Its `start` still adopts the recording, if it got going, for the last
        // words; one that hadn't reached the mic never will.
        val begun = synchronized(presses) {
            presses.ended += press
            presses.begun[press]
        }
        begun?.cancel()
        val recording = current?.takeIf { it.press == press } ?: return@withContext
        current = null
        finish(recording)
    }

    /** Finalizes [recording] if a newer press hasn't replaced it. */
    private suspend fun finishIfCurrent(recording: Recording) {
        if (current !== recording) return
        current = null
        finish(recording)
    }

    /** Stops capture at once and throws the audio away. */
    suspend fun cancel(press: Int) = withContext(actor) { cancelNow(press) }

    private suspend fun cancelNow(press: Int) {
        val begun = synchronized(presses) {
            presses.ended += press
            presses.begun.remove(press)
        }
        begun?.cancel()
        val recording = current
        if (recording != null && recording.press == press) {
            current = null
            discard(recording)
        } else {
            // Stopped and still finalizing (a call starting): its last words
            // would land after the call took the mic.
            val stopped = finishing[press]
            if (stopped != null && !stopped.delivered) discard(stopped)
        }
    }

    private suspend fun finish(recording: Recording) {
        finishing[recording.press] = recording
        try {
            // Android's speech service can hang during install as well as during
            // recognition. Both share one deadline starting at Stop.
            val finished = withTimeoutOrNull(FINISH_TIMEOUT) {
                recording.stopCapture()
                // A model still loading gets everything heard so far.
                recording.input.end()
                recording.recognition?.join()
                recording.model?.finish?.invoke()
                recording.results?.join()
                true
            }
            if (finished == null && !recording.isReleased) {
                deliver(recording, DictationError.LocaleUnsupported())
                recording.load?.cancel()
                recording.load = null // Don't hand the timed-out load to another press.
                recording.recognition?.cancel()
                recording.results?.cancel()
                recording.model?.cancel?.invoke()
            }
            release(recording)
        } finally {
            if (finishing[recording.press] === recording) finishing.remove(recording.press)
        }
    }

    private suspend fun discard(recording: Recording) {
        if (current === recording) current = null
        recording.stopCapture()
        recording.input.discard()
        recording.recognition?.cancel()
        recording.results?.cancel()
        recording.model?.cancel?.invoke()
        recording.output.close()
        release(recording)
    }

    private fun release(recording: Recording) {
        if (recording.isReleased) return
        recording.isReleased = true
        // Finalizing can take a second: a newer press or a call that claimed the
        // session meanwhile owns it now, and keeps it (`releaseSession`).
        val end = when {
            recording.input.isDiscarded -> DictationEnd.Cancel
            recording.failed -> DictationEnd.Failed
            else -> synchronized(presses) { presses.ends[recording.press] } ?: DictationEnd.Insert
        }
        engine.releaseSession(end, recording.sessionToken)
        // The next press starts warm. A load this press took and never used goes
        // back at once, so a press now joins it rather than preparing a second model.
        if (recording.load != null) handBack(recording) else warmModel(install = false)
    }

    private companion object {
        val FINISH_TIMEOUT = 10.seconds
    }
}
