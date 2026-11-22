package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.BrowserReplyStatus
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkMenuText
import ai.opencode.feature.media.LinkProbe
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.media.PageReaderClient
import ai.opencode.feature.media.UniversalLinkClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePageContext
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PageShareRequest
import ai.opencode.server.model.PromptContext
import ai.opencode.feature.composer.PendingPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/*
 * All of `BrowserObscuredInsetsTests`, and the `BrowserWebViews.Delegate.contextMenuConfiguration`
 * tail of `LinkMenuTests.holdingALinkOffersSafariCopyAndShare`.
 */

private val browserServer = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
private val chat = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

private fun browser(action: BrowserFeature.Action): Action = Action.Browser(action)

private val receivedBrowserOpen: (Action) -> Boolean = { (it as? Action.Browser)?.action is BrowserFeature.Action.Open }

class BrowserSendTests {
    private val link = "https://github.com/anomalyco/opencode/pull/53712"

    private fun Harness.state(draft: String = ""): SessionFeature.State =
        state { SessionFeature.State.session(server = browserServer, session = chat).withDraft(draft) }

    @Test fun askingFromThePageCarriesItAndItsReplyShowsInTheCapsule() = sessionTest { h ->
        data class Sent(val text: String, val page: OpenCodePageContext?, val attachments: List<OpenCodeOutgoingAttachment>)
        val sent = LockIsolated<Sent?>(null)
        val reads = LockIsolated(listOf<PageShareRequest>())
        val store = h.store(h.state()) {
            this[UuidClient] = UuidClient.incrementing()
            this[PageReaderClient] = PageReaderClient(
                read = { request ->
                    reads.append(request)
                    OpenCodePageContext(url = request.url, title = "Accept page context", selection = null, visibleText = "Prompts accept metadata.page.", scroll = 0.5)
                },
                goBack = {}, goForward = {}, scrollToTop = {}, openExternal = PageReaderClient.testValue.openExternal, discard = {},
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, text, attachments, _, _, _ -> sent.setValue(Sent(text, PromptContext.page.current(), attachments)) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> chat },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(browser(BrowserFeature.Action.Open(link)))
        store.send(browser(BrowserFeature.Action.AskTapped))
        // Nothing typed: nothing goes.
        store.send(browser(BrowserFeature.Action.AskSent))
        assertNull(store.state.browser.peekMessageID)
        store.send(browser(BrowserFeature.Action.AskDraftChanged("  What does this change?  ")))
        store.send(browser(BrowserFeature.Action.AskSent))
        val messageID = "msg_00000000000100000000000000"
        assertEquals(messageID, store.state.browser.peekMessageID)
        assertEquals("Sending puts the composer away", false, store.state.browser.isAsking)
        assertTrue(store.state.browser.askDraft.isEmpty())
        assertTrue("The session's own draft is untouched", store.state.draft.isEmpty())
        assertEquals("Working until the reply lands", SessionFeature.State.BrowserPeek(text = null), store.state.browserPeek)
        store.receive(Action.PromptAdmitted::class)
        assertEquals("Read once, at send", 1, reads.value.size)
        assertEquals("The user's words stay the message's text", "What does this change?", sent.value?.text)
        assertEquals("Prompts accept metadata.page.", sent.value?.page?.visibleText)
        assertEquals(link, sent.value?.page?.url)
        assertEquals("No screenshot", true, sent.value?.attachments?.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun theCapsuleShowsTheNewestReplyToTheQuestionAskedFromThePage() = sessionTest { h ->
        var state = h.state().let {
            it.copy(
                browser = it.browser.copy(pageID = UUID(0, 0), peekMessageID = "msg_page"),
                isAwaitingReply = true,
                items = listOf(
                    OpenCodeTranscriptItem.UserText(id = "msg_before", text = "Earlier"),
                    OpenCodeTranscriptItem.AssistantMarkdown(id = "m1-0", text = "An older reply"),
                ),
            )
        }
        assertEquals("Not echoed yet: Working", SessionFeature.State.BrowserPeek(text = null), state.browserPeek)
        state = state.copy(items = state.items + OpenCodeTranscriptItem.UserText(id = "msg_page", text = "What does this change?"))
        assertEquals(SessionFeature.State.BrowserPeek(text = null), state.browserPeek)
        state = state.copy(items = state.items + OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-0", text = "It adds **metadata.page**."))
        assertEquals("It adds metadata.page.", state.browserPeek?.text)
        state = state.copy(browser = state.browser.copy(pageID = null))
        assertNull("Only on the open page", state.browserPeek)
    }

    @Test fun theSessionsOwnComposerNeverAttachesThePage() = sessionTest { h ->
        val pages = LockIsolated(listOf<OpenCodePageContext?>())
        val issues = LockIsolated(listOf<String>())
        val store = h.store(h.state(draft = "First")) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[PageReaderClient] = PageReaderClient(
                read = {
                    issues.append("The page must not be read")
                    OpenCodePageContext(url = "https://x.test", title = "")
                },
                goBack = {}, goForward = {}, scrollToTop = {}, openExternal = PageReaderClient.testValue.openExternal, discard = {},
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> pages.append(PromptContext.page.current()) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> chat },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(browser(BrowserFeature.Action.Open(link)))
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertNull(store.state.browser.peekMessageID)
        assertEquals(listOf<OpenCodePageContext?>(null), pages.value)
        assertTrue(issues.value.isEmpty())
        h.skipInFlightEffects()
    }
}

class LinkMenuTests {
    @Test fun holdingALinkOffersSafariCopyAndShare() = sessionTest { h ->
        val opened = LockIsolated(listOf<String>())
        val copied = LockIsolated(listOf<String>())
        val played = LockIsolated(listOf<Haptic>())
        val url = "https://example.com/post"
        val state = h.state { SessionFeature.State.session(server = browserServer, session = chat) }
        val store = h.store(state) {
            this[OpenURLClient] = OpenURLClient(open = { opened.append(it); true })
            this[PasteboardClient] = PasteboardClient(setURL = { copied.append(it) })
            this[HapticsClient] = HapticsClient(play = { played.append(it) })
        }
        assertEquals(
            listOf(
                LinkMenuAction.OpenInAppBrowser, LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy,
                LinkMenuAction.Share, LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID,
            ),
            LinkMenuAction.entries.toList(),
        )
        assertEquals(
            "Public links offer Open in Safari, Open in App, Copy Link, and Share",
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(url, ExperimentalFeatures.testValue),
        )
        store.send(Action.LinkMenu(LinkMenuAction.OpenInSafari, url))
        store.send(Action.LinkMenu(LinkMenuAction.Copy, url))
        store.send(Action.LinkMenu(LinkMenuAction.Share, url))
        store.finish()
        assertEquals(listOf(url), opened.value)
        assertEquals(listOf(url), copied.value)
        assertEquals(listOf(Haptic.selection, Haptic.tap, Haptic.selection), played.value)

        // Pairing URLs never offer Safari or the in-app browser, and copy/share the custom-scheme deep link.
        val pairingURL = "https://mini-trial.taila8e7c7.ts.net:4196/auth/connect/Ab3_x-9Q"
        val expectedShareURL = "opencode-ios://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196"
        val browserOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
        assertTrue(LinkMenuAction.offers(pairingURL))
        assertTrue(LinkMenuAction.offers(expectedShareURL))
        assertEquals(listOf(LinkMenuAction.Copy, LinkMenuAction.Share), LinkMenuAction.actions(pairingURL, browserOn))
        assertEquals(listOf(LinkMenuAction.Copy, LinkMenuAction.Share), LinkMenuAction.actions(expectedShareURL, browserOn))
        assertEquals(expectedShareURL, OpenCodePairingLink.shareableURL(pairingURL))

        val malformedPairingURL = "https://mini-trial.taila8e7c7.ts.net:4196/auth/connect/Ab3_x-9Q?extra=1"
        assertFalse(LinkMenuAction.offers(malformedPairingURL))
        assertTrue(LinkMenuAction.actions(malformedPairingURL, browserOn).isEmpty())

        store.send(Action.LinkMenu(LinkMenuAction.OpenInSafari, pairingURL))
        store.send(Action.LinkMenu(LinkMenuAction.Copy, pairingURL))
        store.send(browser(BrowserFeature.Action.LinkMenu(LinkMenuAction.Copy, expectedShareURL)))
        store.finish()
        assertEquals("Open in Safari must not open a single-use pairing URL", listOf(url), opened.value)
        assertEquals(
            "Copy Link in transcript or in-app browser copies the non-fetching opencode-ios://pair link",
            listOf(url, expectedShareURL, expectedShareURL),
            copied.value,
        )
    }
}

/**
 * Open Links in Apps: a tap offers a public link to the app that claims it
 * as a universal link first, and falls back to the in-app browser or Safari.
 */
class OpenLinksInAppsTests {
    private val link = "https://github.com/anomalyco/opencode/pull/1"
    private val other = "https://youtube.com/watch?v=abc"
    private val issues = LockIsolated(listOf<String>())

    private fun Harness.store(
        experiments: Set<ExperimentalFeature>,
        probed: LockIsolated<List<String>>,
        opened: LockIsolated<List<String>>,
        appClaims: Boolean,
    ) = store(experiments, opened) { url ->
        probed.append(url)
        appClaims
    }

    private fun Harness.store(
        experiments: Set<ExperimentalFeature>,
        opened: LockIsolated<List<String>>,
        waiting: LinkProbe? = null,
        covered: Boolean = false,
        probe: suspend (String) -> Boolean,
    ): TestStore<SessionFeature.State, Action> {
        val state = state { SessionFeature.State.session(server = browserServer, session = chat) }.copy(linkProbe = waiting, isCovered = covered)
        val store = store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(experiments)
            this[UniversalLinkClient] = UniversalLinkClient(open = probe)
            this[OpenURLClient] = OpenURLClient(open = { opened.append(it); true })
            this[HapticsClient] = HapticsClient(play = { issues.append("Opening a link is navigation, so quiet: $it") })
        }
        store.exhaustive = false
        return store
    }

    private fun linkTest(block: suspend kotlinx.coroutines.CoroutineScope.(Harness) -> Unit) = sessionTest { h ->
        block(h)
        assertEquals(emptyList<String>(), issues.value)
    }

    @Test fun anAppThatClaimsTheLinkOpensItAndNothingElseDoes() = linkTest { h ->
        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), probed, opened, appClaims = true)
        store.send(Action.LinkTapped(link))
        store.receive(Action.UniversalLinkResponse::class)
        store.finish()
        assertEquals(listOf(link), probed.value)
        assertTrue(opened.value.isEmpty())
        assertFalse(store.state.browser.isOpen)
    }

    @Test fun withNoAppTheInAppBrowserOpensIt() = linkTest { h ->
        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), probed, opened, appClaims = false)
        store.send(Action.LinkTapped(link))
        store.receive(Action.UniversalLinkResponse::class)
        store.receive(receivedBrowserOpen, "browser.open") {
            it.copy(browser = it.browser.copy(pageID = UUID(0, 1), openedURL = link, url = link, isLoading = true))
        }
        assertEquals(listOf(link), probed.value)
        assertTrue(opened.value.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun withNoAppAndTheBrowserOffItOpensInTheSystemAsToday() = linkTest { h ->
        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(emptySet(), probed, opened, appClaims = false)
        store.send(Action.LinkTapped(link))
        store.receive(Action.UniversalLinkResponse::class)
        store.finish()
        assertEquals(listOf(link), probed.value)
        assertEquals(listOf(link), opened.value)
        assertFalse(store.state.browser.isOpen)
    }

    @Test fun excludedLinksAreNeverProbed() = linkTest { h ->
        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), probed, opened, appClaims = true)
        val excluded = listOf(
            "http://localhost:4096/", "http://192.168.1.20/admin", "https://box.taila8e7c7.ts.net/",
            "https://user:secret@example.com/", "https://bucket.s3.amazonaws.com/file?X-Amz-Signature=abc",
            "https://example.com/download?token=abc", "mailto:kit@example.com",
        )
        for (url in excluded) store.send(Action.LinkTapped(url))
        store.finish()
        assertTrue(probed.value.isEmpty())
        assertEquals("They open as they always have", excluded, opened.value)
        assertFalse(store.state.browser.isOpen)
    }

    @Test fun openInAppBrowserSkipsTheApp() = linkTest { h ->
        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), probed, opened, appClaims = true)
        store.send(Action.LinkMenu(LinkMenuAction.OpenInAppBrowser, link))
        store.receive(receivedBrowserOpen, "browser.open") {
            it.copy(browser = it.browser.copy(pageID = UUID(0, 0), openedURL = link, url = link, isLoading = true))
        }
        assertTrue(probed.value.isEmpty())
        assertTrue(opened.value.isEmpty())
        // A private link never opens in the browser, from the menu either.
        store.send(Action.LinkMenu(LinkMenuAction.OpenInAppBrowser, "http://192.168.1.20/"))
        h.skipInFlightEffects()
    }

    // region Answers that arrive late

    @Test fun openInAppBrowserForAnotherLinkCancelsAWaitingTap() = linkTest { h ->
        val gate = LinkProbeGate()
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), opened) { gate.open(it) }
        store.send(Action.LinkTapped(link))
        gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.LinkMenu(LinkMenuAction.OpenInAppBrowser, other))
        gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        store.receive(receivedBrowserOpen, "browser.open")
        assertNull(store.state.linkProbe)

        // Even an answer that got through would be stale.
        store.send(Action.UniversalLinkResponse(id = UUID(0, 0), openedInApp = false))
        store.finish()
        assertEquals("The page the reader chose stays", other, store.state.browser.url)
        assertTrue(opened.value.isEmpty())
    }

    @Test fun aLaterTapSupersedesAWaitingOne() = linkTest { h ->
        val gate = LinkProbeGate()
        val opened = LockIsolated(listOf<String>())
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), opened) { gate.open(it) }
        store.send(Action.LinkTapped(link))
        gate.expect(LinkProbeGate.Event.Asked(link, 1))
        store.send(Action.LinkTapped(other))
        gate.expect(LinkProbeGate.Event.Cancelled(link, 1))
        gate.expect(LinkProbeGate.Event.Asked(other, 1))
        store.send(Action.UniversalLinkResponse(id = UUID(0, 0), openedInApp = false))
        assertFalse("The first tap's answer opens nothing", store.state.browser.isOpen)

        gate.answer(other, 1, openedInApp = false)
        store.receive(Action.UniversalLinkResponse::class)
        store.receive(receivedBrowserOpen, "browser.open")
        store.finish()
        assertEquals(other, store.state.browser.url)
        assertTrue(opened.value.isEmpty())
    }

    @Test fun anAnswerForAnotherSessionOpensNothing() = linkTest { h ->
        val opened = LockIsolated(listOf<String>())
        // A tap from another session, answered on this one.
        val waiting = LinkProbe(id = UUID(0, 9), url = link, sessionID = "ses_elsewhere")
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), opened, waiting = waiting) { false }
        store.send(Action.UniversalLinkResponse(id = UUID(0, 9), openedInApp = false))
        store.finish()
        assertFalse(store.state.browser.isOpen)
        assertTrue(opened.value.isEmpty())
    }

    @Test fun aCoveredScreenRejectsItsAnswer() = linkTest { h ->
        val opened = LockIsolated(listOf<String>())
        val waiting = LinkProbe(id = UUID(0, 9), url = link, sessionID = "ses_1")
        val store = h.store(setOf(ExperimentalFeature.inAppBrowser), opened, waiting = waiting, covered = true) { false }
        store.send(Action.UniversalLinkResponse(id = UUID(0, 9), openedInApp = false))
        store.finish()
        assertFalse("Never a hidden page under the subagent", store.state.browser.isOpen)
        assertTrue("Nor Safari over it", opened.value.isEmpty())
    }

    @Test fun theMenuOffersTheAppBrowserOnlyWhenATapMightSkipIt() = linkTest { h ->
        val browserOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))
        assertEquals(
            listOf(LinkMenuAction.OpenInAppBrowser, LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(link, browserOn),
        )
        assertEquals(
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.OpenInApp, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions(link, ExperimentalFeatures.enabling(emptySet())),
        )
        assertEquals(
            listOf(LinkMenuAction.OpenInSafari, LinkMenuAction.Copy, LinkMenuAction.Share),
            LinkMenuAction.actions("http://192.168.1.20/", browserOn),
        )
        assertEquals("Open in App Browser", LinkMenuText.accessibilityName(LinkMenuAction.OpenInAppBrowser, link, listOf(link)))
        assertEquals("Open in App", LinkMenuText.accessibilityName(LinkMenuAction.OpenInApp, link, listOf(link)))
        assertEquals("Open github.com in App", LinkMenuText.accessibilityName(LinkMenuAction.OpenInApp, link, listOf(link, other)))

        val probed = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val store = h.store(emptySet(), probed, opened, appClaims = true)
        store.send(Action.LinkMenu(LinkMenuAction.OpenInApp, link)) {
            it.copy(linkProbe = LinkProbe(id = UUID(0, 0), url = link, sessionID = "ses_1"))
        }
        store.receive(Action.UniversalLinkResponse::class) { it.copy(linkProbe = null) }
        store.finish()
        assertEquals(listOf(link), probed.value)
        assertTrue(opened.value.isEmpty())
    }

    // endregion
}

