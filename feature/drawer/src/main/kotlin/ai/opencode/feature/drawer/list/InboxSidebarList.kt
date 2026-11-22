package ai.opencode.feature.drawer.list

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.InboxSidebarRow
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.catchUpSessions
import ai.opencode.feature.drawer.inboxSidebarRows
import ai.opencode.feature.drawer.style.DrawerHeaderScrollState
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The drawer's sessions as an inbox, one item per session. Tapping opens the session; holding it
 * chooses it ([DrawerSelection]).
 *
 * A sideways swipe stays the drawer's, as in any navigation drawer; the actions are in selection
 * mode's bar. Archived sessions are a page of their own ([ArchivedEntryRow], [ArchivedPage]).
 */
@Composable
internal fun InboxSidebarList(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    selectedSessionID: String?,
    headerScroll: DrawerHeaderScrollState,
    contentPadding: PaddingValues,
    checkedIDs: Set<String>,
    select: (String) -> Unit,
    held: (String) -> Unit,
    openCatchUp: () -> Unit,
    openArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by store.observe { it.inboxSidebarRows() }
    val archivedCount by store.observe { it.inboxSidebarStatus.archived.size }
    val catchUpCount by store.observe { it.catchUpSessions.size }
    val snapshotLoaded by store.observe { it.snapshotLoaded }
    val catchUpSessionCount = pluralStringResource(R.plurals.drawer_catch_up_session_count, catchUpCount, catchUpCount)
    // Ages tick over on the minute.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now.toEpochMilli() % 60_000L)
            now = Instant.now()
        }
    }
    val listState = rememberLazyListState()
    // As in DrawerListView: a list resting at its top stays there when a row arrives above the
    // first one (Catch Up, a session that just got busy); LazyColumn would keep
    // the old first row anchored by key and leave the new ones scrolled under the header.
    val restsAtTop = remember { booleanArrayOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { restsAtTop[0] = it }
    }
    val firstKey = when {
        catchUpCount > 0 -> "inbox-catch-up"
        else -> rows.firstOrNull()?.id
    }
    val shownFirstKey = remember { arrayOfNulls<String>(1) }
    if (shownFirstKey[0] != firstKey) {
        if (shownFirstKey[0] != null && restsAtTop[0]) listState.requestScrollToItem(0, 0)
        shownFirstKey[0] = firstKey
    }
    val density = LocalDensity.current
    // The header's edge follows how far rows have scrolled beneath the title.
    LaunchedEffect(listState, headerScroll) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, px) ->
            val fade = max(DrawerTunables.fade.value, 1f)
            val scrolled = if (index == 0) min(px / density.density, fade) else fade
            headerScroll.update(((scrolled * 2).roundToInt() / 2f).dp)
        }
    }
    val colors = OpenCodeTheme.colors
    Box(modifier) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            if (catchUpCount > 0) {
                item(key = "inbox-catch-up") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClick = openCatchUp)
                            .openCodeTapTarget()
                            .semantics { stateDescription = catchUpSessionCount }
                            .testTag("inbox-catch-up")
                            .padding(horizontal = InboxSidebarMetrics.edge),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OpenCodeIcon(SFSymbol.RectangleStack, contentDescription = null, tint = colors.signal)
                        Text(stringResource(R.string.drawer_catch_up_title), style = OpenCodeTextStyles.body, color = colors.text, modifier = Modifier.weight(1f))
                        Text("$catchUpCount", style = OpenCodeTextStyles.body.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
                    }
                }
            }
            if (archivedCount > 0) {
                item(key = "inbox-archived") {
                    ArchivedEntryRow(archivedCount, regularList = false, onClick = openArchived)
                }
            }
            items(rows, key = { it.id }) { row ->
                InboxSidebarSessionRow(
                    row = row,
                    now = now,
                    selected = row.id == selectedSessionID,
                    checked = if (checkedIDs.isEmpty()) null else row.id in checkedIDs,
                    select = { select(row.id) },
                    held = { held(row.id) },
                )
            }
        }
        if (rows.isEmpty() && snapshotLoaded) {
            Column(
                Modifier.align(Alignment.Center).padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeIcon(SFSymbol.Tray, contentDescription = null, size = 40.dp, tint = colors.secondaryText)
                Text(stringResource(R.string.drawer_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.text)
            }
        }
    }
}

/**
 * One session: a tap opens it (or, while selecting, chooses it) and a hold chooses it, as on the
 * regular drawer's rows ([drawerRowInteraction]). A sideways swipe is left to the drawer, so a
 * left swipe closes it.
 */
