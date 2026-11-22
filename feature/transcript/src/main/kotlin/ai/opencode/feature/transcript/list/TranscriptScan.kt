package ai.opencode.feature.transcript.list

/**
 * Counts the transcript rows a reconciliation walks while a test holds it, so the test can prove a
 * refresh works on the rows it touched, not on every row of a long session. Lives in
 * `:feature:transcript`, below the session reducers, so [NewMessagesMark.firstUnseen] can record
 * too.
 */
object TranscriptScan {
    @Volatile
    @PublishedApi
    internal var tally: NewMessagesMark.Companion.Tally? = null

    /** Runs [block] with [tally] counting the rows walked. */
    inline fun <T> withTally(tally: NewMessagesMark.Companion.Tally, block: () -> T): T {
        val previous = this.tally
        this.tally = tally
        try {
            return block()
        } finally {
            this.tally = previous
        }
    }

    fun record(rows: Int) {
        tally?.add(rows)
    }
}