class BrowserLifetimeTests {
    private val server = OpenCodeServer(url = "https://switch.example", username = "", password = "")
    private val link = "https://example.com/article"
    private fun session(id: String, parent: String? = null) =
        OpenCodeSession(id = id, serverTitle = id, projectID = "project", directory = "/project", updatedAt = Instant.ofEpochSecond(10), parentID = parent)

    @Test fun aSubagentNeverOpensUnderItsParentsPage() = sessionTest { h ->
        val discarded = LockIsolated(listOf<UUID>())
        val state = h.state { SessionFeature.State.session(server = server, session = session("root")) }
            .copy(familySessions = listOf(session("root"), session("child", parent = "root")))
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[PageReaderClient] = PageReaderClient.testValue.let {
                PageReaderClient(
                    read = it.read,
                    goBack = it.goBack,
                    goForward = it.goForward,
                    scrollToTop = it.scrollToTop,
                    openExternal = it.openExternal,
                    discard = { id -> discarded.append(id) },
                )
            }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> session(id, parent = "root") },
                transcript = { _, _, _ -> awaitCancellation() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(browser(BrowserFeature.Action.Open(link)))
        store.send(Action.FamilyPicked("child"))
        assertEquals("child", store.state.session?.id)
        assertEquals(BrowserFeature.State(), store.state.browser)
        repeat(100) { if (discarded.value.isEmpty()) kotlinx.coroutines.yield() }
        assertEquals(listOf(UUID(0, 0)), discarded.value)
        h.skipInFlightEffects()
    }
}