@Composable
internal fun InboxSidebarSessionRow(
    row: InboxSidebarRow,
    now: Instant,
    selected: Boolean,
    checked: Boolean?,
    select: () -> Unit,
    held: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val hold = remember(row.id) { DrawerRowHoldState() }
    val currentSelect by rememberUpdatedState(select)
    val currentHeld by rememberUpdatedState(held)
    // While the drawer selects, the chosen rows' tint stands in for the open session's.
    val openTint = if (checked == null && selected) colors.text.copy(alpha = 0.08f) else Color.Transparent
    val checkedTint = checkedRowTint()
    val checkedTintAlpha = rememberCheckedTintAlpha(checked == true)
    val selectLabel = stringResource(R.string.drawer_row_select_action)
    val selectedText = stringResource(R.string.drawer_row_selected)
    val notSelectedText = stringResource(R.string.drawer_row_not_selected)
    Box(
        Modifier
            .fillMaxWidth()
            .background(openTint)
            .drawBehind {
                val tintAlpha = checkedTintAlpha()
                if (tintAlpha > 0f) drawRect(checkedTint.copy(alpha = checkedTint.alpha * tintAlpha))
            }
            .drawerRowInteraction(
                interaction = DrawerRowInteraction(
                    sessionID = row.id,
                    reorderable = false,
                    select = { currentSelect() },
                    pressed = {},
                    held = { currentHeld() },
                ),
                hold = hold,
                list = { null },
                row = { null },
            )
            .semantics(mergeDescendants = true) {
                testTag = "inbox-row-${row.id}"
                role = Role.Button
                onClick { currentSelect(); true }
                if (checked == null) onLongClick(selectLabel) { currentHeld(); true }
                when (checked) {
                    true -> stateDescription = selectedText
                    false -> stateDescription = notSelectedText
                    null -> Unit
                }
            },
    ) {
        InboxSidebarRowView(row, now, checked)
    }
}

/** Dot, title and age, project, then what the session needs. */
@Composable
internal fun InboxSidebarRowView(row: InboxSidebarRow, now: Instant, checked: Boolean?) {
    val colors = OpenCodeTheme.colors
    val age = InboxSidebarRow.age(row.updatedAt, now)
    val needsInputText = stringResource(R.string.drawer_inbox_needs_input)
    val workingText = stringResource(R.string.drawer_inbox_working)
    val unreadText = stringResource(R.string.drawer_inbox_unread)
    val description = buildList {
        row.project?.let(::add)
        when (val line = row.line) {
            InboxSidebarRow.Line.NeedsInput -> add(needsInputText)
            InboxSidebarRow.Line.Working -> add(workingText)
            is InboxSidebarRow.Line.Reply -> add(line.text)
            InboxSidebarRow.Line.None -> Unit
        }
        if (row.unread) add(unreadText)
        add(age)
    }.joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = InboxSidebarMetrics.edge, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = row.title
                stateDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The check takes the unread dot's place while the drawer selects.
        SelectionLead(
            checked = checked,
            restWidth = 8.dp,
            selectWidth = 18.dp,
            checkSize = 18.dp,
            modifier = Modifier.padding(top = 2.dp).height(18.dp),
        ) {
            Box(Modifier.size(8.dp).background(if (row.unread) colors.signal else Color.Transparent, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Text + Spacer(minLength: 8) + age. The title takes all the width the age leaves (a
            // second weighted spacer would cap it at half), so the age stays trailing.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    style = OpenCodeTextStyles.body,
                    fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(age, style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
            }
            row.project?.let { project ->
                Text(project, style = OpenCodeTextStyles.subheadline, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when (val line = row.line) {
                InboxSidebarRow.Line.NeedsInput -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, null, size = 16.dp, tint = OpenCodeTheme.colors.warning)
                    Text(needsInputText, style = OpenCodeTextStyles.subheadline, fontWeight = FontWeight.Medium, color = colors.text)
                }
                InboxSidebarRow.Line.Working -> Text(workingText, style = OpenCodeTextStyles.subheadline, color = colors.secondaryText)
                is InboxSidebarRow.Line.Reply -> Text(line.text, style = OpenCodeTextStyles.subheadline, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InboxSidebarRow.Line.None -> Unit
            }
        }
    }
}

/** The Inbox Sidebar switch as the drawer reads it (off by default). */
@Composable
internal fun rememberInboxSidebarOn(): Boolean {
    val feature: ExperimentalFeature = InboxSidebarFeature.experiment
    val stored by remember(feature) { shared(feature.key).flow }.collectAsState()
    return feature.isActive(stored)
}

internal object InboxSidebarMetrics {
    val edge = 20.dp
}
