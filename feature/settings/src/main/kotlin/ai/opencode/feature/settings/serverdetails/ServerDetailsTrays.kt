package ai.opencode.feature.settings.serverdetails

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsText
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.server.model.OpenCodeAccount
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeMCPServer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The sheet a Server Details tile or banner opens, one job per tray. Skills open full height; the
 * rest at half height first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerDetailsTray(
    state: ServerDetailsFeature.State,
    tray: ServerDetailsFeature.Tray,
    send: (ServerDetailsFeature.Action) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = tray == ServerDetailsFeature.Tray.Skills)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { send(ServerDetailsFeature.Action.TrayChanged(null)) }
    }
    ModalBottomSheet(
        onDismissRequest = { send(ServerDetailsFeature.Action.TrayChanged(null)) },
        sheetState = sheetState,
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        when (tray) {
            ServerDetailsFeature.Tray.Mcp -> MCPServersTray(state, send, close)
            ServerDetailsFeature.Tray.Accounts -> AccountsTray(state.capabilities?.accounts ?: emptyList(), close)
            ServerDetailsFeature.Tray.Skills -> SkillsTray(state.capabilities?.skills ?: emptyList(), close)
            is ServerDetailsFeature.Tray.Server -> MCPServerTray(state, tray.name, send, close, onBack = null)
        }
    }
}

/** The tray's inline title bar: Back inside the MCP list, the title and subtitle, Close. */
@Composable
private fun TrayHeader(title: String?, subtitle: String?, onClose: () -> Unit, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp)) {
            if (onBack != null) {
                OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = stringResource(CoreR.string.common_back), onClick = onBack, style = OpenCodeIconButtonStyle.Standard)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) Text(title, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
            }
        }
        Box(Modifier.width(48.dp)) {
            OpenCodeIconButton(SFSymbol.Xmark, contentDescription = stringResource(CoreR.string.common_close), onClick = onClose, style = OpenCodeIconButtonStyle.Standard)
        }
    }
}

/** The inset-grouped card a tray's rows sit on. */
@Composable
private fun TrayCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(horizontal = OpenCodeRadius.card).fillMaxWidth().clip(RoundedCornerShape(OpenCodeRadius.panel)).background(OpenCodeTheme.colors.groupedCard)) {
        content()
    }
}

// MCP servers

@Composable
private fun MCPServersTray(
    state: ServerDetailsFeature.State,
    send: (ServerDetailsFeature.Action) -> Unit,
    close: () -> Unit,
) {
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = detail != null) { detail = null }
    val reduceMotion = rememberReduceMotion()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slidePx = with(LocalDensity.current) { PageTransitions.SlideDistance.roundToPx() }
    // The server's page comes and goes on Material's shared axis, as Settings' pages do.
    AnimatedContent(
        targetState = detail,
        transitionSpec = {
            val pushing = targetState != null
            if (pushing) {
                PageTransitions.forward(slidePx, rtl, reduceMotion)
            } else {
                PageTransitions.backward(slidePx, rtl, reduceMotion)
            }.using(
                // The sheet keeps its height through the move: it grows at once for a taller page
                // and shrinks only once the move is done.
                SizeTransform(clip = true) { initial, target ->
                    if (target.height >= initial.height) snap() else snap(delayMillis = Motion.millis(PageTransitions.DURATION_SECONDS))
                },
            )
        },
        label = "mcp-tray-push",
    ) { name ->
        // Each page is opaque and reaches to the sheet's bottom, as a NavigationStack page fills
        // its detent: the page beneath never shows through or below it. The sheet's clip
        // (SizeTransform) bounds the paint.
        val canvas = OpenCodeTheme.colors.groupedCanvas
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(canvas, size = Size(size.width, size.height + PageUnderpaint.toPx())) },
        ) {
            if (name != null) {
                MCPServerTray(state, name, send, close, onBack = { detail = null })
            } else {
                MCPServersList(state, send, close) { detail = it }
            }
        }
    }
}

/** How far below its content a tray page paints its canvas (more than any sheet is tall). */
private val PageUnderpaint = 2000.dp

