package ai.opencode.feature.settings.connection

import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.observe
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.AlertTextFields
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.RollingNumberStyle
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.AccessibilityFocusTarget
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusTarget
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.settings.ConnectionStatus
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.SettingsButtonRow
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.SettingsFooterText
import ai.opencode.feature.settings.SettingsPage
import ai.opencode.feature.settings.SettingsPageScaffold
import ai.opencode.feature.settings.SettingsRowFrame
import ai.opencode.feature.settings.SettingsSection
import ai.opencode.feature.settings.SettingsStore
import ai.opencode.feature.settings.SettingsText
import ai.opencode.feature.settings.SettingsValueRow
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.util.WeakHashMap
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The HTTP status a refused sign-in comes back with. */
const val credentialsRejectedStatus = 401

/**
 * The card's geometry. On this branch the card takes the Settings groups' desktop card radius, so
 * it reads as one of them.
 */
private object ConnectionCardMetrics {
    val cornerRadius = OpenCodeRadius.card
    /** The scope box's inset from the card. */
    val boxInset = 6.dp
    /** The text column's inset from the box. */
    val textInset = 12.dp
    val scopeHeight = 96.dp
}

/** The largest font scales (1.8× and 2×). */
@Composable
private fun isAccessibilitySize(): Boolean = LocalDensity.current.fontScale > 1.5f

/**
 * One monitor per Settings store, shared by the card and the server page pushed from it. Weakly
 * keyed: it goes with the store.
 */
private val monitors = WeakHashMap<SettingsStore, ConnectionMonitor>()

@Composable
private fun rememberConnectionMonitor(store: SettingsStore, server: OpenCodeServer, link: ConnectionHealth.Link): ConnectionMonitor {
    val monitor = remember(store) { monitors.getOrPut(store) { ConnectionMonitor(server, link) } }
    // Home's connection or server changed.
    LaunchedEffect(monitor, server, link) { monitor.update(server, link) }
    return monitor
}

/** Runs the monitor's checks while this view is composed and the app is resumed. */
@Composable
private fun RunWhileResumed(monitor: ConnectionMonitor) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isLive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(monitor, isLive) {
        if (isLive) monitor.run()
    }
}

/**
 * The health checks as an EKG in an inset box, then the server's name with the status trailing on
 * the same line, then the address and route, quietest. The whole card is the button (no chevron)
 * and opens the server page; Retry sits inline while Offline, and Edit Server… when the server
 * refused the saved password, which no retry can fix.
 */
@Composable
fun ConnectionCard(
    store: SettingsStore,
    modifier: Modifier = Modifier,
    /** Called as the card opens the server page, before it's pushed. */
    onOpen: () -> Unit = {},
    /** Moves TalkBack's focus onto the card once (`accessibilityFocusRequest`). */
    focusRequest: AccessibilityFocusRequest? = null,
) {
    val server by store.observe { it.server }
    val status by store.observe { it.connectionStatus }
    val switchingTo by store.observe { it.switchingTo }
    val credentialsRejected by store.observe { it.credentialsRejected }
    val current = server ?: return
    val link = ConnectionMonitor.link(status, switchingTo, credentialsRejected)
    val monitor = rememberConnectionMonitor(store, current, link)
    RunWhileResumed(monitor)
    ConnectionCardContent(
        monitor = monitor,
        onOpen = {
            onOpen()
            store.send(SettingsFeature.Action.PagePushed(SettingsPage.Server))
        },
        focusRequest = focusRequest,
        onRetry = { monitor.retry { store.send(SettingsFeature.Action.Delegate(SettingsDelegate.RetryConnection)) } },
        onEditServer = { store.send(SettingsFeature.Action.Delegate(SettingsDelegate.EditServer(current))) },
        modifier = modifier,
    )
}

