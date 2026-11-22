package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.client
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.SessionTranscriptView
import ai.opencode.feature.session.backgroundShells
import ai.opencode.feature.session.canChooseProject
import ai.opencode.feature.session.errorMessage
import ai.opencode.feature.session.hasBackgroundWork
import ai.opencode.feature.session.isTranscriptLoading
import ai.opencode.feature.session.transcriptSubagents
import ai.opencode.feature.session.visibleItems
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoard
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Compose has no observation probe, so step 4 of
 * `backgroundStreamingIsolationAndScrollFastPathRegression` checks the chrome values don't change
 * on a streaming delta.
 */
class TranscriptBenchmarkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val baseTime: Instant = Instant.ofEpochSecond(1_760_000_000)
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")

    private fun project(id: String, name: String) = OpenCodeProject(id = id, name = name, directory = "/Users/kit/code/$id")

    private fun session(id: String, title: String, project: OpenCodeProject, updatedAt: Instant = baseTime) = OpenCodeSession(
        id = id, serverTitle = title, projectID = project.id, directory = project.directory, updatedAt = updatedAt,
    )

    private fun session(id: String, title: String, projectID: String, directory: String, parentID: String? = null) = OpenCodeSession(
        id = id, serverTitle = title, projectID = projectID, directory = directory, updatedAt = baseTime, parentID = parentID,
    )

    private fun HomeFeature.State.live(isStreaming: Boolean = false) = copy(
        connection = connection.copy(
            hasConnected = true,
            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected,
            isStreaming = isStreaming || connection.isStreaming,
            isEventStreamLive = true,
        ),
        noticeReadiness = HomeFeature.State.NoticeReadiness.settled,
    )

    private fun tool(id: String, callID: String, name: String, status: OpenCodeToolCall.Status, metadata: Map<String, OpenCodeJSONValue> = emptyMap()) =
        OpenCodeTranscriptItem.Tool(
            id = id,
            call = OpenCodeToolCall(
                callID = callID, messageID = "msg_1", name = name, status = status,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = metadata,
            ),
        )

    @Test
    fun backgroundStreamingIsolationAndScrollFastPathRegression() = homeTest { harness ->
        val openProject = project("proj_0", "Proj 0")
        val bgProject = project("proj_1", "Proj 1")
        val openSession = session("ses_open", "Open", openProject)
        val bgSession = session("ses_bg", "Background", bgProject, updatedAt = baseTime.minusSeconds(10))
        val initialItems = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Hello"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = "Initial reply"),
        )
        val attentionCalls = LockIsolated(0)
        val bgTranscriptCalls = LockIsolated(0)
        val clock = TestClock()

        val homeState = harness.state {
            var s = HomeFeature.State(
                server,
                OpenCodeHomeSnapshot(
                    projects = listOf(openProject, bgProject),
                    sessions = listOf(openSession, bgSession),
                    activeSessionIDs = setOf(openSession.id, bgSession.id),
                ),
            ).live()
            s = s.copy(transcriptCache = s.transcriptCache + (bgSession.id to initialItems))
            val (next, made) = s.makeDetail(openSession, cachedItems = initialItems, reason = "test")
            val detail = made.copy(
                isVisible = true,
                hasRenderedFirstContentFrame = true,
                initialTranscriptIsFresh = true,
                familyReadAt = baseTime,
                shells = listOf(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = openSession.id, isUserCommand = false, isListed = true, startedAt = baseTime)),
            )
            next.copy(sessionDetail = detail)
        }
        assertTrue(homeState.sessionDetail?.streamIsHealthy == true)

        val store = harness.store(homeState) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(baseTime)
            this[HomeSnapshotDate] = DateClient.constant(baseTime)
            this[OpenCodeClient] = client {
                copy(
                    homeAttention = { _, _, _ ->
                        attentionCalls.increment()
                        emptySet()
                    },
                    transcript = { _, sessionID, _ ->
                        if (sessionID == bgSession.id) bgTranscriptCalls.increment()
                        initialItems
                    },
                    homeSnapshot = {
                        OpenCodeHomeSnapshot(
                            projects = listOf(openProject, bgProject),
                            sessions = listOf(openSession, bgSession),
                            activeSessionIDs = setOf(openSession.id),
                        )
                    },
                )
            }
        }
        store.exhaustive = false

        // 1. A background session spawning a child and starting runs leaves the open session's
        // family read and transcript subagents alone.
        val subagentsBefore = store.state.sessionDetail?.transcriptSubagents
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionCreated(sessionID = "ses_bg_child", parentID = bgSession.id)))
        assertEquals(baseTime, store.state.sessionDetail?.familyReadAt)
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(sessionID = "ses_bg_child")))
        assertEquals(subagentsBefore, store.state.sessionDetail?.transcriptSubagents)

        // 2. A cached background session streaming debounces its 2 s refresh on each batch.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(bgSession.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_b1", 0, "A")))))
        settle()
        clock.advance(1500.milliseconds)
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(bgSession.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_b1", 0, "B")))))
        settle()
        clock.advance(1500.milliseconds)
        assertEquals(0, bgTranscriptCalls.value)
        clock.advance(600.milliseconds)
        settle()
        assertEquals(1, bgTranscriptCalls.value)

        // 3. A snapshot refresh while live, settled and with unchanged attention directories
        // doesn't read attention again.
        val unchangedSnapshot = OpenCodeHomeSnapshot(
            projects = listOf(openProject, bgProject),
            sessions = listOf(openSession, bgSession),
            activeSessionIDs = store.state.activeSessionIDs,
            activeSessionParents = store.state.activeSessionParents,
        )
        store.send(HomeFeature.Action.SnapshotRefreshed(unchangedSnapshot))
        settle()
        clock.advance(300.milliseconds)
        settle()
        assertEquals(0, attentionCalls.value)

        // 4. The chrome doesn't change on a streaming delta.
        fun chrome(d: SessionFeature.State) = listOf(d.errorMessage, d.isTranscriptLoading, d.hasBackgroundWork, d.backgroundShells, d.canChooseProject)
        val chromeBefore = chrome(store.state.sessionDetail!!)
        store.send(
            detail(
                SessionFeature.Action.ContentBatch(
                    openSession.id,
                    listOf(OpenCodeLiveContentEvent.TextStarted("msg_a1", 0), OpenCodeLiveContentEvent.TextDelta("msg_a1", 0, " more text")),
                ),
            ),
        )
        assertEquals(chromeBefore, chrome(store.state.sessionDetail!!))

        // 5. The projection cache's tail fast path matches a cold build.
        val cache = SessionTranscriptView.ProjectionCache()
        val d1 = store.state.sessionDetail!!
        val vis1 = cache.resolveVisibleItems(d1.items, d1.visibleLimit, d1.browsingWindowStartID, revertMessageID = null) { d1.visibleItems }
        val (entries1, _) = cache.resolveEntries(
            visibleItems = vis1, allItems = d1.items, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        store.send(detail(SessionFeature.Action.ContentBatch(openSession.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_a1", 0, " and tail delta")))))
        val d2 = store.state.sessionDetail!!
        val vis2 = cache.resolveVisibleItems(d2.items, d2.visibleLimit, d2.browsingWindowStartID, revertMessageID = null) { d2.visibleItems }
        val (entries2, _) = cache.resolveEntries(
            visibleItems = vis2, allItems = d2.items, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        val coldEntries2 = TranscriptTimeStamps.inserting(
            TranscriptTimeStamps.stamps(d2.items, sentNow = null, now = baseTime),
            TranscriptEntry.build(d2.visibleItems, isRunning = true, models = emptyList()),
        )
        assertEquals(coldEntries2, entries2)
        assertEquals(entries1.size, entries2.size)
        assertNotEquals(entries1.last(), entries2.last())
        harness.skipInFlightEffects()
    }

    @Test
    fun contentBatchStepStartedForInactiveSessionRunsOnChangeHooks() = homeTest { harness ->
        val project = project("proj_0", "Proj 0")
        val openSession = session("ses_open", "Open", project)
        val inactiveSession = session("ses_inactive", "Inactive", project, updatedAt = baseTime.minusSeconds(10))
        val publishedBoards = LockIsolated<List<VoiceSessionBoard?>>(emptyList())

        val homeState = harness.state {
            val s = HomeFeature.State(
                server,
                OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(openSession, inactiveSession), activeSessionIDs = setOf(openSession.id)),
            ).live()
            val (next, made) = s.makeDetail(openSession, cachedItems = emptyList(), reason = "test")
            next.copy(sessionDetail = made.copy(voice = VoiceFeature.State(server, openSession)))
        }

        val store = harness.store(homeState) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(baseTime)
            this[HomeSnapshotDate] = DateClient.constant(baseTime)
            this[VoiceSessionBoardClient] = VoiceSessionBoardClient(
                publish = { board -> publishedBoards.append(board) },
                attentionRead = { _, _ -> },
                current = { null },
            )
        }
        store.exhaustive = false

        // A batch with a step start for a session not yet active runs the full chain, so the
        // observers (the call's session board among them) fire.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(inactiveSession.id, listOf(OpenCodeLiveContentEvent.StepStarted("msg_1")))))
        assertTrue(inactiveSession.id in store.state.activeSessionIDs)
        assertTrue(publishedBoards.value.lastOrNull()?.busyIDs?.contains(inactiveSession.id) == true)

        val publishCountAfterStart = publishedBoards.value.size
        // Once active, later batches take the fast path without the observers.
        store.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.ContentBatch(inactiveSession.id, listOf(OpenCodeLiveContentEvent.TextDelta("msg_1", 0, "hello")))))
        assertEquals(publishCountAfterStart, publishedBoards.value.size)
        harness.skipInFlightEffects()
    }

    @Test
    fun sameCountWindowAndMessageReplacementSyncsEarlierShellAndSubagentTools() = homeTest { harness ->
        val session = session("ses_open", "Open", projectID = "proj_0", directory = "/Users/kit/code/proj_0")
        val runningShellTool = tool("msg_1-tool-call_sh", "call_sh", "shell", OpenCodeToolCall.Status.Running, mapOf("shellID" to OpenCodeJSONValue.Str("sh_1")))
        val placeholderTool = tool("msg_1-tool-call_sub", "call_sub", "read", OpenCodeToolCall.Status.Completed)
        val tailMarkdown = OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_1-text-0", text = "Tail text unchanged")

        val initialState = SessionFeature.State.session(
            server = server,
            session = session,
            cachedItems = listOf(runningShellTool, placeholderTool, tailMarkdown),
            isRunning = true,
        ).copy(shells = listOf(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, isUserCommand = false, isListed = true, startedAt = baseTime)))
        assertEquals(OpenCodeToolCall.Status.Running, initialState.shellToolStatuses["sh_1"])
        assertTrue(initialState.backgroundShells.isEmpty())
        assertTrue(initialState.subagentToolSessionIDs.isEmpty())

        val store = harness.sessionStore(initialState) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(baseTime)
        }
        store.exhaustive = false

        // A window of the same count with the same tail settles the earlier shell tool and turns
        // the second item into a subagent tool.
        val completedShellTool = tool("msg_1-tool-call_sh", "call_sh", "shell", OpenCodeToolCall.Status.Completed, mapOf("shellID" to OpenCodeJSONValue.Str("sh_1")))
        val subagentTool = tool(
            "msg_1-tool-call_sub", "call_sub", "subagent", OpenCodeToolCall.Status.Completed,
            mapOf("sessionID" to OpenCodeJSONValue.Str("ses_sub_1"), "status" to OpenCodeJSONValue.Str("running")),
        )
        store.send(SessionFeature.Action.TranscriptResponse(session.id, Result.success(listOf(completedShellTool, subagentTool, tailMarkdown))))
        assertEquals(OpenCodeToolCall.Status.Completed, store.state.shellToolStatuses["sh_1"])
        assertEquals(listOf("sh_1"), store.state.backgroundShells.map { it.id })
        assertEquals(setOf("ses_sub_1"), store.state.subagentToolSessionIDs)

        // Older task calls can also supply the child ID in a progress event.
        store.send(
            SessionFeature.Action.ContentBatch(
                session.id,
                listOf(
                    OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_1", toolID = "call_sub_live", name = "task"),
                    OpenCodeLiveContentEvent.ToolProgress(messageID = "msg_1", toolID = "call_sub_live", metadata = mapOf("sessionID" to OpenCodeJSONValue.Str("ses_sub_live"))),
                ),
            ),
        )
        assertTrue("ses_sub_live" in store.state.subagentToolSessionIDs)
        harness.skipInFlightEffects()
    }

    @Test
    fun networkReroutedClearsStreamLiveAndRefreshesAttentionOnNextSnapshot() = homeTest { harness ->
        val project = project("proj_0", "Proj 0")
        val openSession = session("ses_open", "Open", project)
        val attentionCalls = LockIsolated(0)
        val clock = TestClock()

        val homeState = harness.state {
            val s = HomeFeature.State(
                server,
                OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(openSession), activeSessionIDs = setOf(openSession.id)),
            ).live(isStreaming = true)
            s.openingDetail(openSession, cachedItems = emptyList(), reason = "test")
        }
        val snapshot = OpenCodeHomeSnapshot(projects = listOf(project), sessions = listOf(openSession), activeSessionIDs = setOf(openSession.id))

        val store = harness.store(homeState) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(baseTime)
            this[HomeSnapshotDate] = DateClient.constant(baseTime)
            this[OpenCodeClient] = client {
                copy(
                    homeSnapshot = { snapshot },
                    homeAttention = { _, _, _ ->
                        attentionCalls.increment()
                        setOf(openSession.id)
                    },
                    events = { flow { awaitCancellation() } },
                )
            }
        }
        store.exhaustive = false

        store.send(HomeFeature.Action.NetworkRerouted)
        assertFalse(store.state.isEventStreamLive)
        assertEquals(HomeFeature.State.NoticeReadiness.unsettled, store.state.noticeReadiness)

        settle()
        clock.advance(250.milliseconds)
        store.receive(HomeFeature.Action.SnapshotRefreshed::class)
        clock.advance(150.milliseconds)
        store.receive(HomeFeature.Action.AttentionRefreshed::class)
        assertEquals(1, attentionCalls.value)
        assertEquals(setOf(openSession.id), store.state.attentionSessionIDs)
        harness.skipInFlightEffects()
    }

    @Test
    fun shouldForwardFamilyRunStateAndTranscriptSubagentsFiltering() {
        val openRoot = session("ses_open", "Open", projectID = "p", directory = "/p")
        val knownChild = session("ses_open_child", "Child", projectID = "p", directory = "/p", parentID = openRoot.id)
        val foreignRoot = session("ses_foreign", "Foreign", projectID = "p", directory = "/p")
        val referencedSubagentTool = OpenCodeTranscriptItem.Tool(
            id = "msg_1-tool-sub",
            call = OpenCodeToolCall(
                callID = "sub", messageID = "msg_1", name = "subagent", status = OpenCodeToolCall.Status.Running,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null,
                metadata = mapOf("sessionID" to OpenCodeJSONValue.Str("ses_unlisted_ref")),
            ),
        )

        val screen = SessionFeature.State.session(server = server, session = openRoot, cachedItems = listOf(referencedSubagentTool), isRunning = true).copy(
            familySessions = listOf(openRoot, knownChild),
            activeFamilySessionIDs = setOf(openRoot.id, knownChild.id, "ses_unlisted_ref", foreignRoot.id, "ses_foreign_child"),
        )
        val parents = mapOf(
            knownChild.id to openRoot.id,
            "ses_unlisted_descendant" to knownChild.id,
            "ses_foreign_child" to foreignRoot.id,
        )

        assertTrue(shouldForwardFamilyRunState(screen, openRoot.id, unattributed = false, parents = parents))
        assertTrue(shouldForwardFamilyRunState(screen, knownChild.id, unattributed = false, parents = parents))
        assertTrue(shouldForwardFamilyRunState(screen, "ses_unlisted_descendant", unattributed = false, parents = parents))
        assertTrue(shouldForwardFamilyRunState(screen, "ses_brand_new", unattributed = true, parents = parents))
        assertFalse(shouldForwardFamilyRunState(screen, foreignRoot.id, unattributed = false, parents = parents))
        assertFalse(shouldForwardFamilyRunState(screen, "ses_foreign_child", unattributed = false, parents = parents))

        val subagents = screen.transcriptSubagents
        assertEquals(true, subagents[knownChild.id]?.isKnown)
        assertEquals(false, subagents["ses_unlisted_ref"]?.isKnown)
        assertEquals(true, subagents["ses_unlisted_ref"]?.isRunning)
        assertNull(subagents[foreignRoot.id])
        assertNull(subagents["ses_foreign_child"])
    }
}
