package ai.opencode.feature.settings.connection

import ai.opencode.server.HostAddress
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import ai.opencode.server.friendlyServerName
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The Settings connection card's model: one beat per health check, drawn like an EKG, and the
 * status the card shows.
 *
 * The status follows the trace, not the network, so it lags the link by a beat: a measured state
 * lands on its beat's spike, Reconnecting when the first unanswered check would have been answered,
 * Unstable when a check goes unanswered. Offline, Sign-in failed and Switching show at once.
 *
 * Times are seconds on the `Clock` dependency's monotonic clock. A plain mutable model owned by
 * [ConnectionMonitor] on the main thread.
 */
class ConnectionHealth(link: Link, at: Double, milliseconds: Int? = null) {
    /** The app's connection, as Home reports it. */
    sealed interface Link {
        data object Connected : Link
        data object Reconnecting : Link
        data object Offline : Link
        /**
         * The server refused the saved credentials (a 401): offline until the server is edited,
         * since retrying them can't help.
         */
        data object Rejected : Link
        data class Switching(val to: String) : Link

        /** No checks run and the trace lies flat. */
        val isDown: Boolean get() = this == Offline || this == Rejected

        val target: String? get() = (this as? Switching)?.to
    }

    /** What the card says. */
    enum class Status { connected, slow, unstable, reconnecting, offline, signInFailed, switching }

    sealed interface Outcome {
        data object Pending : Outcome
        data class Answered(val milliseconds: Int) : Outcome
        data object Unanswered : Outcome
    }

    /** One health check: the P wave when the request leaves, the spike when the answer comes back. */
    data class Beat(val id: Int, val time: Double, val outcome: Outcome) {
        val milliseconds: Int? get() = (outcome as? Outcome.Answered)?.milliseconds
    }

    data class Display(
        val status: Status,
        /**
         * The round trip shown: the rolling average of the recent answered checks
         * ([averageMilliseconds]), so one slow answer doesn't jolt it.
         */
        val milliseconds: Int? = null,
        /** The server being switched to. */
        val target: String? = null,
    )

    /** A display change waiting for its moment on the trace. */
    data class Change(val at: Double, val display: Display)

    data class OfflineSpan(val start: Double, val end: Double? = null) {
        fun contains(t: Double): Boolean = t >= start && t < (end ?: Double.POSITIVE_INFINITY)
    }

    companion object {
        /** A round trip at or above this reads as Slow. */
        const val slowMilliseconds = 300
        /** A check not answered by then is a dropped beat. */
        const val answerDeadline = 1.2
        /** Checks while connected. */
        const val cadence = 2.0
        /** Checks while reconnecting. */
        const val reconnectingCadence = 1.5
        /** One pass of the pen across the scope. */
        const val sweep = 4.5
        /** Switching sweeps the old trace clear in this long. */
        const val eraseDuration = 0.7
        /** Reconnecting lands when the first unanswered check would have been answered. */
        const val reconnectingDelay = 0.45
        /** Resolved checks that judge the line: any drop among them is Unstable. */
        const val judged = 5
        /** Answered checks the shown round trip averages, like a game's ping: about six seconds at the connected cadence. */
        const val averaged = 3

        private fun status(of: Link): Status = when (of) {
            Link.Connected -> Status.connected
            Link.Reconnecting -> Status.reconnecting
            Link.Offline -> Status.offline
            Link.Rejected -> Status.signInFailed
            is Link.Switching -> Status.switching
        }
    }

    var link: Link = link
        private set
    var linkSince: Double = at
        private set
    private val _beats = mutableListOf<Beat>()
    val beats: List<Beat> get() = _beats
    /** When the link went offline, and came back (null while it still is). */
    private val _offline = mutableListOf<OfflineSpan>()
    val offline: List<OfflineSpan> get() = _offline
    var display: Display
        private set
    private val _changes = mutableListOf<Change>()
    val changes: List<Change> get() = _changes
    var lastCheck: Double? = null
        private set
    /** Where the pen starts its passes; Switching restarts it at the left. */
    var sweepOrigin: Double = at - sweep * 0.7
        private set
    var previousOrigin: Double = sweepOrigin
        private set
    /** The trace before this is gone (Switching cleared it). */
    var clearAt: Double = Double.NEGATIVE_INFINITY
        private set
    var eraseStart: Double? = null
        private set
    private var checkDue: Double? = null
    private var nextID = 0
    /**
     * The last [averaged] answered round trips since the link connected, oldest first. Settings
     * opens with the number it last showed for this server as the first, so the first check
     * doesn't jump from it.
     */
    var recentMilliseconds: List<Int> = emptyList()
        private set

