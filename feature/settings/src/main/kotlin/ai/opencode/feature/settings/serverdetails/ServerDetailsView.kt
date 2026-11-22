package ai.opencode.feature.settings.serverdetails

import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.store.scope
import ai.opencode.core.ui.AlertTextFields
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPageScaffold
import ai.opencode.feature.settings.SettingsStore
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeMCPServer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.sp

/** The page's store: Settings scoped to its Server Details child (null while the page leaves). */
internal typealias ServerDetailsStore = StoreOf<ServerDetailsFeature.State?, ServerDetailsFeature.Action>

/**
 * Problems as banners with their action, then three tiles (MCP servers, Accounts, Skills) that each
 * open a tray with the full list. Values on a 4 dp grid; radii 26 tiles, 22 banners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailsPage(store: SettingsStore) {
    val details: ServerDetailsStore = remember(store) {
        store.scope(
            state = { it.serverDetails },
            action = { action: ServerDetailsFeature.Action -> SettingsFeature.Action.ServerDetails(action) },
        )
    }
    val stateOrNull by details.collectState()
    // A switch gets a fresh page and read.
    val serverID = stateOrNull?.server?.persistenceID
    LaunchedEffect(details, serverID) { if (serverID != null) details.send(ServerDetailsFeature.Action.Task) }
    DisposableEffect(details) { onDispose { details.send(ServerDetailsFeature.Action.Disappeared) } }
    val send = details::send

    val refresh = rememberPullToRefreshState()
    val reduceMotion = rememberReduceMotion()
    // The pull-to-refresh spinner shows until the pulled read ends, not for other reloads.
    var pulled by remember { mutableStateOf(false) }
    val isLoading = stateOrNull?.isLoading == true
    LaunchedEffect(isLoading) { if (!isLoading) pulled = false }
    val isRefreshing = pulled && isLoading

    Box(
        Modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = refresh,
            enabled = stateOrNull != null,
            onRefresh = {
                pulled = true
                send(ServerDetailsFeature.Action.RefreshPulled)
            },
        ),
    ) {
        SettingsPageScaffold(title = "Server Details", store = store) {
            val state = stateOrNull ?: return@SettingsPageScaffold
            val capabilities = state.capabilities
            when {
                capabilities != null -> {
                    state.mcpError?.let { error ->
                        item(key = "mcp-error") {
                            Row(
                                Modifier.animateItem(errorFade(MotionPhase.Enter, reduceMotion), errorPlacement(MotionPhase.Enter, reduceMotion), errorFade(MotionPhase.Exit, reduceMotion)).fillMaxWidth().padding(horizontal = 20.dp).padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 16.dp, tint = SettingsTint.Red.color())
                                Text(error, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium), color = SettingsTint.Red.color())
                            }
                        }
                    }
                    state.problems.forEach { mcp ->
                        item(key = "problem-${mcp.name}") {
                            ProblemBanner(
                                mcp = mcp,
                                onTap = { send(ServerDetailsFeature.Action.TrayChanged(ServerDetailsFeature.Tray.Server(mcp.name))) },
                                action = { ServerDetailsFeature.Action.primaryAction(mcp)?.let(send) },
                                modifier = Modifier.animateItem(listFade(reduceMotion), listPlacement(reduceMotion), listFade(reduceMotion)).tileRow(),
                            )
                        }
                    }
                    item(key = "mcp-tile") {
                        McpTile(state.mcpServers, { send(ServerDetailsFeature.Action.TrayChanged(ServerDetailsFeature.Tray.Mcp)) }, Modifier.animateItem(listFade(reduceMotion), listPlacement(reduceMotion), listFade(reduceMotion)).tileRow())
                    }
                    item(key = "accounts-skills") {
                        Row(Modifier.animateItem(listFade(reduceMotion), listPlacement(reduceMotion), listFade(reduceMotion)).tileRow(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            AccountsTile(capabilities.accounts, { send(ServerDetailsFeature.Action.TrayChanged(ServerDetailsFeature.Tray.Accounts)) }, Modifier.weight(1f))
                            SkillsTile(capabilities.skills, { send(ServerDetailsFeature.Action.TrayChanged(ServerDetailsFeature.Tray.Skills)) }, Modifier.weight(1f))
                        }
                    }
                }
                state.error != null -> item(key = "error") {
                    OpenCodeContentUnavailable(
                        symbol = SFSymbol.ExclamationmarkTriangle,
                        title = "Couldn’t Load Server Details",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        description = state.error,
                        action = "Try Again",
                        onAction = { send(ServerDetailsFeature.Action.RefreshPulled) },
                    )
                }
                else -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        PullToRefreshDefaults.Indicator(
            state = refresh,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp),
        )
    }

    val state = stateOrNull ?: return
    state.tray?.let { tray -> ServerDetailsTray(state, tray, send) }
    if (state.awaitingCode) CodeAlert(instructions = state.signIn?.attempt?.instructions ?: "", send = send)
    HidesFromScreenshots(shown = state.awaitingCode)
}

/** The “Paste the Code” alert for a `code` mode sign-in. */
@Composable
private fun CodeAlert(instructions: String, send: (ServerDetailsFeature.Action) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    OpenCodeAlertDialog(
        onDismissRequest = { send(ServerDetailsFeature.Action.CodeCancelled) },
        title = { Text("Paste the Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (instructions.isNotEmpty()) Text(instructions)
                OpenCodeTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = "Code",
                    trailingIcon = AlertTextFields.clearButton(code) { code = "" },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                send(ServerDetailsFeature.Action.CodeSubmitted(code))
                code = ""
            }) { Text("Sign In") }
        },
        dismissButton = {
            TextButton(onClick = { send(ServerDetailsFeature.Action.CodeCancelled) }) { Text("Cancel") }
        },
    )
}