class BrowserVoiceTests {
    private val server = OpenCodeServer(url = "https://voice.example", username = "", password = "")
    private val a = OpenCodeSession(id = "ses_a", serverTitle = "A", projectID = "p", directory = "/project", updatedAt = SessionTestSupport.distantPast)
    private val pageURL = "https://example.com/doc"

    @Test fun aCallStartedOverAPageKnowsIt() = sessionTest { h ->
        val store = h.store(h.state { SessionFeature.State.session(server = server, session = a) }) {
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(browser(BrowserFeature.Action.Open(pageURL)))
        store.send(Action.VoiceTapped)
        assertEquals(store.state.browser.viewing, store.state.voice?.viewing)
        assertEquals(pageURL, store.state.voice?.viewing?.url)
        h.skipInFlightEffects()
    }
}

class BrowserPeekEndTests {
    private fun Harness.state(): SessionFeature.State = state { SessionFeature.State.session(server = browserServer, session = chat) }.let {
        it.copy(
            browser = it.browser.copy(pageID = UUID(0, 0), peekMessageID = "msg_page"),
            items = listOf(OpenCodeTranscriptItem.UserText(id = "msg_page", text = "What does this change?")),
        )
    }

    @Test fun thePeekNeverSticksOnWorking() = sessionTest { h ->
        val running = h.state().copy(isAwaitingReply = true)
        assertEquals(SessionFeature.State.BrowserPeek(text = null), running.browserPeek)

        // A turn that ended (or was stopped) without prose.
        val ended = h.state().let {
            it.copy(
                items = it.items + OpenCodeTranscriptItem.Tool(
                    id = "m2-tool",
                    call = OpenCodeToolCall(
                        callID = "c", name = "shell", status = OpenCodeToolCall.Status.Completed,
                        input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
                    ),
                ),
            )
        }
        assertNull(ended.browserPeek)

        val failed = running.copy(
            failedSends = running.failedSends + FailedSend(
                pending = PendingPrompt(messageID = "msg_page", text = "x", attachments = emptyList(), requestEpoch = 1),
                reason = "offline",
            ),
        )
        assertNull(failed.browserPeek)

        val cancelled = running.copy(cancelledInputIDs = running.cancelledInputIDs + "msg_page")
        assertNull(cancelled.browserPeek)

        val queued = h.state().copy(items = emptyList(), queuedInputs = listOf(OpenCodePendingInput(id = "msg_page", text = "x", delivery = OpenCodeDelivery.Queue)))
        assertEquals("Waiting in the queue", SessionFeature.State.BrowserPeek(text = null), queued.browserPeek)
    }