    init {
        // Opening onto a connection shows where it is at once; there's no trace to wait for yet.
        display = Display(status(link), if (link == Link.Connected) milliseconds else null, link.target)
        recentMilliseconds = listOfNotNull(display.milliseconds)
        if (link.isDown) _offline += OfflineSpan(Double.NEGATIVE_INFINITY)
    }

    // Inputs

    fun setLink(new: Link, at: Double) {
        if (new == link) return
        val old = link
        link = new
        linkSince = at
        recentMilliseconds = emptyList()
        if (old.isDown && !new.isDown && _offline.isNotEmpty()) {
            _offline[_offline.lastIndex] = _offline.last().copy(end = at)
        }
        when (new) {
            Link.Offline, Link.Rejected -> {
                if (!old.isDown) _offline += OfflineSpan(at)
                _changes.clear()
                display = Display(status(new), null, null)
            }
            is Link.Switching -> {
                _changes.clear()
                display = Display(Status.switching, null, new.to)
                // The old trace sweeps clear, then the pen starts again at the left.
                previousOrigin = origin(at)
                eraseStart = at
                clearAt = at + eraseDuration
                sweepOrigin = clearAt
            }
            Link.Connected -> {
                // Check at once; Connected lands on the first full beat. A Reconnecting
                // still waiting for its moment is stale: the link came back first.
                _changes.removeAll { it.display.status == Status.reconnecting }
                checkDue = at + 0.15
            }
            Link.Reconnecting -> {
                _changes.clear()
                checkDue = at + 0.3
            }
        }
    }

    /** A health check leaves now. Returns its beat's ID. */
    fun beginCheck(at: Double): Int {
        nextID += 1
        _beats += Beat(nextID, at, Outcome.Pending)
        lastCheck = at
        checkDue = null
        if (link == Link.Reconnecting && display.status != Status.reconnecting &&
            _changes.none { it.display.status == Status.reconnecting }
        ) {
            schedule(Change(at + reconnectingDelay, Display(Status.reconnecting, null, null)))
        }
        val keep = at - sweep * 2.2
        _beats.removeAll { it.time < keep }
        _offline.removeAll { (it.end ?: Double.POSITIVE_INFINITY) < keep }
        return nextID
    }

    /**
     * The check [id] was answered in [milliseconds], now. A late answer, past the deadline,
     * stays a dropped beat.
     */
    fun answer(id: Int, milliseconds: Int, at: Double) {
        val index = _beats.indexOfFirst { it.id == id }
        if (index < 0 || _beats[index].outcome != Outcome.Pending || at - _beats[index].time > answerDeadline) return
        _beats[index] = _beats[index].copy(outcome = Outcome.Answered(milliseconds))
        // Reconnecting holds until Home reconnects; the answer only draws its spike.
        if (link != Link.Connected) return
        // A check that left before this connection says nothing about its round trip.
        if (_beats[index].time >= linkSince) {
            recentMilliseconds = (recentMilliseconds + milliseconds).takeLast(averaged)
        }
        val shape = BeatShape(milliseconds)
        schedule(
            Change(max(at, _beats[index].time + shape.spike), Display(measuredStatus(), averageMilliseconds ?: milliseconds, null)),
        )
    }

    /** The check [id] failed outright (no answer will come). */
    fun fail(id: Int, at: Double) {
        val index = _beats.indexOfFirst { it.id == id }
        if (index < 0 || _beats[index].outcome != Outcome.Pending) return
        resolveUnanswered(index, max(at, _beats[index].time))
    }

    /** Drops overdue checks and applies the changes now due. True when the display changed. */
    fun advance(to: Double): Boolean {
        for (index in _beats.indices) {
            val beat = _beats[index]
            if (beat.outcome == Outcome.Pending && to - beat.time > answerDeadline) {
                resolveUnanswered(index, beat.time + answerDeadline)
            }
        }
        val before = display
        while (_changes.isNotEmpty() && _changes.first().at <= to) {
            display = _changes.removeAt(0).display
        }
        return display != before
    }

