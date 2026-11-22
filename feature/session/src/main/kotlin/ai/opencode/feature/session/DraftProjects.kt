package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRollDirection
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.OpenCodeProject
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import kotlin.math.roundToInt

/** The shipped defaults (developer tuning isn't ported). */
object ProjectPickerTunables {
    val rowInset = 16.dp
    val rowPadding = 10.dp
    val pathGap = 4.dp
    val accessoryWidth = 20.dp
    val sectionGap = 12.dp
    val searchGap = 8.dp
}

/**
 * Screen-local slots, not recency. A refresh or another pick must not move a folder out from under
 * the finger. An unlisted choice uses the last slot. A new draft visit or server starts again from
 * its current recency order.
 *
 * Immutable: [update] returns the next order. [identity] is the draft's
 * `SessionFeature.State.TaskIdentity` (typed `Any?` so this file doesn't depend on it).
 */
data class DraftProjectOrder(
    val projects: List<OpenCodeProject> = emptyList(),
    private val identity: Any? = null,
    private val hasIdentity: Boolean = false,
) {
    fun update(
        candidates: List<OpenCodeProject>,
        chosen: OpenCodeProject?,
        identity: Any?,
        limit: Int = RECENT_PROJECT_LIMIT,
    ): DraftProjectOrder {
        var projects = if (!hasIdentity || this.identity != identity) emptyList() else this.projects
        if (projects.isEmpty()) {
            val directories = HashSet<String>()
            projects = candidates.filter { directories.add(it.directory) }.take(limit)
        } else {
            // Refresh names/IDs without changing the folders' screen positions.
            val refreshed = projects.map { project -> candidates.firstOrNull { it.directory == project.directory } ?: project }.toMutableList()
            for (project in candidates) {
                if (refreshed.size >= limit) break
                if (refreshed.none { it.directory == project.directory }) refreshed += project
            }
            projects = refreshed
        }
        if (chosen != null && projects.none { it.directory == chosen.directory }) {
            projects = (if (projects.size == limit) projects.dropLast(1) else projects) + chosen
        }
        return DraftProjectOrder(projects, identity, hasIdentity = true)
    }

    companion object {
        const val RECENT_PROJECT_LIMIT = 5
    }
}

/**
 * The header's project name, replaced with an upward slide-and-fade when it changes; a cross-fade
 * under Reduce Motion.
 */
@Composable
fun ProjectHeaderSubtitle(
    project: String,
    modifier: Modifier = Modifier,
    animates: Boolean = true,
    content: @Composable (String) -> Unit = { Text(it) },
) {
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    AnimatedContent(
        targetState = project,
        modifier = modifier,
        transitionSpec = {
            // A subtitle swapped in place without animation doesn't move.
            if (!animates) (fadeIn(snap()) togetherWith fadeOut(snap())) using null
            else MotionTransition.roll(MotionRollDirection.Up, reducedMotion, density).content
        },
        label = "projectHeaderSubtitle",
    ) { content(it) }
}

/**
 * A draft's recent projects, listed above the composer. The chosen one carries a filled
 * folder, its name in semibold primary, and a quiet highlight. All Projects opens the Project
 * sheet, which also browses the server's folders. Composing, the list folds down to the chosen row,
 * which then opens the sheet: the other rows fade and blur out where they are, riding up with the
 * composer, while the chosen one glides down to sit on it ([DraftProjectStack]). Reduce Motion
 * cross-fades.
 *
 * Values the SessionView group passes from `SessionFeature.State`:
 * @param identity `state.taskIdentity` (a new visit or server restarts the screen order).
 * @param projects `state.recentProjects` (recency order).
 * @param chosen `state.draftProject`; a listed one at its folder counts as it.
 * @param isComposing the composer has text or attachments.
 * @param onPick `.projectPicked(project)`.
 * @param onAll opens the Project sheet (`.projectButtonTapped`).
 */