@Composable
private fun ConnectionCardContent(
    monitor: ConnectionMonitor,
    onOpen: () -> Unit,
    focusRequest: AccessibilityFocusRequest?,
    onRetry: () -> Unit,
    onEditServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val presentation = ConnectionStatusPresentation(monitor.display)
    val action = CardAction.of(presentation.status)
    val reduceMotion = rememberReduceMotion()
    // A ripple answers the press.
    val interaction = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(ConnectionCardMetrics.cornerRadius)
    val wellShape = RoundedCornerShape(ConnectionCardMetrics.cornerRadius - ConnectionCardMetrics.boxInset)
    val wellFill = if (colors.isDark) Color.Black.copy(alpha = if (colors.highContrast) 0.5f else 0.32f) else colors.groupedCanvas
    val wellEdge = (if (colors.isDark) Color.White else Color.Black).copy(alpha = if (colors.highContrast) 0.16f else 0.06f)
// TalkBack re-reads the focused card whenever anything it speaks changes, so the card speaks the
// status word, not the live milliseconds; the number stays on screen and on the Status row.
    val accessibilityValue = stringResource(
        R.string.settings_connection_card_value_description,
        rememberSettledSpokenWord(presentation.spokenWord),
        monitor.server.host,
        monitor.route.title,
    )
    val showServerLabel = stringResource(R.string.settings_connection_show_server_label)

    // Where the inline action sits: reserved after the status, the real button drawn over the card
    // (outside its click and its merged semantics).
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var slot by remember { mutableStateOf<Rect?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { root = it },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(colors.groupedCard)
                .border(1.dp, colors.border, cardShape)
                .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onOpen)
                // Ahead of the clearing below, which would drop its marker.
                .accessibilityFocusRequest(focusRequest)
                .clearAndSetSemantics {
                    contentDescription = monitor.name
                    stateDescription = accessibilityValue
                    role = Role.Button
                    onClick(label = showServerLabel) { onOpen(); true }
                },
        ) {
            ConnectionScopeView(
                monitor = monitor,
                isHidden = false,
                modifier = Modifier
                    .padding(start = ConnectionCardMetrics.boxInset, end = ConnectionCardMetrics.boxInset, top = ConnectionCardMetrics.boxInset)
                    .fillMaxWidth()
                    .height(ConnectionCardMetrics.scopeHeight)
                    .clip(wellShape)
                    .background(wellFill)
                    .border(0.5.dp, wellEdge, wellShape),
            )
            CardText(
                monitor = monitor,
                presentation = presentation,
                action = action,
                onSlot = { coordinates ->
                    slot = root?.takeIf { it.isAttached && coordinates.isAttached }?.localBoundingBoxOf(coordinates)
                },
                modifier = Modifier.padding(
                    start = ConnectionCardMetrics.boxInset + ConnectionCardMetrics.textInset,
                    end = ConnectionCardMetrics.boxInset + ConnectionCardMetrics.textInset,
                    top = 11.dp,
                    bottom = 14.dp,
                ),
            )
        }
        val anchor = slot
        AnimatedVisibility(
            visible = action != null && anchor != null,
            // The retry arrives on micro.
            enter = MotionTransition.micro(reduceMotion).enter,
            exit = MotionTransition.micro(reduceMotion).exit,
            modifier = Modifier.matchParentSize().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val center = anchor?.center
                layout(constraints.maxWidth, constraints.maxHeight) {
                    if (center != null) {
                        placeable.place((center.x - placeable.width / 2f).roundToInt(), (center.y - placeable.height / 2f).roundToInt())
                    }
                }
            },
        ) {
            // The action that was showing stays while it leaves.
            val shown = remember { mutableStateOf(action) }
            if (action != null) shown.value = action
            shown.value?.let { current ->
                // A ripple inside the capsule answers the press.
                val actionInteraction = remember { MutableInteractionSource() }
                RetryLabel(
                    title = current.title,
                    interaction = actionInteraction,
                    modifier = Modifier
                        .clickable(interactionSource = actionInteraction, indication = null, role = Role.Button) {
                            when (current) {
                                CardAction.retry -> onRetry()
                                CardAction.editServer -> onEditServer()
                            }
                        }
                        .openCodeTapTarget(),
                )
            }
        }
    }
}

/**
 * Name and status share the first line, the status trailing on the text column's edge (the box's
 * inset plus the trace's), the name truncating first. At accessibility sizes the status stacks
 * under it.
 */
