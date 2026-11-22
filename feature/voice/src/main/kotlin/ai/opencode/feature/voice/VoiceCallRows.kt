package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Android shows them in a Material card standing on the composer, which expands and shrinks with
 * the rows (`AnimatedVisibility`), so the glass room maths isn't ported.
 */

/** What the call has up above the composer right now. */
data class VoiceCallRows(
    val screen: ClientToolCall? = null,
    val link: ClientToolCall? = null,
    /** Audio on the call: its row sits under any request. */
    val track: CallAudioTrack? = null,
) {
    /** The request on its row: one at a time, a screen request first. */
    val request: ClientToolCall? get() = screen ?: link

    val isEmpty: Boolean get() = request == null && track == null

    /** Whichever rows [live] has replace these; the rest stay, still drawn while they leave. */
    fun take(live: VoiceCallRows): VoiceCallRows {
        var next = this
        if (live.request != null) next = next.copy(screen = live.screen, link = live.link)
        if (live.track != null) next = next.copy(track = live.track)
        return next
    }

    /** The rows [missingFrom] no longer has. */
    fun drop(missingFrom: VoiceCallRows): VoiceCallRows {
        var next = this
        if (missingFrom.request == null) next = next.copy(screen = null, link = null)
        if (missingFrom.track == null) next = next.copy(track = null)
        return next
    }

    companion object {
        /** A call's rows; an ended call has none. */
        fun from(state: VoiceFeature.State?): VoiceCallRows {
            if (state == null || state.phase.isTerminal) return VoiceCallRows()
            val screen = state.screenRequest.pending
            val link = if (screen == null) state.openLink.pending else null
            return VoiceCallRows(screen = screen, link = link, track = state.callAudio.track)
        }
    }
}

/**
 * The call's rows above the composer: a screen request or a link to open, and audio playing on
 * the call. [hidden] folds them away (the call bar's captions, or a docked request's panel).
 */
@Composable
fun VoiceCallRowsCard(store: VoiceStoreOf?, hidden: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val state = store.watch { VoiceCallRowsSnapshot(VoiceCallRows.from(it), it.callAudio.isPaused) }
    val live = if (hidden || state == null) VoiceCallRows() else state.rows
    val isPaused = state?.isPaused == true
    // A row stays drawn while it leaves: the holder keeps the last rows, and isn't state, so
    // updating it while composing doesn't recompose.
    val holder = remember { ShownRows() }
    holder.rows = holder.rows.take(live)
    val shown = holder.rows
    val send: (VoiceFeature.Action) -> Unit = { action -> store?.send(action) }
    val enter = if (reduceMotion) fadeIn(Motion.reducedFadeSpec()) else expandVertically(expandFrom = Alignment.Bottom) + fadeIn()
    val exit = if (reduceMotion) fadeOut(Motion.reducedFadeSpec()) else shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
    AnimatedVisibility(visible = !live.isEmpty, modifier = modifier, enter = enter, exit = exit) {
        Surface(
            shape = MaterialTheme.shapes.large,
            // The desktop card: floating fill, hairline ring.
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                AnimatedVisibility(visible = live.request != null, enter = enter, exit = exit) {
                    AnimatedContent(
                        targetState = shown.request,
                        contentKey = { it?.id },
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "callRequest",
                    ) { request ->
                        if (request == null) return@AnimatedContent
                        if (request.tool == ClientTool.voiceRequestScreen) {
                            ScreenRequestRow(request, send)
                        } else {
                            OpenLinkRow(request, send)
                        }
                    }
                }
                val track = shown.track
                AnimatedVisibility(visible = live.track != null, enter = enter, exit = exit) {
                    if (track == null) return@AnimatedVisibility
                    Column {
                        if (live.request != null) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        CallAudioRow(track, isPaused, send)
                    }
                }
            }
        }
    }
}

private data class VoiceCallRowsSnapshot(val rows: VoiceCallRows, val isPaused: Boolean)

private class ShownRows {
    var rows = VoiceCallRows()
}

