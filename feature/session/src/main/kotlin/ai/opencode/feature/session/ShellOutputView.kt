package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.composer.OpenCodeBottomSheet
import ai.opencode.server.model.OpenCodeShell
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Duration
import java.time.Instant

/**
 * A shell's live output in a bottom sheet. Tails while running, follows the bottom unless the
 * reader scrolls up, and ends with the command's exit status. Polls only while on screen and the
 * app is resumed.
 *
 * @param onClose Close (or swipe away): the parent dismisses the presentation (quietly).
 * @param runningGlyph The running shell's glyph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellOutputView(
    store: StoreOf<ShellOutputFeature.State, ShellOutputFeature.Action>,
    onClose: () -> Unit,
    runningGlyph: @Composable (shellID: String) -> Unit = { ShellGlyph(shellIDs = listOf(it)) },
) {
    val state by store.collectState()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // Polls only while on screen and the app is active.
    DisposableEffect(isActive) {
        if (isActive) store.send(ShellOutputFeature.Action.Task)
        onDispose { if (isActive) store.send(ShellOutputFeature.Action.TaskEnded) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val navigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    OpenCodeBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier.testTag("shell-output"),
    ) {
        var isFollowing by remember { mutableStateOf(true) }
        var jumpRequest by remember { mutableIntStateOf(0) }
        // The column is as tall as the part of the sheet on screen, not the whole sheet. The
        // column's top below the sheet's (the drag handle), so the visible part is the window below
        // the sheet's top less that.
        var headerInSheet by remember { mutableFloatStateOf(0f) }
        // A drag moves the sheet every frame: only the output box takes the new height. The
        // toolbar, earlier-output bar and status bar keep constant constraints, so they're
        // measured once, and the header's place is written only when it really moves.
        Layout(
            content = {
                Toolbar(state, onClose, send = store::send)
                Box { EarlierOutputBar(state) { store.send(ShellOutputFeature.Action.LoadEarlierTapped) } }
                Box(Modifier.fillMaxSize()) {
                    Content(state, isFollowing, jumpRequest, onFollowingChanged = { isFollowing = it })
                    JumpToLatest(visible = !isFollowing && state.output.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd)) {
                        jumpRequest += 1
                    }
                }
                StatusBar(state, runningGlyph, send = store::send)
            },
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { coordinates ->
                    val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@onPlaced
                    val header = (coordinates.positionInWindow().y - offset).coerceAtLeast(0f)
                    if (abs(header - headerInSheet) > 0.5f) headerInSheet = header
                },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val full = constraints.maxHeight
            val offset = runCatching { sheetState.requireOffset() }.getOrNull()
            // Above the navigation bar, wherever the sheet rests (its own inset spacer sits
            // off screen at the medium rest).
            val visible = if (offset == null || windowHeight <= 0) {
                full - navigationBottom
            } else {
                (windowHeight - navigationBottom - offset - headerInSheet).roundToInt()
            }.coerceIn(0, full)
            val bars = Constraints(minWidth = width, maxWidth = width, maxHeight = full)
            val toolbar = measurables[0].measure(bars)
            val earlier = measurables[1].measure(bars)
            val status = measurables[3].measure(bars)
            val outputHeight = (visible - toolbar.height - earlier.height - status.height).coerceAtLeast(0)
            val output = measurables[2].measure(Constraints.fixed(width, outputHeight))
            layout(width, full) {
                // M3 changes the top inset as the sheet crosses the status bar. Read the
                // placed top, not last frame's measured header, to pin the footer this frame.
                val top = coordinates?.positionInWindow()?.y
                val statusTop = if (top == null || offset == null || windowHeight <= 0) {
                    toolbar.height + earlier.height + outputHeight
                } else {
                    ((windowHeight - navigationBottom - top).roundToInt() - status.height)
                        .coerceIn(toolbar.height + earlier.height, (full - status.height).coerceAtLeast(toolbar.height + earlier.height))
                }
                toolbar.place(0, 0)
                earlier.place(0, toolbar.height)
                output.place(0, toolbar.height + earlier.height)
                status.place(0, statusTop)
            }
        }
    }
    if (state.isConfirmingStop) {
        OpenCodeAlertDialog(
            onDismissRequest = { store.send(ShellOutputFeature.Action.IsConfirmingStopChanged(false)) },
            title = { Text(stringResource(R.string.session_shell_stop_confirm_title)) },
            text = { Text(stringResource(R.string.session_shell_stop_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { store.send(ShellOutputFeature.Action.StopConfirmed) }, modifier = Modifier.testTag("shell-output-stop-confirm")) {
                    Text(stringResource(R.string.session_shell_stop), color = OpenCodeTheme.colors.destructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { store.send(ShellOutputFeature.Action.IsConfirmingStopChanged(false)) }) { Text(stringResource(CoreR.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun Toolbar(state: ShellOutputFeature.State, onClose: () -> Unit, send: (ShellOutputFeature.Action) -> Unit) {
    var didCopy by remember { mutableStateOf(false) }
    LaunchedEffect(didCopy) {
        if (!didCopy) return@LaunchedEffect
        delay(1500)
        didCopy = false
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeIconButton(
            SFSymbol.Xmark,
            contentDescription = stringResource(CoreR.string.common_close),
            onClick = onClose,
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier.testTag("shell-output-close"),
        )
        // The title is centred on the sheet: the leading side holds one control and the trailing
        // two, so it gives the difference back.
        Spacer(Modifier.width(48.dp))
        Text(
            state.shell.title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold, fontFamily = OpenCodeFonts.monospace),
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        OpenCodeIconButton(
            if (didCopy) SFSymbol.Checkmark else SFSymbol.SquareOnSquare,
            contentDescription = if (didCopy) stringResource(CoreR.string.common_copied) else stringResource(R.string.session_shell_copy_output),
            onClick = {
                send(ShellOutputFeature.Action.CopyOutputTapped)
                didCopy = true
            },
            style = OpenCodeIconButtonStyle.Standard,
            enabled = state.output.isNotEmpty(),
        )
        OpenCodeIconButton(
            SFSymbol.SquareAndArrowUp,
            contentDescription = stringResource(R.string.session_shell_share_output),
            onClick = { send(ShellOutputFeature.Action.ShareOutputTapped) },
            style = OpenCodeIconButtonStyle.Standard,
            enabled = state.output.isNotEmpty(),
        )
    }
}

@Composable
private fun EarlierOutputBar(state: ShellOutputFeature.State, onLoadEarlier: () -> Unit) {
    if (!state.hasEarlierOutput || state.output.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.canLoadEarlier) stringResource(R.string.session_shell_earlier_output_hidden) else stringResource(R.string.session_shell_showing_last_output),
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        if (state.canLoadEarlier && !state.isUnavailable) {
            TextButton(
                onClick = onLoadEarlier,
                enabled = !state.isLoadingEarlier,
                modifier = Modifier.heightIn(min = 48.dp).testTag("shell-output-load-earlier"),
            ) {
                if (state.isLoadingEarlier) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.session_shell_load_earlier), style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun Content(state: ShellOutputFeature.State, isFollowing: Boolean, jumpRequest: Int, onFollowingChanged: (Boolean) -> Unit) {
    when {
        state.output.isNotEmpty() -> ShellOutputTextView(state.output, state.startCursor, isFollowing, jumpRequest, onFollowingChanged)
        state.isUnavailable -> EmptyState(stringResource(R.string.session_shell_output_unavailable_title), stringResource(R.string.session_shell_output_unavailable_message))
        !state.isLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> EmptyState(
            if (state.isRunning) stringResource(R.string.session_shell_no_output_yet_title) else stringResource(R.string.session_shell_no_output_title),
            if (state.isRunning) stringResource(R.string.session_shell_no_output_yet_message) else null,
        )
    }
}

@Composable
private fun EmptyState(title: String, description: String?) {
    OpenCodeContentUnavailable(SFSymbol.Terminal, title, Modifier.fillMaxSize(), description = description)
}

/** One output line and its absolute byte offset, its stable key: trimming or loading earlier keeps the reader's place. */
private data class OutputLine(val offset: Long, val text: String)