@Composable
private fun CardText(
    monitor: ConnectionMonitor,
    presentation: ConnectionStatusPresentation,
    action: CardAction?,
    onSlot: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stacked = isAccessibilitySize()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val name = @Composable { nameModifier: Modifier ->
            Text(
                monitor.name,
                style = OpenCodeTextStyles.headline,
                color = OpenCodeTheme.colors.text,
                maxLines = if (stacked) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = nameModifier,
            )
        }
        val label = @Composable {
            ConnectionStatusLabel(presentation, ConnectionStatusPresentation.Style.current, action, onSlot)
        }
        if (stacked) {
            name(Modifier)
            label()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                name(Modifier.weight(1f))
                label()
            }
        }
        CardAddress(monitor, stacked)
    }
}

@Composable
private fun CardAddress(monitor: ConnectionMonitor, stacked: Boolean) {
    val color = OpenCodeTheme.colors.tertiaryText
    val mono = OpenCodeTextStyles.caption1.copy(fontFamily = OpenCodeFonts.monospace)
    val caption = OpenCodeTextStyles.caption1
    if (stacked) {
        Column(Modifier.padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(monitor.server.host, style = mono, color = color, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
            Text(monitor.route.title, style = caption, color = color, maxLines = 1)
        }
    } else {
        Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                monitor.server.host,
                style = mono,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            Text(" · ", style = caption, color = color, modifier = Modifier.alignByBaseline())
            Text(monitor.route.title, style = caption, color = color, maxLines = 1, modifier = Modifier.alignByBaseline())
        }
    }
}

/**
 * What the card offers inline beside its status: Retry while Offline, and Edit Server… when the
 * password was refused (Retry couldn't help).
 */
private enum class CardAction(@StringRes private val titleRes: Int) {
    retry(CoreR.string.common_retry),
    editServer(R.string.settings_server_edit_server),
    ;

    val title: String get() = localized(titleRes)

    companion object {
        fun of(status: ConnectionHealth.Status): CardAction? = when (status) {
            ConnectionHealth.Status.offline -> retry
            ConnectionHealth.Status.signInFailed -> editServer
            else -> null
        }
    }
}

/**
 * The status as shown: a symbol for its kind, then the live round trip. A measured state needs no
 * word: the glyph names it and the trace shows it. States without a number say their word.
 */
data class ConnectionStatusPresentation(
    val status: ConnectionHealth.Status,
    val symbol: SFSymbol,
    val word: String,
    val milliseconds: Int?,
    val target: String?,
    /** The HTTP status a refused sign-in came back with. */
    val code: Int?,
) {
    enum class Style {
        /** Glyph and number; the word only without one (the default). */
        glyph,
        /** Glyph, word, and number. */
        full,
        ;

        companion object {
            /** Android has no lab launch arguments. */
            val current: Style = glyph
        }
    }

    val spokenStatus: String
        get() {
            if (code != null) return localized(R.string.settings_connection_status_http_description, word, code)
            return milliseconds?.let { localizedPlural(R.plurals.settings_connection_status_milliseconds_description, it, word, it) } ?: word
        }

    /** [spokenStatus] without the round trip, for the card (see `ConnectionCard`). */
    val spokenWord: String get() = code?.let { localized(R.string.settings_connection_status_http_description, word, it) } ?: word

    /** The word, with a refused sign-in's status code. */
    val text: String get() = code?.let { localized(R.string.settings_connection_status_code, word, it) } ?: word

    /** Offline and a refused sign-in read red. */
    val isAlert: Boolean get() = status == ConnectionHealth.Status.offline || status == ConnectionHealth.Status.signInFailed

    companion object {
        operator fun invoke(display: ConnectionHealth.Display): ConnectionStatusPresentation {
            var code: Int? = null
            val (symbol, word) = when (display.status) {
                ConnectionHealth.Status.connected -> SFSymbol.WaveformPathEcg to localized(R.string.settings_connection_status_connected)
                ConnectionHealth.Status.slow -> SFSymbol.TortoiseFill to localized(R.string.settings_connection_status_slow)
                ConnectionHealth.Status.unstable -> SFSymbol.WifiExclamationmark to localized(R.string.settings_connection_status_unstable)
                ConnectionHealth.Status.reconnecting -> SFSymbol.ArrowTrianglehead2Clockwise to localized(R.string.settings_connection_status_reconnecting)
                ConnectionHealth.Status.offline -> SFSymbol.NetworkSlash to localized(R.string.settings_connection_status_offline)
                ConnectionHealth.Status.signInFailed -> {
                    code = credentialsRejectedStatus
                    SFSymbol.LockSlash to localized(R.string.settings_connection_status_sign_in_failed)
                }
                ConnectionHealth.Status.switching ->
                    SFSymbol.ArrowLeftArrowRight to (display.target?.let { localized(R.string.settings_connection_status_switching_to, it) } ?: localized(R.string.settings_connection_status_switching))
            }
            val measured = display.status == ConnectionHealth.Status.connected ||
                display.status == ConnectionHealth.Status.slow ||
                display.status == ConnectionHealth.Status.unstable
            return ConnectionStatusPresentation(
                status = display.status,
                symbol = symbol,
                word = word,
                milliseconds = if (measured) display.milliseconds else null,
                target = display.target,
                code = code,
            )
        }
    }
}

