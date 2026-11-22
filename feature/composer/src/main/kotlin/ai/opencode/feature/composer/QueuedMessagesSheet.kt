package ai.opencode.feature.composer

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.sfSymbol
import ai.opencode.feature.media.CachedImage
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeQueuedInput
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The queued messages, opened from the queued pill: a sheet only as tall as the queue, growing as
 * it does and scrolling once it reaches [QueuedMessagesSheetMetrics.maximumHeight]. Each message is
 * a numbered row of two lines (a tap reads the rest) with quiet trailing icons: ↑ Send Now on a
 * small grey disc and a trash glyph for Cancel Message. A swipe left cancels (a full swipe acts); a
 * swipe right only reveals Send Now, so a careless full swipe never sends. Holding a row offers
 * Send Now, Copy and Cancel Message. Nothing here is red.
 *
 * [displayText] turns a queued voice delegation into its Voice message; the session area passes the
 * voice area's parser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueuedMessagesSheet(
    messages: List<OpenCodeQueuedInput>,
    onSendNow: (String) -> Unit,
    onCopy: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClose: () -> Unit,
    displayText: (String) -> String = { it },
    /** Where a `media:<hash>` thumbnail loads from. */
    server: OpenCodeServer? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val reduceMotion = rememberReduceMotion()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        Column(
            Modifier
                .heightIn(max = QueuedMessagesSheetMetrics.maximumHeight(screenHeight))
                .animateContentSize(animationSpec = Motion.layout.animation(reduceMotion = reduceMotion)),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.composer_queued_title),
                    style = OpenCodeTextStyles.headline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).semantics { heading() },
                )
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd).testTag("queued-messages-close")) {
                    OpenCodeIcon(SFSymbol.Xmark, contentDescription = stringResource(CoreR.string.common_close))
                }
            }
            if (messages.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .padding(vertical = 24.dp),
                ) {
                    OpenCodeIcon(SFSymbol.Tray, contentDescription = null, size = OpenCodeIconSize.large, tint = OpenCodeTheme.colors.secondaryText)
                    Text(stringResource(R.string.composer_queued_empty), style = OpenCodeTextStyles.headline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.testTag("queued-message-list"),
                    contentPadding = PaddingValues(top = QueuedMessagesSheetMetrics.listTop, bottom = QueuedMessagesSheetMetrics.listBottom),
                ) {
                    itemsIndexed(messages, key = { _, input -> input.id }) { index, input ->
                        Column(if (reduceMotion) Modifier else Modifier.animateItem(placementSpec = Motion.layout.animation(visibilityThreshold = IntOffset.VisibilityThreshold))) {
                            QueuedMessageRow(
                                input = input,
                                number = index + 1,
                                count = messages.size,
                                text = displayText(input.text),
                                onSendNow = { onSendNow(input.id) },
                                onCopy = { onCopy(input.text) },
                                onCancel = { onCancel(input.id) },
                                server = server,
                            )
                            // Hairlines only between messages.
                            if (index < messages.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp))
                        }
                    }
                }
            }
        }
    }
}

object QueuedMessagesSheetMetrics {
    val listTop = 4.dp
    val listBottom = 12.dp

    /** Just short of the full sheet: past it, the rows scroll. */
    fun maximumHeight(screenHeight: androidx.compose.ui.unit.Dp) = maxOf(screenHeight - 110.dp, 320.dp)
}

/** How far a swipe reveals its action. */
private val SWIPE_REVEAL = 88.dp