/**
 * The output's lines, kept across polls: output that only grew re-reads from its last line on,
 * so a long capture isn't split again on every append. Anything else (trimmed, loaded earlier)
 * is read whole.
 */
private class OutputLines {
    private var text = ""
    private var startCursor = -1
    private var lines: List<OutputLine> = emptyList()
    /** Where the last line starts in [text]. */
    private var lastLineStart = 0

    fun update(text: String, startCursor: Int): List<OutputLine> {
        if (text == this.text && startCursor == this.startCursor) return lines
        val appends = startCursor == this.startCursor && lines.isNotEmpty() &&
            text.length >= this.text.length && text.startsWith(this.text)
        val next = ArrayList<OutputLine>(if (appends) lines.size + 16 else 64)
        var from = 0
        var offset = startCursor.toLong()
        if (appends) {
            next.addAll(lines.subList(0, lines.size - 1))
            from = lastLineStart
            offset = lines.last().offset
        }
        lastLineStart = parse(text, from, offset, next)
        this.text = text
        this.startCursor = startCursor
        lines = next
        return next
    }

    /** Appends [text]'s lines from [from] on; returns where the last one starts. */
    private fun parse(text: String, from: Int, startOffset: Long, into: ArrayList<OutputLine>): Int {
        var offset = startOffset
        var lineStart = from
        var lastStart = from
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val end = if (newline < 0) text.length else newline
            val line = text.substring(lineStart, end)
            if (newline < 0 && line.isEmpty() && into.isNotEmpty()) break
            into += OutputLine(offset, line)
            lastStart = lineStart
            offset += ShellOutputFeature.utf8Count(line) + 1
            if (newline < 0) break
            lineStart = newline + 1
        }
        return lastStart
    }
}