/** How long a status word holds before TalkBack is given it (see [rememberSettledSpokenWord]). */
private const val SPOKEN_STATUS_SETTLE_MS = 500L

/**
 * [word] once it has held for [SPOKEN_STATUS_SETTLE_MS]; until then the word before it. TalkBack
 * speaks each new word on a focused node, and a dropped server passes through Reconnecting for a
 * moment (~150 ms) on its way to Offline. TalkBack read both changes after the second, so "Offline,
 * …" was spoken twice; a word that doesn't last isn't given to it. The screen still shows
 * every word at once.
 */
@Composable
private fun rememberSettledSpokenWord(word: String): String {
    var settled by remember { mutableStateOf(word) }
    LaunchedEffect(word) {
        if (word == settled) return@LaunchedEffect
        delay(SPOKEN_STATUS_SETTLE_MS)
        settled = word
    }
    return settled
}

/** The status colour: red for an alert, else secondary. */
@Composable
private fun ConnectionStatusPresentation.foreground(): Color =
    if (isAlert) OpenCodeTheme.colors.danger else OpenCodeTheme.colors.secondaryText

/** The round trip rolls on the layout role. */
private val millisecondsRoll = RollingNumberStyle(duration = MotionTunables.layoutSpring.duration.value, bounce = 0.0)

/**
 * A measured line keeps its identity, so its glyph swaps and its digits roll in place. Any other
 * change replaces the whole line where it stands on the trailing edge, so the glyph never slides
 * across words; under reduce motion, a cross-fade. Followed by the reserved width of the inline
 * action.
 */
@Composable
private fun ConnectionStatusLabel(
    presentation: ConnectionStatusPresentation,
    style: ConnectionStatusPresentation.Style,
    action: CardAction?,
    onSlot: (LayoutCoordinates) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val identity = if (presentation.milliseconds == null) presentation.word else "measured"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedContent(
            targetState = presentation,
            contentKey = { if (it.milliseconds == null) it.word else "measured" },
            // The status line swaps in place on the swap role.
            transitionSpec = { MotionTransition.swap(reduceMotion).content },
            contentAlignment = Alignment.CenterEnd,
            label = "ConnectionStatus-$identity",
        ) { shown ->
            StatusLine(shown, style, reduceMotion)
        }
        if (action != null) {
            // Reserves the action's width (1 dp tall) and reports where it is.
            RetryLabel(
                title = action.title,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0))
                        layout(placeable.width, 1.dp.roundToPx()) {}
                    }
                    .onGloballyPositioned(onSlot),
            )
        }
    }
}

@Composable
private fun StatusLine(presentation: ConnectionStatusPresentation, style: ConnectionStatusPresentation.Style, reduceMotion: Boolean) {
    val color = presentation.foreground()
    val textStyle = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        StatusGlyph(presentation, color, spins = presentation.status == ConnectionHealth.Status.reconnecting && !reduceMotion)
        if (style == ConnectionStatusPresentation.Style.full || presentation.milliseconds == null) {
            Text(presentation.text, style = textStyle, color = color, maxLines = 1)
        }
        presentation.milliseconds?.let { milliseconds ->
            if (style == ConnectionStatusPresentation.Style.full) {
                Text("·", style = textStyle, color = OpenCodeTheme.colors.tertiaryText)
            }
            RollingNumber(
                text = stringResource(R.string.settings_connection_milliseconds, milliseconds),
                style = textStyle,
                color = color,
                motion = millisecondsRoll,
                alignment = RollingNumberAlignment.End,
            )
        }
    }
}