    @Test fun replyStatusReflectsWorkingNeedsInputAndReady() = sessionTest { h ->
        var current = h.state().copy(isAwaitingReply = true)
        assertEquals(BrowserReplyStatus.Working, current.browserReplyStatus)
        assertEquals(false, current.browserReplyStatus?.goesBack)

        current = current.updateRequestDock { dock ->
            dock.copy(
                permissionRequests = listOf(
                    OpenCodePermissionRequest(id = "per_1", sessionID = "ses_1", action = "shell", resources = listOf("ls"), save = null),
                ),
            )
        }
        assertEquals(BrowserReplyStatus.NeedsInput, current.browserReplyStatus)
        assertEquals(true, current.browserReplyStatus?.goesBack)

        current = current.copy(items = current.items + OpenCodeTranscriptItem.AssistantMarkdown(id = "m2-0", text = "Done."))
        assertEquals(BrowserReplyStatus.Ready, current.browserReplyStatus)
        assertEquals(true, current.browserReplyStatus?.goesBack)

        current = current.copy(browser = current.browser.close())
        assertNull(current.browserReplyStatus)
    }
}

/**
 * A universal-link check the test answers when it chooses. Each check is its own request, numbered
 * per link from 1, and says when it was asked or cancelled; waiting for either fails after 5 s.
 */
private class LinkProbeGate {
    sealed interface Event {
        data class Asked(val url: String, val number: Int) : Event
        data class Cancelled(val url: String, val number: Int) : Event
    }

