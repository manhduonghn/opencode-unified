package ai.opencode.feature.system.share

import ai.opencode.core.R as CoreR
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.motionPressEffect
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.system.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.net.URI
import androidx.compose.material3.LocalContentColor

/** What the share sheet's controls do ([ShareActivity] wires them). */
class ShareActions(
    val onMessageChange: (String) -> Unit,
    val onChoose: (ShareComposition.Destination) -> Unit,
    val onCancel: () -> Unit,
    val onSend: () -> Unit,
    val onSendLater: () -> Unit,
    val onSaveToDownloads: () -> Unit,
    val onContinue: () -> Unit,
    val onDone: () -> Unit,
    val onDismissSaveError: () -> Unit,
)

/**
 * The share sheet as a composer: what was shared, large, at the top; the sessions as chips right
 * above an app-style composer; Send in the composer and Continue in OpenCode beside the title. Wrap
 * in `OpenCodeAppTheme {}`.
 */
@Composable
fun ShareView(state: ShareModel.State, actions: ShareActions) {
    val reduceMotion = rememberReduceMotion()
    var showsSessionSearch by rememberSaveable { mutableStateOf(false) }
    val colors = OpenCodeTheme.colors

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp)
                // A swipe down cancels, as the system sheet's does; from Send until it closes the
                // sheet is the send's to end, so it springs back.
                .swipeDownToDismiss(enabled = !state.isBusy, reduceMotion = reduceMotion, onDismiss = actions.onCancel),
            shape = OpenCodeShape.sheetTop,
            color = colors.canvas,
            contentColor = colors.text,
        ) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                TopBar(state, actions)
                if (state.targets == null) {
                    EmptyState(Modifier.weight(1f))
                } else {
                    Composer(state, actions, reduceMotion, Modifier.weight(1f)) {
                        if (!state.isBusy) showsSessionSearch = true
                    }
                }
            }
        }
    }

    state.saveError?.let { error ->
        OpenCodeAlertDialog(
            onDismissRequest = actions.onDismissSaveError,
            title = { Text(stringResource(R.string.system_share_save_failed_title)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = actions.onDismissSaveError) { Text(stringResource(CoreR.string.common_ok)) } },
        )
    }

    if (showsSessionSearch) {
        ShareSessionSearch(state, onChoose = actions.onChoose) { showsSessionSearch = false }
    }
}

/**
 * The sheet follows a downward drag (on itself, or past the top of what scrolls inside it), and
 * goes when let go past half its height or flung down; anything less springs back. Dismissal runs
 * [onDismiss] once the sheet has slid away.
 */