    private fun resolveUnanswered(index: Int, at: Double) {
        _beats[index] = _beats[index].copy(outcome = Outcome.Unanswered)
        // A drop while connected is Unstable; the number stays the last measured.
        // A check that left before this connection (while reconnecting, or to
        // the server switched from) says nothing about it, as in `measuredStatus`.
        if (link != Link.Connected || _beats[index].time < linkSince) return
        schedule(Change(at, Display(Status.unstable, display.milliseconds, null)))
    }

    private fun schedule(change: Change) {
        _changes += change
        _changes.sortBy { it.at }
    }

    // Reading

    /** Connected, Slow or Unstable, from the checks since the link connected. */
    fun measuredStatus(): Status {
        val resolved = _beats.filter { it.time >= linkSince && it.outcome != Outcome.Pending }.takeLast(judged)
        if (resolved.any { it.outcome == Outcome.Unanswered }) return Status.unstable
        val recent = resolved.mapNotNull { it.milliseconds }.takeLast(3).sorted()
        if (recent.isEmpty()) return Status.connected
        return if (recent[(recent.size - 1) / 2] >= slowMilliseconds) Status.slow else Status.connected
    }

    /**
     * The rolling average of [recentMilliseconds], rounded; null before any. Drops don't count:
     * they show as Unstable, not as a number.
     */
    val averageMilliseconds: Int?
        get() = if (recentMilliseconds.isEmpty()) null else Math.round(recentMilliseconds.sum().toDouble() / recentMilliseconds.size).toInt()

    /** When the next check should leave; null while offline or switching. */
    val nextCheck: Double?
        get() = when (link) {
            Link.Offline, Link.Rejected, is Link.Switching -> null
            Link.Connected -> checkDue ?: lastCheck?.let { it + cadence } ?: linkSince
            Link.Reconnecting -> checkDue ?: lastCheck?.let { it + reconnectingCadence } ?: linkSince
        }

    /**
     * The next moment anything changes on its own: a check, a deadline, a display change, or the
     * end of a switch's erase.
     */
    val nextWake: Double?
        get() {
            val times = _changes.map { it.at }.toMutableList()
            times += _beats.filter { it.outcome == Outcome.Pending }.map { it.time + answerDeadline + 0.01 }
            nextCheck?.let { times += it }
            return times.minOrNull()
        }

    /** Where the pen's passes start at [t]. */
    fun origin(t: Double): Double = if (t >= clearAt) sweepOrigin else previousOrigin

    /** The trace is red only while the link is actually offline: once Retry starts, the flat stretch goes back to pearl. */
    fun isRed(t: Double): Boolean = link.isDown && _offline.any { it.contains(t) }

    fun isOffline(t: Double): Boolean = _offline.any { it.contains(t) }
}

/**
 * A beat's shape from its round trip: tall and crisp when fast, low and wide when slow. The gap
 * from the request (P wave) to the answer (QRS) is the round trip, stretched to at least the
 * prototype's PR interval, and never shorter than the real wait, so the pen never draws a spike
 * before its answer has arrived.
 */
data class BeatShape(
    /** Seconds from the P wave to the QRS. */
    val gap: Double,
    /** How much wider than the fastest beat. */
    val width: Double,
    /** The R spike's height (1 is the tallest). */
    val height: Double,
) {
    /** Seconds from the P wave to the R spike's peak. */
    val spike: Double get() = gap + 0.032 * width

    companion object {
        operator fun invoke(milliseconds: Int): BeatShape {
            val rtt = max(0, milliseconds).toDouble()
            val width = 1 + min(1.6, rtt / 280)
            val gap = max(0.13 + min(0.4, rtt / 1300), rtt / 1000 + 0.03)
            val height = 1 / (0.72 + 0.28 * width)
            return BeatShape(gap, width, height)
        }

        private fun gauss(x: Double, s: Double): Double = exp(-(x * x) / (2 * s * s))

        /** The trace [d] seconds after a check left: a P wave for the request and, once answered, the QRS and T wave. */
        fun value(d: Double, outcome: ConnectionHealth.Outcome): Double {
            if (d < 0 || d > ConnectionTrace.beatLength) return 0.0
            var v = 0.2 * gauss(d - 0.07, 0.035)
            val answered = outcome as? ConnectionHealth.Outcome.Answered ?: return v
            val shape = BeatShape(answered.milliseconds)
            val w = shape.width
            val p = shape.gap
            val r = shape.height
            v += -0.12 * gauss(d - p - 0.012 * w, 0.009 * w)
            v += r * gauss(d - p - 0.032 * w, 0.011 * w)
            v += -0.26 * r * gauss(d - p - 0.055 * w, 0.011 * w)
            v += 0.3 * gauss(d - p - 0.22 - 0.09 * w, 0.06 * w)
            return v
        }
    }
}