    private data class Request(val url: String, val number: Int)

    private val lock = Any()
    private val counts = HashMap<String, Int>()
    private val answers = HashMap<Request, CompletableDeferred<Boolean>>()
    private val happened = HashMap<Event, CompletableDeferred<Unit>>()

    private fun event(event: Event): CompletableDeferred<Unit> = synchronized(lock) { happened.getOrPut(event) { CompletableDeferred() } }

    suspend fun open(url: String): Boolean {
        val (request, answer) = synchronized(lock) {
            val number = (counts[url] ?: 0) + 1
            counts[url] = number
            val request = Request(url, number)
            val answer = CompletableDeferred<Boolean>()
            answers[request] = answer
            request to answer
        }
        event(Event.Asked(url, request.number)).complete(Unit)
        return try {
            answer.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) { answers.remove(request) }
            event(Event.Cancelled(url, request.number)).complete(Unit)
            throw cancelled
        }
    }

    fun answer(url: String, number: Int, openedInApp: Boolean) {
        val answer = synchronized(lock) { answers.remove(Request(url, number)) }
        assertTrue("Check $number of $url isn't waiting", answer != null)
        answer?.complete(openedInApp)
    }

    suspend fun expect(event: Event) {
        val happened = withTimeoutOrNull(5.seconds) { event(event).await() } != null
        assertTrue("Timed out waiting for $event", happened)
    }
}