/**
 * Monospaced, wrapped, selectable output. Lines are lazy rows keyed by byte offset, so new output
 * appends in place instead of re-laying out the capture. Following: the reader's own scrolling
 * within 32 dp of the bottom follows; anything else stops.
 */
@Composable
private fun ShellOutputTextView(text: String, startCursor: Int, isFollowing: Boolean, jumpRequest: Int, onFollowingChanged: (Boolean) -> Unit) {
    val cache = remember { OutputLines() }
    val lines = remember(text, startCursor) { cache.update(text, startCursor) }
    val listState = rememberLazyListState()
    val reduceMotion = rememberReduceMotion()
    val followThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    var userScrolling by remember { mutableStateOf(false) }
    var lastJump by remember { mutableIntStateOf(jumpRequest) }
    val following by rememberUpdatedState(isFollowing)
    val reportFollowing by rememberUpdatedState(onFollowingChanged)

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolling = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (!it) userScrolling = false }
    }
    // Only the reader's own scrolling changes whether we follow.
    LaunchedEffect(listState, followThreshold) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val distance = if (last == null) 0f else if (last.index < info.totalItemsCount - 1) Float.MAX_VALUE
            else (last.offset + last.size - info.viewportEndOffset + info.afterContentPadding).toFloat()
            distance < followThreshold
        }.distinctUntilChanged().collect {
            if (userScrolling && it != following) reportFollowing(it)
        }
    }
    // Every render while following ends at the bottom: new lines, or more of the last one.
    LaunchedEffect(lines, isFollowing) {
        if (isFollowing && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1, Int.MAX_VALUE / 2)
    }
    // A resize (the sheet dragged or settling on a detent) keeps a following reader at the bottom
    // in the same layout pass: the bottom is asked for before the list measures at its new height,
    // so its last lines never lag a frame behind the status bar.
    val lineCount by rememberUpdatedState(lines.size)
    val measuredHeight = remember { intArrayOf(-1) }
    LaunchedEffect(jumpRequest) {
        if (jumpRequest == lastJump) return@LaunchedEffect
        lastJump = jumpRequest
        onFollowingChanged(true)
        if (lines.isNotEmpty()) {
            if (reduceMotion) listState.scrollToItem(lines.size - 1, Int.MAX_VALUE / 2)
            else listState.animateScrollToItem(lines.size - 1, Int.MAX_VALUE / 2)
        }
    }
    val style = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace, fontSize = 13.sp, color = OpenCodeTheme.colors.text)
    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val height = constraints.maxHeight
                    if (height != measuredHeight[0]) {
                        // Never while the reader's own drag runs: asking would stop it (and a swipe
                        // that pulls the sheet down goes through it).
                        if (measuredHeight[0] >= 0 && following && !userScrolling && !listState.isScrollInProgress && lineCount > 0) {
                            listState.requestScrollToItem(lineCount - 1, Int.MAX_VALUE / 2)
                        }
                        measuredHeight[0] = height
                    }
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .testTag("shell-output-text").semantics { contentDescription = "Shell output" },
        ) {
            itemsIndexed(lines, key = { _, line -> line.offset }, contentType = { _, _ -> 0 }) { _, line ->
                Text(line.text, style = style, softWrap = true)
            }
        }
    }
}

@Composable
private fun JumpToLatest(visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(16.dp),
        enter = MotionTransition.micro(reduceMotion).enter,
        exit = MotionTransition.micro(reduceMotion).exit,
    ) {
        OpenCodeIconButton(
            SFSymbol.ArrowDown,
            contentDescription = stringResource(R.string.session_scroll_to_latest_description),
            onClick = onClick,
            iconSize = 20.dp,
            modifier = Modifier.testTag("shell-output-scroll-to-latest"),
        )
    }
}

