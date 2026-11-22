package ai.opencode.feature.session

import ai.opencode.core.dependencies.DateClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceLiveTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    @Test fun theSessionScreenReportsTheNewestFinishedCompactionOnlyDuringACall() = sessionTest { h ->
        var state = h.state {
            State.session(
                server = server,
                session = session,
                cachedItems = listOf(
                    OpenCodeTranscriptItem.Compaction(id = "cmp_old", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
                    OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Hello"),
                    OpenCodeTranscriptItem.Compaction(id = "cmp_new", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
                    OpenCodeTranscriptItem.Compaction(id = "cmp_running", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Running)),
                ),
            )
        }
        assertNull("No call, no scan", state.voiceCompaction)
        state = state.copy(voice = h.state { VoiceFeature.State(server, session) })
        assertEquals(
            "A running compaction hasn't dropped anything yet",
            VoiceFeature.State.CompactionMark(sessionID = "ses_voice", compactionID = "cmp_new"),
            state.voiceCompaction,
        )
        state = state.copy(voice = state.voice?.let { it.copy(target = it.target.copy(sessionID = "ses_elsewhere")) })
        assertNull("Only the call's own session", state.voiceCompaction)
    }

    /**
     * A compaction that happened while the call was elsewhere is seen when
     * the call arrives, not only when a later one lands.
     */
    @Test fun aCallArrivingInASessionKnowsItsLatestCompaction() = sessionTest { h ->
        val other = OpenCodeSession(id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)
        val compacted = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_1", text = "Hello"),
            OpenCodeTranscriptItem.Compaction(id = "cmp_1", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed)),
        )
        val store = h.store(h.state { State.session(server = server, session = other, cachedItems = compacted) }) {
            this[DateClient] = DateClient.constant(date(0))
        }
        store.exhaustive = false
        store.send(Action.VoiceTapped)
        assertEquals("Started here", "cmp_1", store.state.voice?.compactions?.get("ses_other"))
        h.skipInFlightEffects()
    }
}