/** The status glyph (footnote semibold); Reconnecting's turns. */
@Composable
private fun StatusGlyph(presentation: ConnectionStatusPresentation, color: Color, spins: Boolean) {
    val rotation = if (spins) {
        val transition = rememberInfiniteTransition(label = "Reconnecting")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "spin").value
    } else {
        0f
    }
    AnimatedContent(
        targetState = presentation.symbol,
        transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
        label = "ConnectionStatusGlyph",
    ) { symbol ->
        OpenCodeIcon(symbol, contentDescription = null, size = 15.dp, tint = color, modifier = Modifier.rotate(rotation))
    }
}

/** The inline action's capsule: subheadline semibold on a faint fill, 30 tall. */
@Composable
private fun RetryLabel(
    title: String,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    Box(
        modifier
            .clip(OpenCodeShape.control)
            .background(OpenCodeTheme.colors.text.copy(alpha = 0.11f))
            .indication(interaction, ripple())
            .height(30.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
        )
    }
}

/**
 * The server in use: its connection, address, route and version, then the one place to manage
 * servers ([SettingsServersSection]: switch, add, rename, edit, remove), and Rename. Opened from
 * the connection card or the drawer's Manage Servers….
 */
@Composable
fun ServerPage(store: SettingsStore) {
    val server by store.observe { it.server }
    val status by store.observe { it.connectionStatus }
    val switchingTo by store.observe { it.switchingTo }
    val credentialsRejected by store.observe { it.credentialsRejected }
    val error by store.observe { it.connectionErrorMessage }
    val current = server
    if (current == null) {
        SettingsPageScaffold(title = stringResource(R.string.settings_server_title), store = store) {}
        return
    }
    val monitor = rememberConnectionMonitor(store, current, ConnectionMonitor.link(status, switchingTo, credentialsRejected))
    RunWhileResumed(monitor)
    val presentation = ConnectionStatusPresentation(monitor.display)
    val send = { delegate: SettingsDelegate -> store.send(SettingsFeature.Action.Delegate(delegate)) }
    var renaming by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    val addressLabel = stringResource(R.string.settings_server_address)

    SettingsPageScaffold(title = monitor.name, store = store) {
        item(key = "server-status", contentType = "settings-section") {
            SettingsSection(
                footer = error?.takeIf { status != ConnectionStatus.connected }?.let { message ->
                    { SelectionContainer { SettingsFooterText(message) } }
                },
            ) {
                StatusRow(presentation)
                SettingsDivider()
                SettingsRowFrame(Modifier.clearAndSetSemantics {
                    contentDescription = addressLabel
                    stateDescription = monitor.server.host
                }) {
                    LabeledContent(addressLabel, Modifier.weight(1f)) {
                        SelectionContainer {
                            Text(
                                monitor.server.host,
                                style = SettingsText.title.copy(fontFamily = OpenCodeFonts.monospace),
                                color = OpenCodeTheme.colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                }
                SettingsDivider()
                SettingsValueRow(stringResource(R.string.settings_server_connection), monitor.route.title)
                monitor.version?.let { version ->
                    SettingsDivider()
                    SettingsValueRow(stringResource(R.string.settings_server_version), version)
                }
                when (presentation.status) {
                    ConnectionHealth.Status.offline -> {
                        SettingsDivider()
                        SettingsButtonRow(stringResource(CoreR.string.common_retry), onClick = { monitor.retry { send(SettingsDelegate.RetryConnection) } })
                    }
                    ConnectionHealth.Status.signInFailed -> {
                        SettingsDivider()
                        SettingsButtonRow(stringResource(R.string.settings_server_edit_server), onClick = { send(SettingsDelegate.EditServer(current)) })
                    }
                    else -> Unit
                }
            }
        }
        item(key = "server-servers", contentType = "settings-section") {
            SettingsServersSection(
                current = monitor.server,
                status = status,
                connectionErrorMessage = error,
                // A refused password already offers Edit Server… above.
                offersEditCurrent = presentation.status != ConnectionHealth.Status.signInFailed,
                send = send,
                onSwitch = { target ->
                    // Back on the card, the switch plays out on its trace: the old
                    // line sweeps clear, then the new server's first beat names it.
                    store.send(SettingsFeature.Action.BackTapped)
                    send(SettingsDelegate.SwitchServer(target))
                },
            )
        }
        item(key = "server-rename", contentType = "settings-section") {
            SettingsSection {
                SettingsButtonRow(stringResource(R.string.settings_server_rename_server_ellipsis), onClick = {
                    nameDraft = current.name ?: ""
                    renaming = true
                })
            }
        }
    }

    if (renaming) {
        OpenCodeAlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.settings_server_rename_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_server_rename_message, ai.opencode.core.ui.ThisDevice.name, current.friendlyName))
                    OpenCodeTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        placeholder = current.friendlyName,
                        trailingIcon = AlertTextFields.clearButton(nameDraft) { nameDraft = "" },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    send(SettingsDelegate.RenameServer(current, nameDraft))
                }) { Text(stringResource(CoreR.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

/**
 * The page's Status row: glyph, then the word and round trip. TalkBack re-reads a focused node
 * whenever what it speaks changes, so the spoken round trip follows the live one only while
 * TalkBack is elsewhere and holds while TalkBack is on it; a new status word is still read.
 */
@Composable
private fun StatusRow(presentation: ConnectionStatusPresentation) {
    val color = presentation.foreground()
    val word = rememberSettledSpokenWord(presentation.spokenWord)
    val focus = remember { AccessibilityFocusTarget() }
    val spoken = remember { SpokenStatus() }
    val live = if (word == presentation.spokenWord) presentation.spokenStatus else word
    if (spoken.word != word || !focus.isFocused) {
        spoken.word = word
        spoken.text = live
    }
    val text = spoken.text
    val statusLabel = stringResource(R.string.settings_server_status)
    SettingsRowFrame(Modifier.accessibilityFocusTarget(focus).clearAndSetSemantics {
        contentDescription = statusLabel
        stateDescription = text
    }) {
        LabeledContent(statusLabel, Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                OpenCodeIcon(presentation.symbol, contentDescription = null, size = 18.dp, tint = color)
                val body = SettingsText.title.copy(fontFeatureSettings = "tnum")
                val milliseconds = presentation.milliseconds
                if (milliseconds != null) {
                    RollingNumber(
                        text = stringResource(R.string.settings_server_status_milliseconds, presentation.word, milliseconds),
                        style = body,
                        color = color,
                        motion = millisecondsRoll,
                        alignment = RollingNumberAlignment.End,
                    )
                } else {
                    Text(presentation.text, style = body, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** What the Status row last gave TalkBack, and the status word it was for; read and written while it composes. */
private class SpokenStatus {
    var word: String? = null
    var text: String = ""
}

/**
 * The label leading and the value trailing on one line when both fit at their full widths;
 * otherwise the value stacks under the label, leading, so the label never breaks inside a word.
 * Once stacked it stays stacked while shown at that width and text size, so a value that
 * changes width (Status's round trip, 9 ↔ 10 ms) can't flip the row and move every row below it.
 */
@Composable
private fun LabeledContent(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    val stacked = remember(LocalDensity.current) { StackedWidth() }
    Layout(
        content = {
            Text(label, style = SettingsText.title, color = OpenCodeTheme.colors.text)
            Box { value() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (labelPart, valuePart) = measurables
        val width = constraints.maxWidth
        val spacing = 16.dp.roundToPx()
        // Each measured once, at the full width: what fits beside the other keeps its natural width.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labelPlaced = labelPart.measure(loose)
        val valuePlaced = valuePart.measure(loose)
        val stacks = stacked.width == width || labelPlaced.width + spacing + valuePlaced.width > width
        if (stacks) stacked.width = width
        if (!stacks) {
            val height = maxOf(labelPlaced.height, valuePlaced.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaced.placeRelative(0, (height - labelPlaced.height) / 2)
                valuePlaced.placeRelative(width - valuePlaced.width, (height - valuePlaced.height) / 2)
            }
        } else {
            val gap = 2.dp.roundToPx()
            val height = (labelPlaced.height + gap + valuePlaced.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaced.placeRelative(0, 0)
                valuePlaced.placeRelative(0, labelPlaced.height + gap)
            }
        }
    }
}

/** The width a [LabeledContent] row stacked at; read and written only while it measures. */
private class StackedWidth {
    var width = -1
}
