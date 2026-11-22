package ai.opencode.feature.transcript

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.transcript.list.TranscriptList
import ai.opencode.feature.transcript.list.TranscriptSlots
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * The transcript bound to its store: [TranscriptList] drawing [TranscriptFeature.State.configuration].
 * [contentPadding] is the header above and the composer below; [slots] carries the session's
 * Working line.
 */
@Composable
fun TranscriptView(
    store: StoreOf<TranscriptFeature.State, TranscriptFeature.Action>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    slots: TranscriptSlots = TranscriptSlots(),
) {
    LaunchedEffect(store) { store.send(TranscriptFeature.Action.Task) }
    val configuration by store.observe { it.configuration }
    val jumpRequest by store.observe { it.jumpRequest }
    TranscriptList(
        configuration = configuration,
        send = { store.send(TranscriptFeature.Action.Delegate(it)) },
        modifier = modifier,
        contentPadding = contentPadding,
        jumpRequest = jumpRequest,
        onShowsJumpButtonChanged = { store.send(TranscriptFeature.Action.ShowsJumpButtonChanged(it)) },
        slots = slots,
    )
}