@Composable
fun DraftProjectList(
    identity: Any?,
    projects: List<OpenCodeProject>,
    chosen: OpenCodeProject?,
    isComposing: Boolean,
    onPick: (OpenCodeProject) -> Unit,
    onAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReduceMotion()
    var order by remember { mutableStateOf(DraftProjectOrder()) }
    val visible = order.update(projects, chosen, identity)
    SideEffect { if (visible != order) order = visible }
    val shown = visible.projects
    val chosenIndex = shown.indexOfFirst { isChosen(it, chosen) }.takeIf { it >= 0 }
    // Folded, the chosen row stays; without one, All Projects (the last) does.
    val kept = chosenIndex ?: shown.size
    val iconWidth = with(LocalDensity.current) { (22 * fontScale).dp }

    // Under Reduce Motion the folded and full lists are separate views that cross-fade.
    val fold by animateFloatAsState(
        targetValue = if (isComposing) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else Motion.layout.animation(if (isComposing) MotionPhase.Exit else MotionPhase.Enter),
        label = "draftProjectFold",
    )
    // One highlight that slides between rows. Its position is a fractional row index the stack
    // resolves against the rows' live placement, so it rides the fold with the chosen row. Folded,
    // a pick swaps in place.
    val highlightIndex = remember { Animatable(chosenIndex?.toFloat() ?: 0f) }
    var highlightShown by remember { mutableStateOf(chosenIndex != null) }
    LaunchedEffect(chosenIndex, isComposing, reducedMotion) {
        val target = chosenIndex ?: return@LaunchedEffect
        if (isComposing || reducedMotion || !highlightShown) highlightIndex.snapTo(target.toFloat())
        else highlightIndex.animateTo(target.toFloat(), Motion.layout.animation())
    }
    LaunchedEffect(chosenIndex != null) { highlightShown = chosenIndex != null }
    val highlightAlpha by animateFloatAsState(
        targetValue = if (chosenIndex != null) 1f else 0f,
        animationSpec = if (isComposing) snap() else Motion.layout.animation(reduceMotion = reducedMotion),
        label = "draftProjectHighlightAlpha",
    )
    AnimatedContent(
        targetState = reducedMotion && isComposing,
        transitionSpec = { fadeIn(Motion.reduced.animation(reduceMotion = true)) togetherWith fadeOut(Motion.reduced.animation(reduceMotion = true)) },
        label = "draftProjectsReduced",
        modifier = modifier.testTag("draft-projects"),
    ) { onlyKept ->
        DraftProjectStack(
            folded = if (isComposing) kept else null,
            fold = if (onlyKept) 1f else fold,
            // Under Reduce Motion the highlight cross-fades in place on its row instead.
            highlightIndex = if (reducedMotion) null else { { highlightIndex.value } },
            highlight = {
                Box(
                    Modifier
                        .alpha(highlightAlpha)
                        .background(OpenCodeTheme.colors.text.copy(alpha = 0.06f), RoundedCornerShape(8.dp)),
                )
            },
        ) {
            shown.forEachIndexed { index, project ->
                val rowIsChosen = index == chosenIndex
                if (!onlyKept || index == kept) {
                    DraftProjectFade(isHidden = isComposing && index != kept, reducedMotion = reducedMotion) { hidden ->
                        DraftProjectRow(
                            title = project.name,
                            symbol = if (rowIsChosen) SFSymbol.FolderFill else SFSymbol.Folder,
                            chosen = rowIsChosen,
                            ownHighlight = reducedMotion,
                            iconWidth = iconWidth,
                            hint = if (isComposing) stringResource(R.string.session_draft_project_choose_label) else null,
                            onClick = { if (isComposing) onAll() else onPick(project) },
                            tag = "draft-project-row",
                            takesTouches = !hidden,
                        )
                    }
                } else {
                    Box(Modifier)
                }
            }
            if (!onlyKept || kept == shown.size) {
                DraftProjectFade(isHidden = isComposing && kept != shown.size, reducedMotion = reducedMotion) { hidden ->
                    DraftProjectRow(
                        title = if (shown.isEmpty()) stringResource(R.string.session_project_choose_folder) else stringResource(R.string.session_project_all_projects),
                        symbol = if (shown.isEmpty()) SFSymbol.FolderBadgePlus else SFSymbol.Magnifyingglass,
                        chosen = false,
                        ownHighlight = reducedMotion,
                        iconWidth = iconWidth,
                        hint = null,
                        onClick = onAll,
                        tag = "draft-projects-all",
                        takesTouches = !hidden,
                    )
                }
            } else {
                Box(Modifier)
            }
        }
    }
}