@Composable
private fun MCPServersList(
    state: ServerDetailsFeature.State,
    send: (ServerDetailsFeature.Action) -> Unit,
    close: () -> Unit,
    open: (String) -> Unit,
) {
    val servers = state.mcpServers
    val connected = servers.count { it.status == OpenCodeMCPServer.Status.Connected }
    val reduceMotion = rememberReduceMotion()
    Column(Modifier.fillMaxWidth()) {
        TrayHeader("MCP Servers", "$connected of ${servers.size} connected", close)
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            itemsIndexed(servers, key = { _, mcp -> mcp.name }) { index, mcp ->
                val shape = when {
                    servers.size == 1 -> RoundedCornerShape(OpenCodeRadius.panel)
                    index == 0 -> RoundedCornerShape(topStart = OpenCodeRadius.panel, topEnd = OpenCodeRadius.panel)
                    index == servers.lastIndex -> RoundedCornerShape(bottomStart = OpenCodeRadius.panel, bottomEnd = OpenCodeRadius.panel)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.animateItem(MotionRole.Layout.animation(reduceMotion = reduceMotion), MotionRole.Layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntOffset.VisibilityThreshold), MotionRole.Layout.animation(reduceMotion = reduceMotion)).padding(horizontal = 16.dp).clip(shape).background(OpenCodeTheme.colors.groupedCard)) {
                    MCPServerRow(
                        mcp = mcp,
                        onOpen = { open(mcp.name) },
                        action = { ServerDetailsFeature.Action.primaryAction(mcp)?.let(send) },
                    )
                    if (index != servers.lastIndex) SettingsDivider(inset = true)
                }
            }
        }
    }
}

@Composable
private fun MCPServerRow(mcp: OpenCodeMCPServer, onOpen: () -> Unit, action: () -> Unit) {
    val brand = ServerBrand.mcp(mcp.name)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusRingTile(brand, mcp.status)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(brand.name, style = SettingsText.title.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.text)
            Text(mcp.status.title, style = OpenCodeTextStyles.subheadline, color = mcp.status.tintColor())
        }
        if (mcp.status == OpenCodeMCPServer.Status.Pending) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        } else {
            CapsuleAction(actionTitle(mcp.status), actionKind(mcp.status), action)
        }
    }
}

internal fun actionTitle(status: OpenCodeMCPServer.Status): String = when (status) {
    is OpenCodeMCPServer.Status.NeedsAuth -> "Sign In"
    is OpenCodeMCPServer.Status.Failed -> "Retry"
    OpenCodeMCPServer.Status.Connected -> "Disconnect"
    else -> "Connect"
}

internal fun actionKind(status: OpenCodeMCPServer.Status): CapsuleKind = when (status) {
    is OpenCodeMCPServer.Status.NeedsAuth -> CapsuleKind.Prominent
    is OpenCodeMCPServer.Status.Failed -> CapsuleKind.Inverse
    OpenCodeMCPServer.Status.Connected -> CapsuleKind.Neutral
    else -> CapsuleKind.Tinted
}