private fun listPlacement(reduceMotion: Boolean) =
    MotionRole.Layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntOffset.VisibilityThreshold)
private fun listFade(reduceMotion: Boolean) = MotionRole.Layout.animation<Float>(reduceMotion = reduceMotion)

private fun errorPlacement(phase: MotionPhase, reduceMotion: Boolean) =
    MotionRole.Layout.animation(phase, reduceMotion, visibilityThreshold = IntOffset.VisibilityThreshold)
private fun errorFade(phase: MotionPhase, reduceMotion: Boolean) = MotionRole.Layout.animation<Float>(phase, reduceMotion)

private fun Modifier.tileRow(): Modifier = fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 6.dp)

// Tiles

@Composable
private fun McpTile(servers: List<OpenCodeMCPServer>, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val connected = servers.count { it.status == OpenCodeMCPServer.Status.Connected }
    val overflow = servers.size > 6
    val shown = if (overflow) servers.take(5) else servers
    OverviewTile(
        title = "MCP Servers",
        value = "$connected of ${servers.size} connected",
        onTap = onTap,
        modifier = modifier,
        bottomPadding = 20.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                TileLabel("MCP Servers")
                BigCount(connected, total = servers.size, modifier = Modifier.padding(top = 8.dp))
                Text(
                    if (servers.isEmpty()) "No MCP servers" else "connected",
                    style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }
            val cells: List<OpenCodeMCPServer?> = shown + if (overflow) listOf(null) else emptyList()
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                cells.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { mcp ->
                            if (mcp != null) {
                                StatusRingTile(ServerBrand.mcp(mcp.name), mcp.status)
                            } else {
                                Box(
                                    Modifier.size(42.dp).background(OpenCodeTheme.colors.secondaryText.copy(alpha = 0.18f), RoundedCornerShape(13.5.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "+${servers.size - 5}",
                                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                                        color = OpenCodeTheme.colors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountsTile(accounts: List<OpenCodeAccount>, onTap: () -> Unit, modifier: Modifier = Modifier) {
    OverviewTile(title = "Accounts", value = "${accounts.size}", onTap = onTap, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            TileLabel("Accounts")
            BigCount(accounts.size, modifier = Modifier.padding(top = 8.dp))
            Row(
                Modifier.padding(top = 8.dp).height(35.dp),
                horizontalArrangement = Arrangement.spacedBy((-8).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                accounts.take(5).forEach { account ->
                    Box(Modifier.background(OpenCodeTheme.colors.groupedCard, CircleShape).padding(2.5.dp)) {
                        BrandTile(ServerBrand.account(account), size = 30.dp, round = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTile(skills: List<OpenCodeAutocompleteSkill>, onTap: () -> Unit, modifier: Modifier = Modifier) {
    OverviewTile(title = "Skills", value = "${skills.size}", onTap = onTap, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            TileLabel("Skills")
            BigCount(skills.size, modifier = Modifier.padding(top = 8.dp))
            Column(
                Modifier.padding(top = 8.dp).height(35.dp).fillMaxWidth().trailingFade(),
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.Bottom),
            ) {
                skills.take(2).forEach { skill ->
                    Text(
                        skill.name,
                        style = OpenCodeTextStyles.caption1.copy(fontFamily = OpenCodeFonts.monospace),
                        color = OpenCodeTheme.colors.secondaryText,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** A tile: one button for TalkBack, its title as the label and its count as the value. */
@Composable
private fun OverviewTile(
    title: String,
    value: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    // A ripple answers the press.
    val shape = RoundedCornerShape(OpenCodeRadius.panel)
    Box(
        modifier
            .background(OpenCodeTheme.colors.groupedCard, shape)
            .border(0.5.dp, OpenCodeTheme.colors.text.copy(alpha = 0.06f), shape)
            .clip(shape)
            .clickable(onClick = onTap)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = value
                role = Role.Button
            }
            .padding(PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = bottomPadding)),
    ) {
        content()
    }
}

@Composable
private fun TileLabel(title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.secondaryText)
        OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 12.dp, tint = OpenCodeTheme.colors.tertiaryText)
    }
}

@Composable
private fun BigCount(value: Int, modifier: Modifier = Modifier, total: Int? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        RollingNumber(
            value = value,
            style = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold),
            color = OpenCodeTheme.colors.text,
            modifier = Modifier.alignByBaseline(),
        )
        if (total != null) {
            Text(
                "/$total",
                style = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = OpenCodeTheme.colors.tertiaryText,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

// Pieces

/** A problem on top of the page: the server, what's wrong, and its action. */
@Composable
internal fun ProblemBanner(
    mcp: OpenCodeMCPServer,
    onTap: () -> Unit,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needsAuth = mcp.status is OpenCodeMCPServer.Status.NeedsAuth
    val brand = ServerBrand.mcp(mcp.name)
    val tone = mcp.status.tintColor()
    val shape = RoundedCornerShape(OpenCodeRadius.card)
    Row(
        modifier
            .background(OpenCodeTheme.colors.groupedCard, shape)
            .background(tone.copy(alpha = 0.13f), shape)
            .border(0.5.dp, tone.copy(alpha = 0.3f), shape)
            .clickable(onClick = onTap)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(brand, size = 36.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                if (needsAuth) "${brand.name} needs you to sign in" else "${brand.name} couldn’t connect",
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = OpenCodeTheme.colors.text,
                maxLines = 2,
            )
            val error = mcp.shortError
            if (!needsAuth && error != null) {
                Text(
                    error,
                    style = OpenCodeTextStyles.caption1,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().trailingFade(),
                )
            }
        }
        CapsuleAction(if (needsAuth) "Sign In" else "Retry", if (needsAuth) CapsuleKind.Prominent else CapsuleKind.Inverse, action)
    }
}

internal enum class CapsuleKind { Prominent, Inverse, Tinted, Neutral }

/** A capsule action in a row or banner: Sign In, Retry, Connect, Disconnect. */
@Composable
internal fun CapsuleAction(title: String, kind: CapsuleKind, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val blue = SettingsTint.Blue.color()
    val colors = OpenCodeTheme.colors
    val (fill, text) = when (kind) {
        CapsuleKind.Prominent -> blue to Color.White
        CapsuleKind.Inverse -> colors.text to colors.canvas
        CapsuleKind.Tinted -> blue.copy(alpha = 0.15f) to blue
        CapsuleKind.Neutral -> tertiarySystemFill() to colors.text
    }
    // A ripple inside the capsule answers the press.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .openCodeTapTarget(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(32.dp)
                .widthIn(min = 32.dp)
                .background(fill, CircleShape)
                .clip(CircleShape)
                .indication(interaction, ripple())
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = text, maxLines = 1)
        }
    }
}

/** `UIColor.tertiarySystemFill`: rgba(118,118,128) at 0.12 light / 0.24 dark. */
@Composable
internal fun tertiarySystemFill(): Color =
    Color(0x76, 0x76, 0x80).copy(alpha = if (OpenCodeTheme.colors.isDark) 0.24f else 0.12f)

/** A soft right edge for text that runs past its tile (no hard crops). */
internal fun Modifier.trailingFade(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