/** “Share Screen?”, its reason, ✕ and Share. */
@Composable
private fun ScreenRequestRow(request: ClientToolCall, send: (VoiceFeature.Action) -> Unit) {
    val reason = request.reason
    val detail = reason ?: stringResource(R.string.voice_screen_request_detail)
    val spoken = if (reason != null) stringResource(R.string.voice_screen_request_spoken_with_reason, reason) else stringResource(R.string.voice_screen_request_spoken)
    CallRow(
        symbol = SFSymbol.RectangleOnRectangle,
        title = stringResource(R.string.voice_screen_request_title),
        detail = detail,
        detailLines = 2,
        spoken = spoken,
        tag = "voice-screen-request",
    ) {
        OpenCodeIconButton(
            SFSymbol.Xmark,
            stringResource(CoreR.string.common_not_now),
            onClick = { send(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.NotNowTapped)) },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.semantics { testTag = "voice-screen-request-not-now" },
        )
        Button(
            onClick = { send(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ShareTapped)) },
            modifier = Modifier.semantics { testTag = "voice-screen-request-share" },
            shape = OpenCodeShape.control,
        ) { Text(stringResource(CoreR.string.common_share)) }
    }
}

/** “Open Uber?”, its host, ✕ and Open. */
@Composable
private fun OpenLinkRow(request: ClientToolCall, send: (VoiceFeature.Action) -> Unit) {
    val title = VoiceOpenLinkFeature.cardTitle(request.title)
    val link = VoiceOpenLinkFeature.link(request)
    val host = if (link is VoiceOpenLinkFeature.Link.Valid) VoiceOpenLinkFeature.displayHost(link.url) else null
    val spoken = if (host != null) stringResource(R.string.voice_open_link_spoken_with_host, title, host) else stringResource(R.string.voice_open_link_spoken, title)
    CallRow(
        symbol = SFSymbol.ArrowUpForwardApp,
        title = title,
        detail = host,
        detailLines = 1,
        spoken = spoken,
        tag = "voice-open-link",
    ) {
        OpenCodeIconButton(
            SFSymbol.Xmark,
            stringResource(CoreR.string.common_not_now),
            onClick = { send(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.NotNowTapped)) },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.semantics { testTag = "voice-open-link-not-now" },
        )
        Button(
            onClick = { send(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.OpenTapped)) },
            modifier = Modifier.semantics { testTag = "voice-open-link-open" },
            shape = OpenCodeShape.control,
        ) { Text(stringResource(R.string.voice_open_link_open)) }
    }
}

/** The track's title, then Pause (or Play) and Stop. */
@Composable
private fun CallAudioRow(track: CallAudioTrack, isPaused: Boolean, send: (VoiceFeature.Action) -> Unit) {
    CallRow(
        symbol = SFSymbol.MusicNote,
        title = track.title,
        detail = if (isPaused) stringResource(R.string.voice_audio_paused) else stringResource(R.string.voice_audio_playing),
        detailLines = 1,
        spoken = null,
        tag = "voice-audio-row",
    ) {
        val toggle = if (isPaused) VoiceCallAudioFeature.Action.ResumeTapped else VoiceCallAudioFeature.Action.PauseTapped
        OpenCodeIconButton(
            if (isPaused) SFSymbol.PlayFill else SFSymbol.PauseFill,
            if (isPaused) stringResource(R.string.voice_audio_play) else stringResource(R.string.voice_audio_pause),
            onClick = { send(VoiceFeature.Action.CallAudio(toggle)) },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.semantics { testTag = "voice-audio-pause" },
        )
        OpenCodeIconButton(
            SFSymbol.StopFill,
            stringResource(R.string.voice_audio_stop),
            onClick = { send(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.StopTapped)) },
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.semantics { testTag = "voice-audio-stop" },
        )
    }
}

/** An icon, a title over its detail, then the row's buttons. */
@Composable
private fun CallRow(
    symbol: SFSymbol,
    title: String,
    detail: String?,
    detailLines: Int,
    spoken: String?,
    tag: String,
    actions: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics { testTag = tag },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(
            Modifier
                .weight(1f)
                .then(if (spoken != null) Modifier.semantics(mergeDescendants = true) { contentDescription = spoken } else Modifier.semantics(mergeDescendants = true) { }),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = detailLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}