/** One MCP server: its status, the full error when it failed, and one action. */
@Composable
private fun MCPServerTray(
    state: ServerDetailsFeature.State,
    name: String,
    send: (ServerDetailsFeature.Action) -> Unit,
    close: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val mcp = state.mcpServers.firstOrNull { it.name == name }
    Column(Modifier.fillMaxWidth()) {
        TrayHeader(null, null, close, onBack)
        if (mcp == null) {
            OpenCodeContentUnavailable(SFSymbol.ServerRack, "Server Removed", Modifier.fillMaxWidth().padding(vertical = 16.dp))
            return@Column
        }
        val brand = ServerBrand.mcp(mcp.name)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusRingTile(brand, mcp.status, size = 48.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(brand.name, style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.Bold), color = OpenCodeTheme.colors.text)
                    Text(
                        mcp.status.title,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = mcp.status.tintColor(),
                    )
                }
            }

            when (mcp.status) {
                is OpenCodeMCPServer.Status.Failed -> mcp.errorText?.let { error ->
                    val red = SettingsTint.Red.color()
                    val shape = RoundedCornerShape(OpenCodeRadius.card)
                    SelectionContainer(
                        Modifier.fillMaxWidth().background(red.copy(alpha = 0.1f), shape).border(0.5.dp, red.copy(alpha = 0.25f), shape).padding(14.dp),
                    ) {
                        Text(
                            error,
                            style = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace),
                            color = OpenCodeTheme.colors.text,
                        )
                    }
                }
                is OpenCodeMCPServer.Status.NeedsAuth -> Text(
                    // Android opens the default browser.
                    "Its tools work once you sign in. Sign In opens ${brand.name} in your browser.",
                    style = SettingsText.title,
                    color = OpenCodeTheme.colors.secondaryText,
                )
                else -> Unit
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Config Name", style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
                Spacer(Modifier.weight(1f))
                Text(
                    mcp.name,
                    style = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }

            when (mcp.status) {
                OpenCodeMCPServer.Status.Pending -> OpenCodeSecondaryButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                }
                OpenCodeMCPServer.Status.Connected -> OpenCodeSecondaryButton(
                    "Disconnect",
                    onClick = { send(ServerDetailsFeature.Action.DisconnectTapped(mcp.name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> OpenCodePrimaryButton(
                    actionTitle(mcp.status),
                    onClick = { ServerDetailsFeature.Action.primaryAction(mcp)?.let(send) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// Accounts

@Composable
private fun AccountsTray(accounts: List<OpenCodeAccount>, close: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TrayHeader("Accounts", "Sign-ins this server holds", close)
        if (accounts.isEmpty()) {
            OpenCodeContentUnavailable(SFSymbol.PersonCropCircle, "No Accounts", Modifier.fillMaxWidth().padding(vertical = 16.dp))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            item(key = "accounts") {
                TrayCard {
                    Column {
                        accounts.forEachIndexed { index, account ->
                            AccountRow(account)
                            if (index != accounts.lastIndex) SettingsDivider(inset = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: OpenCodeAccount) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(ServerBrand.account(account), size = 36.dp, round = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(account.provider, style = SettingsText.title.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.text)
            account.subtitle?.let {
                Text(it, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            account.methodTitle,
            style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.background(tertiarySystemFill(), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

val OpenCodeAccount.methodTitle: String
    get() = when (method) {
        OpenCodeAccount.Method.OAuth -> "Sign-In"
        OpenCodeAccount.Method.Key -> "API Key"
        OpenCodeAccount.Method.External -> "External"
        OpenCodeAccount.Method.Environment -> "Environment"
    }

/** A Console workspace, a key's variable, or nothing for “default”. */
val OpenCodeAccount.subtitle: String?
    get() {
        if (method == OpenCodeAccount.Method.Environment) return if (label.isEmpty()) null else "Key from $label"
        return if (label.isEmpty() || label == "default") null else label
    }

// Skills

@Composable
private fun SkillsTray(skills: List<OpenCodeAutocompleteSkill>, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isEmpty()) {
        skills
    } else {
        skills.filter { it.name.contains(query, ignoreCase = true) || (it.description ?: "").contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize()) {
        TrayHeader("Skills", "${skills.size} on this server", close)
        OpenCodeSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search Skills",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (shown.isEmpty()) {
            OpenCodeContentUnavailable(SFSymbol.BookClosed, if (query.isEmpty()) "No Skills" else "No Results", Modifier.fillMaxWidth().padding(vertical = 16.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            itemsIndexed(shown, key = { _, skill -> skill.id }) { index, skill ->
                val shape = when {
                    shown.size == 1 -> RoundedCornerShape(OpenCodeRadius.panel)
                    index == 0 -> RoundedCornerShape(topStart = OpenCodeRadius.panel, topEnd = OpenCodeRadius.panel)
                    index == shown.lastIndex -> RoundedCornerShape(bottomStart = OpenCodeRadius.panel, bottomEnd = OpenCodeRadius.panel)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(OpenCodeTheme.colors.groupedCard)) {
                    Column(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            skill.name,
                            style = SettingsText.title.copy(fontFamily = OpenCodeFonts.monospace, fontWeight = FontWeight.Medium),
                            color = OpenCodeTheme.colors.text,
                        )
                        val description = skill.description
                        if (!description.isNullOrEmpty()) {
                            Text(
                                description,
                                style = OpenCodeTextStyles.subheadline,
                                color = OpenCodeTheme.colors.secondaryText,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                    if (index != shown.lastIndex) SettingsDivider()
                }
            }
        }
    }
}