/**
 * One queued message: its number in the sending order, two lines of its text (a tap reads the rest)
 * under any attachment chips, and the quiet Cancel Message and Send Now icons trailing it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueuedMessageRow(
    input: OpenCodeQueuedInput,
    number: Int,
    count: Int,
    text: String,
    onSendNow: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
    server: OpenCodeServer? = null,
) {
    val reduceMotion = rememberReduceMotion()
    var expanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val canExpand = expanded || overflows
    val largeText = LocalDensity.current.fontScale >= ACCESSIBILITY_FONT_SCALE
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(0) }
    val revealPx = with(LocalDensity.current) { SWIPE_REVEAL.toPx() }
    val singleImageText = stringResource(R.string.composer_queued_image)
    val imagesText = pluralStringResource(R.plurals.composer_queued_images, input.images.size, input.images.size)
    val positionText = stringResource(R.string.composer_queued_position, number, count)
    val sendNowText = stringResource(R.string.composer_queued_send_now)
    val copyText = stringResource(CoreR.string.common_copy)
    val cancelMessageText = stringResource(R.string.composer_queued_cancel_message)
    val showLessText = stringResource(R.string.composer_queued_show_less)
    val showMoreText = stringResource(R.string.composer_queued_show_more)

    val accessibilityText = buildList {
        if (input.images.isNotEmpty()) add(if (input.images.size == 1) singleImageText else imagesText)
        input.files.forEach { add(it.name) }
        input.input.inlineFiles.forEach { file -> add(file.pasted?.let { "${file.name}, ${it.length}" } ?: file.name) }
        if (text.isNotEmpty()) add(text)
    }.joinToString(", ")

    fun settle(to: Float) = scope.launch { offset.animateTo(to, Motion.settle.animation(reduceMotion)) }

    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        // Revealed actions under the row: Send Now leading, Cancel Message trailing (grey, never red).
        if (offset.value != 0f) {
            Row(Modifier.matchParentSize()) {
                if (offset.value > 0f) {
                    SwipeAction(
                        label = sendNowText,
                        symbol = SFSymbol.ArrowUp,
                        fill = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onClick = { settle(0f); onSendNow() },
                    )
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                    SwipeAction(
                        label = cancelMessageText,
                        symbol = SFSymbol.Trash,
                        fill = MaterialTheme.colorScheme.outline,
                        onClick = { settle(0f); onCancel() },
                    )
                }
            }
        }
        val content: @Composable () -> Unit = {
            Box(
                Modifier
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .background(OpenCodeTheme.colors.groupedCanvas)
                    .pointerInput(width) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val value = offset.value
                                when {
                                    // A full swipe left cancels.
                                    value < -width * FULL_SWIPE_FRACTION -> scope.launch {
                                        offset.animateTo(-width.toFloat(), Motion.settle.animation(reduceMotion))
                                        onCancel()
                                        offset.snapTo(0f)
                                    }
                                    value < -revealPx / 2 -> settle(-revealPx)
                                    value > revealPx / 2 -> settle(revealPx)
                                    else -> settle(0f)
                                }
                            },
                            onDragCancel = { settle(0f) },
                        ) { change, dragAmount ->
                            change.consume()
                            // Right only reveals Send Now: it never travels past its action.
                            val next = (offset.value + dragAmount).coerceIn(-width.toFloat(), revealPx * 1.15f)
                            scope.launch { offset.snapTo(next) }
                        }
                    }
                    .combinedClickable(
                        onClick = {
                            if (offset.value != 0f) settle(0f) else if (canExpand) expanded = !expanded
                        },
                        onLongClick = { menuOpen = true },
                        onClickLabel = if (canExpand) (if (expanded) showLessText else showMoreText) else null,
                    )
                    .semantics(mergeDescendants = false) {
                        contentDescription = accessibilityText
                        stateDescription = positionText
                        customActions = buildList {
                            add(CustomAccessibilityAction(sendNowText) { onSendNow(); true })
                            if (input.text.isNotEmpty()) add(CustomAccessibilityAction(copyText) { onCopy(); true })
                            add(CustomAccessibilityAction(cancelMessageText) { onCancel(); true })
                            if (canExpand) add(CustomAccessibilityAction(if (expanded) showLessText else showMoreText) { expanded = !expanded; true })
                        }
                    }
                    .testTag("queued-message-$number")
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            ) {
                val number = @Composable {
                    Text(
                        number.toString(),
                        style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                        color = OpenCodeTheme.colors.tertiaryText,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .widthIn(min = 20.dp)
                            .padding(top = 1.5.dp)
                            .clearAndSetSemantics {},
                    )
                }
                val body = @Composable { modifier: Modifier ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.clearAndSetSemantics {}) {
                        QueuedAttachments(input, server)
                        if (text.isNotEmpty()) {
                            Text(
                                text,
                                style = OpenCodeTextStyles.body,
                                color = OpenCodeTheme.colors.text,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                                // Opens on `layout`, closes on `collapse`.
                                modifier = Modifier.fillMaxWidth().animateContentSize(
                                    Motion.layout.animation(if (expanded) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
                                ),
                            )
                        }
                    }
                }
                if (largeText) {
                    // Large text: the icons drop under the message, trailing.
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            number()
                            body(Modifier.weight(1f))
                        }
                        QueuedRowActions(onCancel, onSendNow)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        number()
                        body(Modifier.weight(1f))
                        // Centred on the first line; the 48 dp targets reach past the row's padding.
                        QueuedRowActions(onCancel, onSendNow, Modifier.offset(y = (-13).dp))
                    }
                }
            }
        }
        content()
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(sendNowText) },
                leadingIcon = { OpenCodeIcon(SFSymbol.ArrowUp, contentDescription = null) },
                onClick = { menuOpen = false; onSendNow() },
            )
            if (input.text.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(copyText) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.DocOnDoc, contentDescription = null) },
                    onClick = { menuOpen = false; onCopy() },
                )
            }
            DropdownMenuItem(
                text = { Text(cancelMessageText) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null) },
                onClick = { menuOpen = false; onCancel() },
            )
        }
    }
}

/** Accessibility sizes start at AX1 (about 1.6× the default text size). */
private const val ACCESSIBILITY_FONT_SCALE = 1.6f