/** The trace's height over time, from the beats. */
object ConnectionTrace {
    /** How long one beat draws for. */
    const val beatLength = 2.0

    fun signal(t: Double, beats: List<ConnectionHealth.Beat>, offline: (Double) -> Boolean): Double {
        if (offline(t)) return 0.0
        var v = 0.0
        for (beat in beats) {
            if (t >= beat.time && t - beat.time < beatLength) v += BeatShape.value(t - beat.time, beat.outcome)
        }
        return v
    }

    /** The strongest value over one column's slice of time, so a narrow spike can't fall between columns. */
    fun sample(t: Double, span: Double, beats: List<ConnectionHealth.Beat>, offline: (Double) -> Boolean): Double {
        var best = 0.0
        for (k in 0 until 6) {
            val v = signal(t + k.toDouble() / 6 * span, beats, offline)
            if (abs(v) > abs(best)) best = v
        }
        return best
    }

    /** Reduce Motion's still trace: a few beats that match the status, ending just behind [t]. */
    fun stillBeats(display: ConnectionHealth.Display, t: Double): List<ConnectionHealth.Beat> {
        val status = display.status
        if (status == ConnectionHealth.Status.offline || status == ConnectionHealth.Status.signInFailed ||
            status == ConnectionHealth.Status.switching
        ) return emptyList()
        val cadence = if (status == ConnectionHealth.Status.reconnecting) ConnectionHealth.reconnectingCadence else ConnectionHealth.cadence
        val milliseconds = display.milliseconds ?: if (status == ConnectionHealth.Status.slow) 480 else 38
        val beats = ArrayList<ConnectionHealth.Beat>()
        var time = t - 0.9
        for (k in 0 until 5) {
            val dropped = status == ConnectionHealth.Status.reconnecting || (status == ConnectionHealth.Status.unstable && k == 1)
            val outcome = if (dropped) ConnectionHealth.Outcome.Unanswered else ConnectionHealth.Outcome.Answered(milliseconds)
            beats.add(0, ConnectionHealth.Beat(-k, time, outcome))
            time -= cadence
        }
        return beats
    }
}

/**
 * What the card calls the server: the name the user gave it, else the machine's name from a
 * tailnet or Bonjour address, else the machine's name a reverse lookup of its IP found (MagicDNS
 * answers for a tailnet IP), else its friendly name (“Server” and the last part of an unnamed IP).
 */
object ServerCardName {
    fun name(server: OpenCodeServer, resolved: String?): String {
        val chosen = server.name?.trim().orEmpty()
        if (chosen.isNotEmpty()) return chosen
        if (server.defaultName != server.host) return server.friendlyName
        val trimmed = resolved?.trim()
        if (!trimmed.isNullOrEmpty()) return friendlyServerName(trimmed)
        return server.friendlyName
    }

    /**
     * The machine's name from a reverse lookup's answer: the first label of
     * `my-mac.tail1234.ts.net.`; null for an empty or numeric answer, or a label that only spells
     * out an IPv4 address (`ip-10-0-2-2`, `ec2-3-89-50-194`), so the card falls back to the
     * friendly name the list and drawer use.
     */
    fun machineName(fromReverse: String): String? {
        val trimmed = fromReverse.trim('.', ' ', '\n')
        if (trimmed.isEmpty() || trimmed.all { it.isDigit() || it == '.' || it == ':' }) return null
        val first = trimmed.split('.').firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (spellsOutAddress(first)) return null
        return first
    }

    private val spelledAddress = Regex("""(?<!\d)(\d{1,3})[-_](\d{1,3})[-_](\d{1,3})[-_](\d{1,3})(?!\d)""")

    /** Whether [label] is all digits or holds four dash-separated octets (`host-192-168-1-5`). */
    private fun spellsOutAddress(label: String): Boolean {
        if (label.all { it.isDigit() }) return true
        return spelledAddress.findAll(label).any { match -> match.groupValues.drop(1).all { it.toInt() <= 255 } }
    }

    /** The IP to look up, when the address in use is one. */
    fun lookupAddress(server: OpenCodeServer): String? {
        val address = HostAddress.ofUrl(server.url) ?: return null
        if (!address.isIP || address.hasZone || address.isLoopback) return null
        return address.host
    }
}
