package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.inboxSidebarArchivedRows
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.style.drawerCanvas
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant

/**
 * Archived and its count, under Catch Up while the server has archived sessions; a tap opens the
 * Archived page. Lined up with Catch Up: the regular list's shortcut row, or the inbox's.
 */
@Composable
internal fun ArchivedEntryRow(count: Int, regularList: Boolean, onClick: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val start = if (regularList) DrawerMetrics.textInset else InboxSidebarMetrics.edge
    val end = if (regularList) DrawerMetrics.trailingInset else InboxSidebarMetrics.edge
    val font = if (regularList) DrawerTunables.rowFont.font else OpenCodeTextStyles.body
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (regularList) Modifier.padding(horizontal = DrawerMetrics.rowEdge) else Modifier)
            .clip(RoundedCornerShape(if (regularList) DrawerMetrics.rowCornerRadius else 0.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = if (regularList) DrawerMetrics.rowHeight else 48.dp)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (regularList) 8.dp else 12.dp),
    ) {
        Box(if (regularList) Modifier.width(22.dp) else Modifier, contentAlignment = Alignment.CenterStart) {
            OpenCodeIcon(SFSymbol.Archivebox, contentDescription = null, tint = colors.secondaryText)
        }
        Text(stringResource(R.string.drawer_archived), style = font, color = colors.text, modifier = Modifier.weight(1f))
        Text("$count", style = font.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
    }
}

/**
 * The Archived page over the drawer's list: back, the title and Search Archived (the server
 * searches, as on desktop), then the archived sessions. A tap opens one; a hold, or a tap
 * while selecting, chooses it, and the selection bar ([header]) takes the title's place.
 */
@Composable
internal fun ArchivedPage(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    checkedIDs: Set<String>,
    select: (sessionID: String) -> Unit,
    held: (sessionID: String) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val colors = OpenCodeTheme.colors
    val rows by store.observe { it.inboxSidebarArchivedRows() }
    val search by store.observe { it.inboxSidebarStatus.archivedSearch }
    val now = Instant.now()
    Column(modifier.fillMaxSize().background(colors.drawerCanvas).padding(top = contentPadding.calculateTopPadding())) {
        Box(Modifier.fillMaxWidth().padding(horizontal = DrawerMetrics.rowEdge, vertical = 4.dp)) {
            if (header != null) {
                header()
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    OpenCodeIconButton(SFSymbol.ChevronBackward, contentDescription = stringResource(CoreR.string.common_back), onClick = onBack, style = OpenCodeIconButtonStyle.Standard)
                    Text(
                        stringResource(R.string.drawer_archived),
                        style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
                        color = colors.text,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            item(key = "archived-search") {
                OpenCodeTextField(
                    value = search,
                    onValueChange = { store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedSearchChanged(it))) },
                    placeholder = stringResource(R.string.drawer_archived_search_placeholder),
                    leadingIcon = { OpenCodeIcon(SFSymbol.Magnifyingglass, contentDescription = null, size = 18.dp, tint = colors.secondaryText) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 8.dp),
                )
            }
            items(rows, key = { it.id }) { row ->
                InboxSidebarSessionRow(
                    row = row,
                    now = now,
                    selected = false,
                    checked = if (checkedIDs.isEmpty()) null else row.id in checkedIDs,
                    select = { select(row.id) },
                    held = { held(row.id) },
                )
            }
            if (rows.isEmpty()) {
                item(key = "archived-empty") {
                    Text(
                        if (search.isBlank()) stringResource(R.string.drawer_archived_empty) else stringResource(R.string.drawer_no_results),
                        style = OpenCodeTextStyles.body,
                        color = colors.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InboxSidebarMetrics.edge, vertical = 24.dp),
                    )
                }
            }
        }
    }
}

/**
 * The Archived page's slide (Material's shared axis X): [progress] runs 0 (the list) to 1 (the
 * page), and [back] follows a predictive back drag from 0 to 1.
 */
@Stable
internal class ArchivedTransition {
    val progress = Animatable(0f)
    val back = Animatable(0f)
    val isVisible: Boolean get() = progress.value > 0f
}

@Composable
internal fun rememberArchivedTransition(shown: Boolean, reduceMotion: Boolean): ArchivedTransition {
    val transition = remember { ArchivedTransition() }
    LaunchedEffect(shown) {
        if (shown) {
            transition.back.snapTo(0f)
            transition.progress.animateTo(1f, MotionRole.Layout.animation(MotionPhase.Enter, reduceMotion))
        } else {
            transition.progress.animateTo(0f, MotionRole.Collapse.animation(MotionPhase.Exit, reduceMotion))
            transition.back.snapTo(0f)
        }
    }
    return transition
}

private val ArchivedSlide = 30.dp

/** How far [value] has gone from [from] to [to], from 0 to 1. */
private fun part(value: Float, from: Float, to: Float): Float = ((value - from) / (to - from)).coerceIn(0f, 1f)

/** The list and its title band: they slide a little left and fade out; a back drag shows them again. */
internal fun Modifier.archivedBeneath(transition: ArchivedTransition, reduceMotion: Boolean): Modifier = graphicsLayer {
    val p = transition.progress.value
    if (p == 0f) return@graphicsLayer
    val back = part(transition.back.value, 0f, 0.3f)
    if (!reduceMotion) translationX = -ArchivedSlide.toPx() * p * (1f - back)
    alpha = maxOf(1f - part(p, 0f, 0.35f), back)
}

/** The page: it slides in from the right; a back drag shrinks it toward 90% and rounds its corners. */
internal fun Modifier.archivedPage(transition: ArchivedTransition, reduceMotion: Boolean): Modifier = graphicsLayer {
    val p = transition.progress.value
    alpha = part(p, 0.35f, 1f)
    if (reduceMotion) return@graphicsLayer
    // The system reports about a third of the way by mid-screen, so the page is fully shrunk by half way.
    val back = part(transition.back.value, 0f, 0.5f)
    val scale = 1f - 0.1f * back
    translationX = ArchivedSlide.toPx() * (1f - p) + 24.dp.toPx() * back
    scaleX = scale
    scaleY = scale
    if (back > 0f) {
        clip = true
        shape = RoundedCornerShape((16f * part(back, 0f, 0.2f) / scale).dp)
    }
}