private fun isChosen(project: OpenCodeProject, chosen: OpenCodeProject?): Boolean =
    chosen != null && (project.id == chosen.id || project.directory == chosen.directory)

@Composable
private fun DraftProjectRow(
    title: String,
    symbol: SFSymbol,
    chosen: Boolean,
    ownHighlight: Boolean,
    iconWidth: androidx.compose.ui.unit.Dp,
    hint: String?,
    onClick: () -> Unit,
    tag: String,
    takesTouches: Boolean,
) {
    // Reduce Motion: the row's own highlight cross-fades in place.
    val highlight by animateFloatAsState(if (chosen && ownHighlight) 1f else 0f, Motion.reduced.animation(reduceMotion = true), label = "draftProjectHighlight")
    val selectedDescription = stringResource(R.string.session_project_selected_description)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(OpenCodeTheme.colors.text.copy(alpha = 0.06f * highlight), RoundedCornerShape(8.dp))
            // A hidden row has no pointer node at all: a disabled one would still be hit and end
            // the search, eating taps meant for the chosen row folded under it.
            .then(if (takesTouches) Modifier.clickable(role = Role.Button, onClickLabel = hint, onClick = onClick) else Modifier)
            .padding(start = 17.dp, end = 16.dp)
            .testTag(tag)
            .clearAndSetSemantics {
                // `selected` would make the row checkable, and TalkBack would offer "double-tap to
                // toggle" on a row that picks.
                if (chosen) stateDescription = selectedDescription
                contentDescription = title
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(iconWidth), contentAlignment = Alignment.Center) {
            OpenCodeIcon(symbol, contentDescription = null, size = 22.dp, tint = if (chosen) OpenCodeTheme.colors.accent else OpenCodeTheme.colors.secondaryText)
        }
        Text(
            title,
            style = OpenCodeTextStyles.body.copy(fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
            color = if (chosen) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row folded away: it fades and blurs out where it is, quickly, so it's gone before the chosen
 * row glides past; unfolding, it comes back just behind. Hidden rows leave TalkBack, and [content]
 * gets `hidden` to drop its touch handling: taps pass through to the chosen row, which sits in the
 * last row's slot when folded.
 */
@Composable
private fun DraftProjectFade(isHidden: Boolean, reducedMotion: Boolean, content: @Composable (hidden: Boolean) -> Unit) {
    val alpha by animateFloatAsState(
        targetValue = if (isHidden) 0f else 1f,
        animationSpec = if (isHidden) Motion.swap.animation(MotionPhase.Exit, reducedMotion)
        else Motion.swap.animation(reduceMotion = reducedMotion, delaySeconds = Motion.contentDelay),
        label = "draftProjectFade",
    )
    val blur = if (reducedMotion) 0f else (1f - alpha) * 6f
    Box(
        Modifier
            .alpha(alpha)
            .transitionBlur(blur.dp)
            .then(if (isHidden) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier),
    ) {
        content(isHidden)
    }
}

/**
 * The list's rows, stacked from the bottom (the composer's edge). Folded to one row, it's that
 * row's height and that row sits at the bottom; the rest keep the places they had, measured from
 * the bottom, so while they fade they ride with the composer instead of being left behind or
 * dropping. [fold] (0…1) animates between the two.
 *
 * [highlight] is drawn behind the rows at the fractional row index [highlightIndex] reads: between
 * two rows it interpolates their live frames. A null [highlightIndex] leaves it out.
 */
@Composable
fun DraftProjectStack(
    folded: Int?,
    fold: Float,
    modifier: Modifier = Modifier,
    highlightIndex: (() -> Float)? = null,
    highlight: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Layout(contents = listOf(highlight, content), modifier = modifier) { (highlightMeasurables, measurables), constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
        val full = placeables.sumOf { it.height }
        val keptIndex = folded?.takeIf { it in placeables.indices }
        val progress = if (keptIndex == null) 0f else fold
        val height = if (keptIndex == null) full else lerpFloat(full.toFloat(), placeables[keptIndex].height.toFloat(), progress).roundToInt()
        val tops = IntArray(placeables.size)
        var below = 0
        for (index in placeables.indices.reversed()) {
            val placeable = placeables[index]
            val fromBottom = if (index == keptIndex) lerpFloat(below.toFloat(), 0f, progress).roundToInt() else below
            tops[index] = height - fromBottom - placeable.height
            below += placeable.height
        }
        val at = highlightIndex?.invoke()?.takeIf { placeables.isNotEmpty() }?.coerceIn(0f, placeables.lastIndex.toFloat())
        val highlightPlaceable = at?.let {
            val lower = it.toInt()
            val upper = minOf(lower + 1, placeables.lastIndex)
            val fraction = it - lower
            val top = lerpFloat(tops[lower].toFloat(), tops[upper].toFloat(), fraction).roundToInt()
            val rowHeight = lerpFloat(placeables[lower].height.toFloat(), placeables[upper].height.toFloat(), fraction).roundToInt()
            highlightMeasurables.firstOrNull()?.measure(Constraints.fixed(width, rowHeight))?.let { placeable -> placeable to top }
        }
        layout(width, height) {
            highlightPlaceable?.let { (placeable, top) -> placeable.place(0, top) }
            placeables.forEachIndexed { index, placeable -> placeable.place(0, tops[index]) }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

/**
 * Search, Choose Folder… (the server's folders), then every project, the chosen one checked. One
 * navigation stack throughout, so nothing swaps under it while it slides away.
 *
 * Values the SessionView group passes from `SessionFeature`:
 * @param projects `state.filteredProjects`.
 * @param draftProject `state.draftProject`.
 * @param projectFilter `state.projectFilter`, with [onProjectFilterChange] its binding.
 * @param onProjectPicked `.projectPicked(project)`.
 * @param onCancel Cancel and swipe-away: `activePicker = nil` (binding).
 * @param folderBrowser `store.scope(\.folderBrowser, \.folderBrowser.presented)`, or null when
 *   the session can't browse folders (Choose Folder… is hidden).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    projects: List<OpenCodeProject>,
    draftProject: OpenCodeProject?,
    projectFilter: String,
    onProjectFilterChange: (String) -> Unit,
    onProjectPicked: (OpenCodeProject) -> Unit,
    onCancel: () -> Unit,
    folderBrowser: StoreOf<FolderBrowserFeature.State, FolderBrowserFeature.Action>?,
) {
    // Half of it there showed only the title, the search and a row under the gesture bar.
    val compactHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = compactHeight),
        dragHandle = { OpenCodeSheetHandle() },
        shape = OpenCodeShape.sheetTop,
    ) {
        val routes = folderBrowser?.let { browser -> browser.collectState().value.routes }.orEmpty()
        BackHandler(enabled = routes.isNotEmpty()) {
            folderBrowser?.send(FolderBrowserFeature.Action.RoutesChanged(routes.dropLast(1)))
        }
        AnimatedContent(
            targetState = routes,
            transitionSpec = {
                val forward = targetState.size >= initialState.size
                (slideInHorizontally { if (forward) it else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it } + fadeOut())
            },
            contentKey = { it.size to it.lastOrNull() },
            label = "projectPickerStack",
        ) { stack ->
            val route = stack.lastOrNull()
            if (route == null || folderBrowser == null) {
                ProjectList(projects, draftProject, projectFilter, onProjectFilterChange, onProjectPicked, onCancel, folderBrowser)
            } else {
                // A climb replaces the folder in place: a fresh view loads it and clears the search.
                FolderBrowserView(folderBrowser, route)
            }
        }
    }
}

@Composable
private fun ProjectList(
    projects: List<OpenCodeProject>,
    draftProject: OpenCodeProject?,
    projectFilter: String,
    onProjectFilterChange: (String) -> Unit,
    onProjectPicked: (OpenCodeProject) -> Unit,
    onCancel: () -> Unit,
    folderBrowser: StoreOf<FolderBrowserFeature.State, FolderBrowserFeature.Action>?,
) {
    Column(Modifier.fillMaxWidth()) {
        SheetTitleBar(
            title = stringResource(R.string.session_project_picker_title),
            leading = { TextButton(onClick = onCancel) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
        SearchField(projectFilter, onProjectFilterChange)
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(top = ProjectPickerTunables.searchGap),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (folderBrowser != null) {
                    item(key = "choose-folder") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .background(sessionGroupedCard(), RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) {
                                    folderBrowser.send(FolderBrowserFeature.Action.RoutesChanged(folderBrowser.state.routes + FolderBrowserFeature.Route(null)))
                                }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = ProjectPickerTunables.rowInset, vertical = ProjectPickerTunables.rowPadding),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.session_project_choose_folder), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.accent, modifier = Modifier.weight(1f))
                            Box(Modifier.width(ProjectPickerTunables.accessoryWidth), contentAlignment = Alignment.Center) {
                                OpenCodeIcon(SFSymbol.FolderBadgePlus, contentDescription = null, size = 20.dp, tint = OpenCodeTheme.colors.accent)
                            }
                        }
                        Spacer(Modifier.size(ProjectPickerTunables.sectionGap))
                    }
                }
                items(projects, key = { it.id + "|" + it.directory }) { project ->
                    val chosen = isChosen(project, draftProject)
                    val selectedDescription = stringResource(R.string.session_project_selected_description)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .background(sessionGroupedCard())
                            .clickable(role = Role.Button) { onProjectPicked(project) }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = ProjectPickerTunables.rowInset, vertical = ProjectPickerTunables.rowPadding)
                            .clearAndSetSemantics {
                                contentDescription = project.name
                                stateDescription = listOfNotNull(if (chosen) selectedDescription else null, project.directory).joinToString(", ")
                                role = Role.Button
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProjectPickerTunables.rowInset),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ProjectPickerTunables.pathGap)) {
                            Text(project.name, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(project.directory, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                        Box(Modifier.width(ProjectPickerTunables.accessoryWidth).alpha(if (chosen) 1f else 0f), contentAlignment = Alignment.Center) {
                            OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.accent)
                        }
                    }
                }
            }
            if (projectFilter.isNotEmpty() && projects.isEmpty()) SearchUnavailable(projectFilter)
        }
    }
}

/**
 * One folder on the server, its path, the folders inside it, and Select. The title menu climbs to
 * any folder above it.
 */
@Composable
fun FolderBrowserView(store: StoreOf<FolderBrowserFeature.State, FolderBrowserFeature.Action>, route: FolderBrowserFeature.Route) {
    val state by store.collectState()
    var filter by rememberSaveable(route) { mutableStateOf("") }
    val directory = state.directory(route)
    val listing = state.listings[route]
    val selectFolderDescription = stringResource(R.string.session_folder_select_description)
    val pathDescription = stringResource(R.string.session_folder_path_description)
    LaunchedEffect(route) { store.send(FolderBrowserFeature.Action.FolderAppeared(route)) }
    Column(Modifier.fillMaxWidth()) {
        var menuOpen by remember { mutableStateOf(false) }
        SheetTitleBar(
            title = directory?.let(FolderBrowserFeature::name) ?: stringResource(R.string.session_folder_title),
            onTitleClick = if (directory != null) ({ menuOpen = true }) else null,
            titleMenu = {
                if (directory != null) {
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for (ancestor in FolderBrowserFeature.ancestors(directory).asReversed()) {
                            DropdownMenuItem(
                                text = { Text(FolderBrowserFeature.name(ancestor)) },
                                leadingIcon = { OpenCodeIcon(SFSymbol.Folder, contentDescription = null, size = 20.dp) },
                                onClick = {
                                    menuOpen = false
                                    store.send(FolderBrowserFeature.Action.AncestorTapped(ancestor))
                                },
                            )
                        }
                    }
                }
            },
            leading = {
                TextButton(onClick = { store.send(FolderBrowserFeature.Action.RoutesChanged(state.routes.dropLast(1))) }) { Text(stringResource(CoreR.string.common_back)) }
            },
            trailing = {
                TextButton(
                    onClick = { store.send(FolderBrowserFeature.Action.SelectTapped(route)) },
                    enabled = state.canSelect(route),
                    modifier = Modifier.semantics {
                        contentDescription = selectFolderDescription
                        stateDescription = directory ?: ""
                    },
                ) { Text(stringResource(R.string.session_folder_select), fontWeight = FontWeight.SemiBold) }
            },
        )
        SearchField(filter, { filter = it })
        if (directory != null) {
            Text(
                directory,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = pathDescription
                        stateDescription = directory
                    },
            )
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).weight(1f, fill = false)) {
            val loaded = (listing as? FolderBrowserFeature.Listing.Loaded)?.listing
            val folders = loaded?.folders?.let { visibleFolders(it, filter) }.orEmpty()
            LazyColumn(Modifier.fillMaxWidth()) {
                items(folders, key = { it }) { folder ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                store.send(FolderBrowserFeature.Action.RoutesChanged(state.routes + FolderBrowserFeature.Route(folder)))
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OpenCodeIcon(SFSymbol.Folder, contentDescription = null, size = 22.dp, tint = OpenCodeTheme.colors.accent)
                        Text(FolderBrowserFeature.name(folder), style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
                    }
                }
            }
            when (listing) {
                null, FolderBrowserFeature.Listing.Loading -> Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is FolderBrowserFeature.Listing.Failed -> OpenCodeContentUnavailable(
                    symbol = SFSymbol.ExclamationmarkTriangle,
                    title = stringResource(R.string.session_folder_open_failed_title),
                    modifier = Modifier.fillMaxSize(),
                    description = listing.message,
                    action = stringResource(CoreR.string.common_try_again),
                    onAction = { store.send(FolderBrowserFeature.Action.RetryTapped(route)) },
                )
                is FolderBrowserFeature.Listing.Loaded -> if (folders.isEmpty()) {
                    if (filter.isEmpty()) OpenCodeContentUnavailable(SFSymbol.Folder, stringResource(R.string.session_folder_empty_title), Modifier.fillMaxSize()) else SearchUnavailable(filter)
                }
            }
        }
    }
}

/** Case- and diacritic-insensitive. */
private fun visibleFolders(folders: List<String>, filter: String): List<String> {
    if (filter.isEmpty()) return folders
    val needle = folded(filter)
    return folders.filter { folded(FolderBrowserFeature.name(it)).contains(needle) }
}

private val combiningMarks = Regex("\\p{Mn}+")
private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase()

@Composable
private fun sessionGroupedCard(): Color = OpenCodeTheme.colors.groupedCard

@Composable
private fun SheetTitleBar(
    title: String,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    onTitleClick: (() -> Unit)? = null,
    titleMenu: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 96.dp)) {
            Row(
                Modifier
                    .then(if (onTitleClick != null) Modifier.clickable(role = Role.DropdownList, onClick = onTitleClick) else Modifier)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (onTitleClick != null) OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
            }
            titleMenu()
        }
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OpenCodeSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(CoreR.string.common_search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun SearchUnavailable(text: String) {
    OpenCodeContentUnavailable(
        SFSymbol.Magnifyingglass,
        stringResource(R.string.session_folder_search_empty_title, text),
        Modifier.fillMaxSize(),
        description = stringResource(R.string.session_folder_search_empty_message),
    )
}