/** Past this share of the row's width, a swipe left cancels on release. */
private const val FULL_SWIPE_FRACTION = 0.6f

@Composable
private fun SwipeAction(label: String, symbol: SFSymbol, fill: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(SWIPE_REVEAL)
            .fillMaxHeight()
            .background(fill)
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OpenCodeIcon(symbol, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Text(label, style = OpenCodeTextStyles.caption2, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun QueuedRowActions(onCancel: () -> Unit, onSendNow: () -> Unit, modifier: Modifier = Modifier) {
    val cancelMessageText = stringResource(R.string.composer_queued_cancel_message)
    val sendNowText = stringResource(R.string.composer_queued_send_now)
    Row(modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .semantics { contentDescription = cancelMessageText },
        ) {
            OpenCodeIcon(SFSymbol.Trash, contentDescription = null, tint = OpenCodeTheme.colors.secondaryText)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(CircleShape)
                .clickable(onClick = onSendNow)
                .semantics { contentDescription = sendNowText },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp)),
            ) {
                OpenCodeIcon(SFSymbol.ArrowUp, contentDescription = null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.text)
            }
        }
    }
}

/** Image thumbnails and file or Pasted Text chips above the text. */
@Composable
private fun QueuedAttachments(input: OpenCodeQueuedInput, server: OpenCodeServer?) {
    val files = input.files
    val inline = input.input.inlineFiles
    if (input.images.isEmpty() && files.isEmpty() && inline.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (input.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (image in input.images) {
                    // A `media:<hash>` photo loads from the server, and one it no longer has shows
                    // the unavailable glyph.
                    CachedImage(
                        id = image.id,
                        data = image.data,
                        mediaHash = image.mediaHash,
                        name = image.name,
                        isUnavailable = image.isUnavailable,
                        server = server,
                        compact = true,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(0.5.dp, OpenCodeTheme.colors.border, RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
        for (file in files) QueuedChip(file.name, AttachmentFileType.symbolName(file.name, file.mime))
        // Inline files: a PDF, or a long paste with its length.
        for (file in inline) {
            val pasted = file.pasted
            QueuedChip(
                when {
                    file.isUnavailable -> stringResource(R.string.composer_queued_media_unavailable, file.name)
                    pasted != null -> "${file.name} · ${pasted.length}"
                    else -> file.name
                },
                if (pasted == null) file.symbolName else "doc.plaintext",
            )
        }
    }
}

@Composable
private fun QueuedChip(title: String, symbolName: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(sfSymbol(symbolName)),
            contentDescription = null,
            tint = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.size(OpenCodeIconSize.small),
        )
        Text(
            title,
            style = OpenCodeTextStyles.subheadline,
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}