@Composable
private fun Modifier.swipeDownToDismiss(enabled: Boolean, reduceMotion: Boolean, onDismiss: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var offset by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val flingVelocity = with(density) { SWIPE_DISMISS_VELOCITY.toPx() }
    // The dimming behind the sheet fades with how far it has been pulled down, so nothing pops when
    // it closes.
    val window = androidx.activity.compose.LocalActivity.current?.window
    androidx.compose.runtime.LaunchedEffect(window) {
        if (window == null) return@LaunchedEffect
        val dim = window.attributes.dimAmount
        androidx.compose.runtime.snapshotFlow { if (height > 0) (offset / height).coerceIn(0f, 1f) else 0f }
            .collect { pulled -> window.setDimAmount(dim * (1f - pulled)) }
    }

    fun drag(delta: Float): Float {
        if (dismissed) return 0f
        settling?.cancel()
        val next = (offset + delta).coerceAtLeast(0f)
        val used = next - offset
        offset = next
        return used
    }

    fun settle(velocity: Float) {
        if (dismissed) return
        settling?.cancel()
        val goes = currentEnabled && offset > 0f && (offset > height / 2f || velocity > flingVelocity)
        if (goes) {
            dismissed = true
            // As a committed sheet dismissal: the keyboard goes down alongside the sheet.
            keyboard?.hide()
        }
        settling = scope.launch {
            val target = if (goes) height.toFloat() else 0f
            val spec = if (goes) Motion.morphClose.animation<Float>(reduceMotion) else Motion.settle.animation(reduceMotion)
            animate(offset, target, initialVelocity = velocity, animationSpec = spec) { value, _ -> offset = value }
            if (goes) currentDismiss()
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            // Raised sheet: an upward drag lowers it back before the content scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && offset > 0f && available.y < 0f) Offset(0f, drag(available.y)) else Offset.Zero

            // Content at its top: the rest of a downward drag pulls the sheet.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && currentEnabled && available.y > 0f) Offset(0f, drag(available.y)) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    val draggableState = rememberDraggableState { delta -> if (currentEnabled || delta < 0f) drag(delta) }
    // The drag is read outside the offset, in coordinates that don't move with the sheet.
    return this
        .onSizeChanged { height = it.height }
        .draggable(draggableState, Orientation.Vertical, onDragStopped = { velocity -> settle(velocity) })
        .nestedScroll(connection)
        .offset { IntOffset(0, offset.roundToInt()) }
}

private val SWIPE_DISMISS_VELOCITY = 1000.dp

@Composable
private fun TopBar(state: ShareModel.State, actions: ShareActions) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        // Inert, not dimmed, from Send until the sheet closes.
        OpenCodeIconButton(
            symbol = SFSymbol.Xmark,
            contentDescription = stringResource(CoreR.string.common_cancel),
            onClick = { if (!state.isBusy) actions.onCancel() },
            modifier = Modifier.align(Alignment.CenterStart),
            style = OpenCodeIconButtonStyle.Standard,
        )
        Text(
            "OpenCode",
            style = OpenCodeTextStyles.headline,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIconButton(
                symbol = SFSymbol.SquareAndArrowDown,
                contentDescription = stringResource(R.string.oc_share_save_downloads_description),
                onClick = actions.onSaveToDownloads,
                style = OpenCodeIconButtonStyle.Standard,
                enabled = state.canSaveToDownloads,
            )
            if (state.targets != null) {
                OpenCodeIconButton(
                    symbol = SFSymbol.ArrowUpForwardApp,
                    contentDescription = stringResource(R.string.system_share_continue_description),
                    onClick = { if (!state.isBusy) actions.onContinue() },
                    style = OpenCodeIconButtonStyle.Standard,
                    enabled = state.canContinue || state.isBusy,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    OpenCodeContentUnavailable(
        symbol = SFSymbol.Desktopcomputer,
        title = stringResource(R.string.system_share_no_server_title),
        modifier = modifier.fillMaxWidth(),
        description = stringResource(R.string.system_share_no_server_message),
    )
}

@Composable
private fun Composer(
    state: ShareModel.State,
    actions: ShareActions,
    reduceMotion: Boolean,
    modifier: Modifier,
    onSearch: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        ShareItemsPreview(
            state,
            Modifier.weight(1f).fillMaxWidth().sendingStage(state.phase, reduceMotion, rest = 0.85f),
        )
        Status(state, actions, reduceMotion)
        ShareTargetChips(
            state,
            reduceMotion,
            onChoose = actions.onChoose,
            onSearch = onSearch,
            modifier = Modifier.padding(bottom = 10.dp).sendingStage(state.phase, reduceMotion, rest = 0.5f),
        )
        MessageBar(state, actions, reduceMotion, Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
    }
}

/** The failure row comes and goes without the card above jumping. */
@Composable
private fun Status(state: ShareModel.State, actions: ShareActions, reduceMotion: Boolean) {
    val phase = state.phase
    val visible = phase is ShareModel.Phase.Failed || phase == ShareModel.Phase.ContinueManually
    // The last row shown, kept while it fades out.
    val shown = remember { arrayOf(phase) }
    if (visible) shown[0] = phase
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(phaseAnimation(reduceMotion)),
        exit = fadeOut(phaseAnimation(reduceMotion)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val current = shown[0]) {
                is ShareModel.Phase.Failed -> {
                    Text(current.problem.message, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = actions.onSendLater, shape = OpenCodeShape.control) { Text(stringResource(R.string.system_share_send_later)) }
                    if (current.problem.canRetry) Button(onClick = actions.onSend, shape = OpenCodeShape.control) { Text(stringResource(CoreR.string.common_try_again)) }
                }
                else -> {
                    Text(stringResource(R.string.system_share_saved_continue), style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Button(onClick = actions.onDone, shape = OpenCodeShape.control) { Text(stringResource(CoreR.string.common_done)) }
                }
            }
        }
    }
}

/** The sheet moving into its sending, sent or failed layout, on the layout role. */
private fun <T> phaseAnimation(reduceMotion: Boolean) = MotionRole.Layout.animation<T>(reduceMotion = reduceMotion)

/**
 * What sits above the bar while it sends: dimmed and locked in place, then lifted away (faded
 * only, under reduce motion) once it's sent. Only alpha and offset change, so nothing around it
 * moves.
 */
@Composable
private fun Modifier.sendingStage(phase: ShareModel.Phase, reduceMotion: Boolean, rest: Float): Modifier {
    val spec = MotionRole.MicroOut.animation<Float>(reduceMotion = reduceMotion)
    val alpha by animateFloatAsState(
        when (phase) {
            ShareModel.Phase.Sent -> 0f
            ShareModel.Phase.Sending -> rest
            else -> 1f
        },
        spec,
        label = "stageAlpha",
    )
    val lift by animateDpAsState(
        if (phase == ShareModel.Phase.Sent && !reduceMotion) (-14).dp else 0.dp,
        MotionRole.MicroOut.animation(reduceMotion = reduceMotion),
        label = "stageLift",
    )
    val density = LocalDensity.current
    val locked = phase == ShareModel.Phase.Sending || phase == ShareModel.Phase.Sent
    return this
        .graphicsLayer {
            this.alpha = alpha
            translationY = with(density) { lift.toPx() }
        }
        .blockInput(locked)
        .then(if (phase == ShareModel.Phase.Sent) Modifier.semantics { hideFromAccessibility() } else Modifier)
}

/** Takes no touches while [blocked], without dimming. */
private fun Modifier.blockInput(blocked: Boolean): Modifier = if (!blocked) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** What was shared: images large, then files, then shared text and links. */
@Composable
private fun ShareItemsPreview(state: ShareModel.State, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    BoxWithConstraints(modifier) {
        val extras = (state.files.size * 72 + state.sharedText.size * 80).dp
        val imageHeight = maxOf(160.dp, maxHeight - 40.dp - extras)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .heightIn(min = maxHeight - 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.isLoading && !state.hasContent) {
                val loadingDescription = stringResource(R.string.system_share_loading_description)
                CircularProgressIndicator(Modifier.semantics { contentDescription = loadingDescription })
            }
            Images(state.images, imageHeight)
            for (file in state.files) {
                Card(Modifier.semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OpenCodeIcon(
                            AttachmentFileType.symbolName(file.name, file.mime),
                            contentDescription = null,
                            size = OpenCodeIconSize.large,
                            tint = colors.secondaryText,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = OpenCodeTextStyles.body)
                            Text(
                                AttachmentFileType.subtitle(file.name, file.mime, file.size),
                                style = OpenCodeTextStyles.footnote,
                                color = colors.secondaryText,
                            )
                        }
                    }
                }
            }
            for (text in state.sharedText) {
                Card {
                    val link = remember(text) { SharedLink.parse(text) }
                    if (link != null) {
                        Row(
                            Modifier.semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OpenCodeIcon(SFSymbol.Link, contentDescription = null, size = OpenCodeIconSize.medium, tint = colors.secondaryText)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(link.host, maxLines = 1, overflow = TextOverflow.Ellipsis, style = OpenCodeTextStyles.body)
                                Text(
                                    link.address,
                                    style = OpenCodeTextStyles.footnote,
                                    color = colors.secondaryText,
                                    maxLines = 2,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    } else {
                        Text(
                            text,
                            style = OpenCodeTextStyles.callout,
                            color = colors.secondaryText,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            val skipped = skippedNote(state.skippedImageCount, state.skippedFileCount)
            if (skipped.isNotEmpty()) {
                Text(skipped, style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
            }
        }
    }
}

@Composable
private fun Images(images: List<ShareModel.SharedImage>, height: Dp) {
    if (images.size == 1) {
        val image = images.first()
        val bitmap = remember(image.id) { image.preview.asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.system_share_image_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = height).clip(RoundedCornerShape(OpenCodeRadius.card)),
        )
    } else if (images.isNotEmpty()) {
        LazyRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(images, key = { _, image -> image.id }) { index, image ->
                val bitmap = remember(image.id) { image.preview.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.system_share_image_position_description, index + 1, images.size),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = minOf(height, 300.dp)).clip(RoundedCornerShape(OpenCodeRadius.card)),
                )
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(OpenCodeTheme.colors.userBubble, RoundedCornerShape(OpenCodeRadius.card))
            .padding(14.dp),
    ) {
        content()
    }
}

private fun skippedNote(images: Int, files: Int): String {
    val parts = mutableListOf<String>()
    if (images > 0) parts.add(localizedPlural(R.plurals.system_share_images_skipped, images, images))
    if (files > 0) parts.add(localizedPlural(R.plurals.system_share_files_skipped, files, files))
    return parts.joinToString(" · ")
}

/** A shared web link: its host over the full address. */
private class SharedLink(val host: String, val address: String) {
    companion object {
        fun parse(text: String): SharedLink? {
            if (text.any { it.isWhitespace() }) return null
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            return SharedLink(if (host.startsWith("www.")) host.drop(4) else host, text)
        }
    }
}

/** New Session (in its project) first, then pinned and recent sessions, then search. The chosen one is filled. */
@Composable
private fun ShareTargetChips(
    state: ShareModel.State,
    reduceMotion: Boolean,
    onChoose: (ShareComposition.Destination) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val selectedKey = when (val destination = state.destination) {
        is ShareComposition.Destination.Session -> destination.session.id
        else -> NEW_KEY
    }
    val keys = remember(state.sessions) { listOf(NEW_KEY) + state.sessions.map { it.id } }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(selectedKey, keys) {
        val index = keys.indexOf(selectedKey).coerceAtLeast(0)
        centre(listState, index, animated = appeared && !reduceMotion)
        appeared = true
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().blockInput(state.isBusy),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = NEW_KEY) { NewSessionChip(state, onChoose) }
        items(state.sessions, key = { it.id }) { session ->
            val selected = (state.destination as? ShareComposition.Destination.Session)?.session?.id == session.id
            Chip(
                selected = selected,
                label = stringResource(R.string.system_share_session_chip_description, session.title, session.projectName),
                onClick = { onChoose(ShareComposition.Destination.Session(session)) },
            ) {
                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
        item(key = SEARCH_KEY) {
            val searchDescription = stringResource(R.string.system_share_search_sessions_description)
            Box(
                Modifier
                    .clip(OpenCodeShape.circle)
                    .clickable(onClickLabel = null, role = Role.Button, onClick = onSearch)
                    .openCodeTapTarget()
                    .semantics { contentDescription = searchDescription },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).background(OpenCodeTheme.colors.inlineCodeFill, OpenCodeShape.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.Magnifyingglass, contentDescription = null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
    }
}

private const val NEW_KEY = "new"
private const val SEARCH_KEY = "search"

/** Scrolls item [index] to the row's centre. */
private suspend fun centre(state: LazyListState, index: Int, animated: Boolean) {
    var info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info == null) {
        state.scrollToItem(index)
        info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val viewport = state.layoutInfo.viewportEndOffset + state.layoutInfo.viewportStartOffset
    val delta = (info.offset + info.size / 2f) - viewport / 2f
    if (animated) state.animateScrollBy(delta, MotionRole.Layout.animation()) else state.scrollBy(delta)
}

@Composable
private fun NewSessionChip(state: ShareModel.State, onChoose: (ShareComposition.Destination) -> Unit) {
    val destination = state.destination
    if (destination is ShareComposition.Destination.NewSession) {
        // Chosen: it names its project, and a tap picks another.
        var expanded by remember { mutableStateOf(false) }
        Box {
            Chip(
                selected = true,
                label = stringResource(R.string.system_share_new_session_in_project_description, destination.project.name),
                hint = stringResource(R.string.system_share_choose_project_description),
                onClick = { expanded = true },
            ) {
                OpenCodeIcon(SFSymbol.Plus, contentDescription = null, size = 14.dp)
                Text(stringResource(R.string.system_new_session))
                Text(destination.project.name, modifier = Modifier.alpha(0.65f), maxLines = 1)
                OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = 12.dp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (candidate in state.projects) {
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        onClick = {
                            expanded = false
                            onChoose(ShareComposition.Destination.NewSession(candidate))
                        },
                        trailingIcon = if (candidate.id == destination.project.id) {
                            { OpenCodeIcon(SFSymbol.Checkmark, contentDescription = stringResource(R.string.system_selected_description), size = OpenCodeIconSize.small) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    } else {
        val project = state.projects.firstOrNull() ?: return
        Chip(selected = false, label = stringResource(R.string.system_new_session), onClick = { onChoose(ShareComposition.Destination.NewSession(project)) }) {
            OpenCodeIcon(SFSymbol.Plus, contentDescription = null, size = 14.dp)
            Text(stringResource(R.string.system_new_session))
        }
    }
}

@Composable
private fun Chip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val container = if (selected) colors.text else colors.inlineCodeFill
    val foreground = if (selected) colors.canvas else colors.text
    Box(
        Modifier
            .clip(OpenCodeShape.control)
            .clickable(onClickLabel = hint, role = Role.Button, onClick = onClick)
            .openCodeTapTarget(accessibility = false)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                .height(36.dp)
                .background(container, OpenCodeShape.control)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                androidx.compose.material3.ProvideTextStyle(
                    OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, color = foreground),
                ) { content() }
            }
        }
    }
}

/**
 * The bar keeps its size and the keyboard through Send → Sent → close: the field locks in place
 * and dims, the arrow turns into the spinner in the same circle, then into a checkmark that
 * pops, while the words give way to where they went.
 */
@Composable
private fun MessageBar(state: ShareModel.State, actions: ShareActions, reduceMotion: Boolean, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val phase = state.phase
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val fieldAlpha by animateFloatAsState(
        when (phase) {
            ShareModel.Phase.Sent -> 0f
            ShareModel.Phase.Sending -> 0.55f
            else -> 1f
        },
        phaseAnimation(reduceMotion),
        label = "fieldAlpha",
    )
    val fieldBlur by animateDpAsState(if (phase == ShareModel.Phase.Sent && !reduceMotion) 6.dp else 0.dp, phaseAnimation(reduceMotion), label = "fieldBlur")
    val fieldLift by animateDpAsState(if (phase == ShareModel.Phase.Sent && !reduceMotion) (-6).dp else 0.dp, phaseAnimation(reduceMotion), label = "fieldLift")
    val density = LocalDensity.current
    // How far the centre of the field's last-line capitals sits above the field's bottom edge:
    // measured from the laid-out text, so the last line's capitals centre on the arrow at any size.
    val bodySize = with(density) { OpenCodeTextStyles.body.fontSize.toPx() }
    val capHeight = remember(bodySize) {
        val bounds = android.graphics.Rect()
        android.graphics.Paint().apply { textSize = bodySize; typeface = android.graphics.Typeface.DEFAULT }
            .getTextBounds("H", 0, 1, bounds)
        bounds.height().toFloat()
    }
    var lastLineCapCentre by remember { mutableStateOf(with(density) { 11.8.sp.toDp() }) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        // The desktop dock: 12 dp corners, floating fill, hairline ring.
        shape = RoundedCornerShape(OpenCodeRadius.panel),
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        contentColor = colors.text,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                // Centre the last line's capitals on the arrow (26 dp up: the 48 dp button's
                // middle plus its 2 dp inset), as the icon centres on caps.
                Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = maxOf(26.dp - lastLineCapCentre, 4.dp)),
                contentAlignment = Alignment.BottomStart,
            ) {
                BasicTextField(
                    value = state.message,
                    // Edits are ignored while it sends, without taking the keyboard (never disabled:
                    // that would drop it).
                    onValueChange = actions.onMessageChange,
                    textStyle = OpenCodeTextStyles.body.copy(color = colors.text),
                    cursorBrush = SolidColor(if (state.isBusy) androidx.compose.ui.graphics.Color.Transparent else colors.accent),
                    minLines = 1,
                    maxLines = 6,
                    onTextLayout = { layout ->
                        if (layout.lineCount == 0) return@BasicTextField
                        val last = layout.lineCount - 1
                        val centre = layout.size.height - layout.getLineBaseline(last) + capHeight / 2
                        val measured = with(density) { centre.toDp() }
                        if (measured != lastLineCapCentre) lastLineCapCentre = measured
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .graphicsLayer {
                            alpha = fieldAlpha
                            translationY = with(density) { fieldLift.toPx() }
                        }
                        .transitionBlur(fieldBlur)
                        .then(if (phase == ShareModel.Phase.Sent) Modifier.semantics { hideFromAccessibility() } else Modifier),
                    decorationBox = { inner ->
                        Box {
                            if (state.message.isEmpty()) {
                                Text(stringResource(R.string.system_share_message_placeholder), style = OpenCodeTextStyles.body, color = colors.tertiaryText)
                            }
                            inner()
                        }
                    },
                )
                val destination = state.destination
                androidx.compose.animation.AnimatedVisibility(
                    visible = phase == ShareModel.Phase.Sent && destination != null,
                    // The words give way upward to where they went, on the swap's fade in.
                    enter = if (reduceMotion) MotionTransition.swap(true).enter else fadeIn(MotionRole.Swap.animation()) +
                        slideInVertically(MotionRole.Swap.animation()) { -it / 3 },
                    exit = MotionTransition.swap(reduceMotion).exit,
                ) {
                    Text(
                        stringResource(R.string.system_share_sent_to, destination?.title ?: ""),
                        style = OpenCodeTextStyles.body,
                        color = colors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SendButton(state, actions.onSend, reduceMotion, Modifier.padding(2.dp))
        }
    }
}

private enum class Glyph { Arrow, Spinner, Check }

@Composable
private fun SendButton(state: ShareModel.State, onSend: () -> Unit, reduceMotion: Boolean, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val phase = state.phase
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pop = remember { Animatable(1f) }
    val sent = phase == ShareModel.Phase.Sent
    LaunchedEffect(sent) {
        // Plays as the checkmark arrives (the sheet closes before Sent ever ends): out like a
        // press going down, back on micro in.
        if (sent && !reduceMotion) {
            pop.animateTo(ShareComposition.SendMotion.POP_SCALE, MotionRole.Press.animation())
            pop.animateTo(1f, MotionRole.MicroIn.animation())
        } else {
            pop.snapTo(1f)
        }
    }
    val lit = state.canSend || phase == ShareModel.Phase.Sending || sent
    val glyph = when (phase) {
        ShareModel.Phase.Sending -> Glyph.Spinner
        ShareModel.Phase.Sent -> Glyph.Check
        else -> Glyph.Arrow
    }
    val label = when (phase) {
        ShareModel.Phase.Sending -> stringResource(R.string.system_share_sending_description)
        ShareModel.Phase.Sent -> stringResource(R.string.system_share_sent_description)
        else -> stringResource(CoreR.string.common_send)
    }
    val face = modifier.size(48.dp)
    Box(
        // Sending and sent keep their look but take no taps, and stop being a button.
        (if (state.isBusy) face else face.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = state.canSend,
            role = Role.Button,
            onClick = onSend,
        )).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                // The shared press (a dim under Reduce Motion), times the Sent pop.
                .motionPressEffect(pressed, reduceMotion)
                .scale(pop.value)
                .background(if (lit) colors.text else colors.inlineCodeFill, OpenCodeShape.circle),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = glyph,
                // Arrow, spinner and checkmark swap in the same circle.
                transitionSpec = { MotionTransition.swap(reduceMotion).content },
                label = "sendGlyph",
            ) { current ->
                when (current) {
                    Glyph.Spinner -> SendingSpinner(colors.canvas)
                    Glyph.Check -> OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = colors.canvas)
                    Glyph.Arrow -> OpenCodeIcon(
                        SFSymbol.ArrowUp,
                        contentDescription = null,
                        size = 18.dp,
                        tint = if (state.canSend) colors.canvas else colors.tertiaryText,
                    )
                }
            }
        }
    }
}

/**
 * The sending arc, drawn in the background colour on the label-coloured circle. It turns once
 * per half of the ambient sweep, and holds still under Reduce Motion.
 */
@Composable
private fun SendingSpinner(color: androidx.compose.ui.graphics.Color) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "spinner")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(Motion.millis(AmbientClock.sweepPeriod / 2), easing = LinearEasing), RepeatMode.Restart),
        label = "spinnerAngle",
    )
    val angle = if (reduceMotion) 0f else turn
    Canvas(Modifier.size(15.dp).semantics { hideFromAccessibility() }) {
        val stroke = 2.2.dp.toPx()
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 0.72f * 360f,
                useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Every session the sheet offers, searchable, for when the chips run long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSessionSearch(
    state: ShareModel.State,
    onChoose: (ShareComposition.Destination) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val colors = OpenCodeTheme.colors
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = colors.groupedCanvas,
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            OpenCodeIconButton(
                symbol = SFSymbol.Xmark,
                contentDescription = stringResource(CoreR.string.common_close),
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
                style = OpenCodeIconButtonStyle.Standard,
            )
            Text(stringResource(R.string.system_share_sessions_title), style = OpenCodeTextStyles.headline, modifier = Modifier.align(Alignment.Center))
        }
        OpenCodeSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(CoreR.string.common_search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val pinned = matches(state.targets?.pinned ?: emptyList(), query)
        val recents = matches(state.targets?.recents ?: emptyList(), query)
        val pinnedTitle = stringResource(R.string.system_share_pinned_header)
        val recentsTitle = stringResource(R.string.system_share_recents_header)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            section(pinnedTitle, pinned, state.destination, onChoose, onClose)
            section(recentsTitle, recents, state.destination, onChoose, onClose)
        }
    }
}

private fun matches(sessions: List<ShareTargets.Session>, query: String): List<ShareTargets.Session> =
    sessions.filter {
        query.isEmpty() || it.title.contains(query, ignoreCase = true) || it.projectName.contains(query, ignoreCase = true)
    }

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    sessions: List<ShareTargets.Session>,
    destination: ShareComposition.Destination?,
    onChoose: (ShareComposition.Destination) -> Unit,
    onClose: () -> Unit,
) {
    if (sessions.isEmpty()) return
    item(key = "header-$title") {
        Text(
            title,
            style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
        )
    }
    items(sessions, key = { "$title-${it.id}" }) { session ->
        val selected = (destination as? ShareComposition.Destination.Session)?.session == session
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    onChoose(ShareComposition.Destination.Session(session))
                    onClose()
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { this.selected = selected },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(session.title, style = OpenCodeTextStyles.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                session.projectName,
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