@Composable
private fun StatusBar(state: ShellOutputFeature.State, runningGlyph: @Composable (String) -> Unit, send: (ShellOutputFeature.Action) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShellStatusLabel(state, runningGlyph, Modifier.weight(1f))
        if (state.isRunning || state.isStopping) {
            // A tonal red fill.
            val red = OpenCodeTheme.colors.destructive
            FilledTonalButton(
                onClick = { send(ShellOutputFeature.Action.StopTapped) },
                enabled = !state.isStopping,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = red.copy(alpha = 0.15f),
                    contentColor = red,
                    disabledContainerColor = red.copy(alpha = 0.08f),
                    disabledContentColor = red.copy(alpha = 0.5f),
                ),
                modifier = Modifier.heightIn(min = 48.dp).testTag("shell-output-stop"),
                shape = OpenCodeShape.control,
            ) {
                if (state.isStopping) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.session_shell_stop))
            }
        }
    }
}

private val successGreen = Color(0xFF34C759)

/** “Running · 1:23”, “Exited with code 1”, “Interrupted”: sentence-case status. */
@Composable
private fun ShellStatusLabel(state: ShellOutputFeature.State, runningGlyph: @Composable (String) -> Unit, modifier: Modifier) {
    val shell = state.shell
    val title = when {
        state.isStopping -> stringResource(R.string.session_shell_status_stopping)
        shell.status == OpenCodeShell.Status.Running && state.isUnavailable -> stringResource(R.string.session_shell_status_unavailable)
        shell.status == OpenCodeShell.Status.Running -> stringResource(R.string.session_activity_running_description)
        shell.status == OpenCodeShell.Status.Killed -> stringResource(R.string.session_shell_status_interrupted)
        shell.status == OpenCodeShell.Status.Timeout -> stringResource(R.string.session_shell_status_timed_out)
        else -> shell.exitCode?.takeIf { it != 0 }?.let { stringResource(R.string.session_shell_status_exited_with_code, it) } ?: stringResource(R.string.session_shell_status_exited)
    }
    Row(modifier.semantics(mergeDescendants = true) {}.testTag("shell-output-status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            when {
                shell.status == OpenCodeShell.Status.Running && !state.isUnavailable -> runningGlyph(shell.id)
                shell.status == OpenCodeShell.Status.Running || shell.status == OpenCodeShell.Status.Killed ->
                    OpenCodeIcon(SFSymbol.HandRaisedFill, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.secondaryText)
                shell.status == OpenCodeShell.Status.Timeout ->
                    OpenCodeIcon(SFSymbol.ClockBadgeExclamationmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.warning)
                (shell.exitCode ?: 0) == 0 ->
                    OpenCodeIcon(SFSymbol.CheckmarkCircleFill, contentDescription = null, size = 18.dp, tint = successGreen)
                else -> OpenCodeIcon(SFSymbol.XmarkCircleFill, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.danger)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val labelStyle = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
                Text(title, style = labelStyle, color = OpenCodeTheme.colors.text)
                val startedAt = shell.startedAt
                if (startedAt != null && (shell.status == OpenCodeShell.Status.Running || shell.completedAt != null)) {
                    Text("·", style = labelStyle, color = OpenCodeTheme.colors.text, modifier = Modifier.semantics { contentDescription = "" })
                    ElapsedTime(startedAt, if (shell.status == OpenCodeShell.Status.Running) null else shell.completedAt, labelStyle)
                }
            }
            state.errorMessage?.let { OpenCodeErrorText(it, style = OpenCodeTextStyles.caption1) }
        }
    }
}

/** The elapsed time, ticking each second while [until] is null. */
@Composable
private fun ElapsedTime(since: Instant, until: Instant?, style: androidx.compose.ui.text.TextStyle) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (until == null) {
        LaunchedEffect(since) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000 - (now - since.toEpochMilli()).mod(1000L))
            }
        }
    }
    val end = until ?: Instant.ofEpochMilli(now)
    val seconds = maxOf(0L, Duration.between(since, end).seconds)
    val locale = appFormattingLocale()
    val text = if (seconds >= 3600) "%d:%02d:%02d".format(locale, seconds / 3600, seconds % 3600 / 60, seconds % 60)
    else "%d:%02d".format(locale, seconds / 60, seconds % 60)
    val hours = (seconds / 3600).toInt()
    val minutes = (seconds % 3600 / 60).toInt()
    val secs = (seconds % 60).toInt()
    val hoursText = pluralStringResource(R.plurals.session_shell_elapsed_hours, hours, hours)
    val minutesText = pluralStringResource(R.plurals.session_shell_elapsed_minutes, minutes, minutes)
    val secondsText = pluralStringResource(R.plurals.session_shell_elapsed_seconds, secs, secs)
    val spoken = buildList {
        if (seconds >= 3600) add(hoursText)
        if (seconds >= 60) add(minutesText)
        add(secondsText)
    }.joinToString(", ")
    Text(text, style = style.copy(fontFeatureSettings = "tnum"), color = OpenCodeTheme.colors.text, modifier = Modifier.semantics { contentDescription = spoken })
}
