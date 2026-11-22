package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.openCodeFieldContainer
import ai.opencode.core.ui.OpenCodeTextFieldDefaults
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSheetHandleDefaults
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.Motion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.R
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// One Picker's morphing surface. On Android the shape is a Material 3 tonal surface
// (`surfaceContainerHigh`, as the composer's own surface), never a blur or glass imitation, and the
// backdrop dim and blur stay off. Coordinates are dp in the overlay's own space (the session's root
// box). The DEBUG-only probes (window space, glass, chip, composer, integrity readout) are n/a.

/**
 * One Picker's surface colour: the composer's (so the morph reads as one surface), on this branch
 * the desktop's floating surface with a hairline ring.
 */
object OnePickerSurfaceDefaults {
    val color: Color @Composable get() = OpenCodeSurfaces.color
}

internal val OnePickerFavoriteTint = Color(0.92f, 0.68f, 0.10f)

private fun FramePoseShape(pose: OnePickerSimulation.FramePose) = RoundedCornerShape(
    topStart = pose.topRadius.dp,
    topEnd = pose.topRadius.dp,
    bottomStart = pose.bottomRadius.dp,
    bottomEnd = pose.bottomRadius.dp,
)

/**
 * The one surface the composer folds into, mounted under the composer and the panel's rows. Not
 * hit-testable and hidden from TalkBack.
 */
@Composable
fun OnePickerGlassBackdrop(controller: OnePickerController, modifier: Modifier = Modifier) {
    if (!controller.isShown) return
    val color = OnePickerSurfaceDefaults.color
    Box(modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
        val pose = controller.motion.pose
        val rect = pose.rect
        val shape = FramePoseShape(pose)
        Box(
            Modifier
                .offset { IntOffset(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
                .requiredSize(rect.width.dp, rect.height.dp)
                .graphicsLayer { alpha = pose.glassOpacity.toFloat().coerceIn(0f, 1f) }
                .shadow(OpenCodeSurfaces.popoverShadow, shape, clip = false)
                .background(color, shape)
                .openCodeHairline(shape),
        )
    }
}

/**
 * The panel's rows, grabber and search over the backdrop, plus the clear tap-catcher that closes
 * it. [liveComposer] is the composer's frame in this space (`Rect.Zero` when it isn't on screen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnePickerSurface(
    state: ModelPickerState,
    controller: OnePickerController,
    ceiling: Double,
    liveComposer: Rect,
    onEvent: (ModelPickerEvent) -> Unit,
    onHideKeyboard: () -> Unit,
    onDismiss: () -> Unit,
    onPickModelAndClose: (OpenCodeModel, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    OnePickerFrameDriver(controller)
    // The composer's live frame, the docked keyboard now (the IME inset) and where it is heading
    // (its animation target), in the window's dp, as the controller reads them.
    val windowSize = windowInfo.containerSize
    val liveComposerNow by rememberUpdatedState(liveComposer)
    val keyboardNow by rememberUpdatedState(WindowInsets.ime.getBottom(density))
    val keyboardTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    val keyboardRect = { bottom: Int ->
        if (bottom <= 0) null else Rect(
            0f, (windowSize.height - bottom) / density.density,
            windowSize.width / density.density, windowSize.height / density.density,
        )
    }
    DisposableEffect(controller) {
        controller.composerRectSource = { liveComposerNow.takeIf { it.width > 0f } }
        controller.keyboardFrameSource = { keyboardRect(keyboardNow) }
        onDispose {
            controller.composerRectSource = null
            controller.keyboardFrameSource = null
        }
    }
    LaunchedEffect(controller, keyboardTarget) { controller.handleKeyboardFrameChange(keyboardRect(keyboardTarget)) }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInRoot()
                val windowHeight = windowInfo.containerSize.height / density.density
                val windowWidth = windowInfo.containerSize.width / density.density
                controller.noteWindowSpace(
                    Rect(Offset(-origin.x / density.density, -origin.y / density.density), Size(windowWidth, windowHeight)),
                    windowHeight.toDouble(),
                )
            },
    ) {
        // The overlay is the full-screen session box; the panel's floor belongs above the
        // navigation bar, as the composer's does.
        val navigationBottom = WindowInsets.navigationBars.getBottom(density) / density.density
        val viewport = Rect(0f, 0f, maxWidth.value, max(0f, maxHeight.value - navigationBottom))
        LaunchedEffect(viewport, ceiling) {
            controller.latestViewportInSpace = viewport
            controller.syncViewport(viewport, ceiling, liveComposer)
        }
        LaunchedEffect(liveComposer) {
            if (!controller.searchFocused && liveComposer.width > 0f && liveComposer.bottom > controller.latestViewportInSpace.bottom - 140f) {
                controller.restingBottomComposer = liveComposer
            }
        }
        OnePickerCatalogPrewarm(state, controller)
        if (controller.isShown) {
            val currentDismiss by rememberUpdatedState(onDismiss)
            if (controller.isOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .excludedFromDrawerPan()
                        .semantics { hideFromAccessibility() }
                        .pointerInput(Unit) { detectTapGestures { if (controller.isOpen) currentDismiss() } },
                )
            }
            // Built here, not in the placement's per-frame scope, and handed the morph as a lambda
            // it reads in its own leaf, so a morph frame skips the panel (catalog, list actions,
            // footer) and only resizes it.
            val morph = remember(controller) { { OnePickerPanelMorph(controller.motion.pose, controller) } }
            OnePickerPanelPlacement(controller) {
                OnePickerPanelContent(
                    state = state,
                    controller = controller,
                    isSheetFallback = false,
                    morph = morph,
                    onEvent = onEvent,
                    onHideKeyboard = onHideKeyboard,
                    onDismiss = onDismiss,
                    onPickModelAndClose = onPickModelAndClose,
                )
            }
        }
    }
}

/** The panel's rows laid out at the pose, revealed with room. */
@Composable
private fun OnePickerPanelPlacement(
    controller: OnePickerController,
    content: @Composable () -> Unit,
) {
    val pose = controller.motion.pose
    val rect = pose.rect
    val shape = FramePoseShape(pose)
    val layoutWidth = max(pose.layoutSize.width, 200f)
    val scaleX = rect.width / max(layoutWidth, 1f)
    val liveComp = controller.liveComposerPresentationRectInSpace()
    val visiblePanelHeight = if (pose.composerOpacity > 0.005) {
        if (liveComp != null) min(pose.contentClipHeight, max(0.0, liveComp.top - 8.0 - rect.top)) else pose.contentClipHeight
    } else {
        min(rect.height.toDouble(), pose.contentClipHeight)
    }
    val liveRoomGate = OnePickerSimulation.smoothstep(((visiblePanelHeight - 22) / 14).coerceIn(0.0, 1.0))
    val panelReveal = if (pose.composerOpacity > 0.005) min(pose.contentReveal, liveRoomGate) else pose.contentReveal
    val interactive = controller.isOpen && pose.contentReveal > 0.4

    Box(
        Modifier
            .offset { IntOffset(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
            .requiredSize(rect.width.dp, rect.height.dp)
            .excludedFromDrawerPan(controller.isShown)
            .graphicsLayer { alpha = if (panelReveal <= 0.01) 0f else panelReveal.toFloat() }
            .clip(shape)
            .let { if (controller.isOpen) it else it.semantics { hideFromAccessibility() } },
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .requiredWidth(rect.width.dp)
                .requiredHeight(max(0.0, visiblePanelHeight).dp)
                .offset { IntOffset.Zero }
                .clipToBounds(),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .requiredSize(layoutWidth.dp, rect.height.dp)
                    .graphicsLayer { this.scaleX = scaleX }
                    // Eat touches until then.
                    .let { if (interactive) it else it.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } } },
            ) {
                content()
            }
        }
    }
}

/** What the panel's interior reads off the morph each frame. */
data class OnePickerPanelMorph(
    val fixedListLayoutHeight: Double?,
    val composerControlsClearance: Double,
    val searchBarReveal: Double,
) {
    companion object {
        val still = OnePickerPanelMorph(null, 0.0, 1.0)

        fun layoutHeight(pose: OnePickerSimulation.FramePose, controller: OnePickerController): Double {
            val baseHeight = max(pose.layoutSize.height.toDouble(), 220.0)
            if (!controller.isDragging) return baseHeight
            return max(baseHeight, pose.rect.height.toDouble())
        }
    }
}

private fun OnePickerPanelMorph(pose: OnePickerSimulation.FramePose, controller: OnePickerController): OnePickerPanelMorph {
    val liveCompH = controller.liveComposerPresentationRectInSpace()?.height?.toDouble() ?: 88.0
    val sourceHeight = max(96.0, max(liveCompH, controller.simulation.metrics.sourceRect.height.toDouble()) + 16)
    return OnePickerPanelMorph(
        fixedListLayoutHeight = max(OnePickerPanelMorph.layoutHeight(pose, controller) - 84, 120.0),
        composerControlsClearance = 60 + max(0.0, sourceHeight - 60) * min(1.0, pose.composerOpacity * 4.5),
        searchBarReveal = pose.searchBarReveal,
    )
}

/**
 * One Picker's interior in a plain sheet, for when the composer can't morph (a call or a docked
 * request covers it). Hosted in the model pickers' sheet; [onDismiss] clears `activePicker` (after
 * the sheet's slide-down), and leaving clears the search (`modelListClosed`).
 */
@Composable
fun OnePickerSheetHost(
    state: ModelPickerState,
    onEvent: (ModelPickerEvent) -> Unit,
    onDismiss: () -> Unit,
    onPickModel: (OpenCodeModel, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallbackController = remember { OnePickerController() }
    DisposableEffect(fallbackController) {
        fallbackController.open(
            sourceRect = Rect(0f, 400f, 390f, 456f),
            viewport = Rect(0f, 0f, 390f, 844f),
            ceiling = 80.0,
            recentModelIDs = state.recentModelIDs,
            hadKeyboard = false,
            initialDetent = ModelListDetent.Medium,
            reduceMotion = true,
            onLaunch = {},
            onOpened = {},
        )
        onDispose { fallbackController.reset() }
    }
    ModelPickerDetentsSheet(onClose = onDismiss, onClosed = { onEvent(ModelPickerEvent.ModelListClosed) }) {
        val hideThen = LocalModelSheetHideThen.current
        Box(modifier.fillMaxSize()) {
            OnePickerPanelContent(
                state = state,
                controller = fallbackController,
                isSheetFallback = true,
                morph = { OnePickerPanelMorph.still },
                onEvent = onEvent,
                onHideKeyboard = {},
                onDismiss = { hideThen?.invoke(onDismiss) ?: onDismiss() },
                // `modelPickedWithLevel` keeps `activePicker`, so the sheet stays up with the new
                // pick checked.
                onPickModelAndClose = onPickModel,
            )
        }
    }
}

/**
 * The last catalog the panel built and the inputs it came from. Kept by [OnePickerController], so
 * it outlives the panel: the next open reuses it, or the copy [OnePickerCatalogPrewarm] built while
 * the list was shut, instead of building it on the opening's first frame. A plain class, so filling
 * it never recomposes anything. Read and filled on the main thread only.
 */
class OnePickerCatalogCache {
    /**
     * What the catalog and its row snapshots are built from. Kotlin's immutable lists compare by
     * identity first, so the key holds the list itself and only a new catalogue is compared in
     * full.
     */
    data class Key(
        val models: List<OpenCodeModel>,
        val visibility: Any?,
        val showsHiddenModels: Boolean,
        val favorites: Set<String>,
        val recents: List<String>,
        val currentModelID: String?,
        val currentLevel: String?,
        val modelVariants: Map<String, String>,
        val filter: String,
        val recentLimit: Int,
        val showsAllModels: Boolean,
        val frozen: List<ModelPickerSection>?,
    ) {
        /**
         * The catalog's inputs as they are now, listing [recents] as the picker's recent rows and
         * [frozen] (closing) in place of the sections.
         */
        constructor(state: ModelPickerState, recents: List<String>, frozen: List<ModelPickerSection>?) : this(
            models = state.models,
            visibility = state.modelVisibility,
            showsHiddenModels = state.showsAllModels,
            favorites = state.favoriteModelIDs,
            recents = recents,
            currentModelID = state.currentModelInfo?.id,
            currentLevel = state.currentLevel,
            modelVariants = state.modelVariants,
            filter = state.modelFilter,
            recentLimit = OnePickerTunables.RECENTS,
            showsAllModels = OnePickerTunables.SHOWS_ALL_MODELS,
            frozen = frozen,
        )

        /** The same inputs, except for the frozen sections. */
        fun matchesIgnoringFrozen(other: Key): Boolean = other.copy(frozen = frozen) == this
    }

    class Catalog(val sections: List<ModelPickerSection>, val snapshots: List<OnePickerSectionSnapshot>)

    private var key: Key? = null
    private var value: Catalog? = null

    /** The sections last built, as the panel shows them: what a close freezes. */
    val shownSections: List<ModelPickerSection>? get() = value?.sections

    fun catalog(next: Key, build: () -> Catalog): Catalog {
        val cached = value
        val cachedKey = key
        if (cached != null && cachedKey != null) {
            if (cachedKey == next) return cached
            // Freezing the sections already on screen (a close) changes nothing the rows show:
            // the catalog and its snapshots stay as they are.
            val frozen = next.frozen
            if (frozen != null && cachedKey.matchesIgnoringFrozen(next) && frozen == cached.sections) {
                key = next
                return cached
            }
        }
        val built = build()
        key = next
        value = built
        return built
    }

    companion object {
        /** The catalog and its row snapshots for [key], from the picker's models. */
        fun build(key: Key, state: ModelPickerState): Catalog {
            val sections = key.frozen ?: OnePickerCatalog.sections(
                models = state.pickerModels,
                favorites = key.favorites,
                recents = key.recents,
                currentModelID = key.currentModelID,
                filter = key.filter,
                recentLimit = key.recentLimit,
                showsAllModels = key.showsAllModels,
            )
            return Catalog(sections, state.onePickerSnapshotSections(sections))
        }
    }
}

/**
 * Builds the next opening's catalog while the list is shut, half a second after the models,
 * favorites, visibility or current model settle (off the frames of whatever changed them, a pick's
 * close or the keyboard), so opening finds it in [OnePickerController.catalogCache] instead of
 * building it on its first frame. A newer key cancels the pending build.
 */
@Composable
private fun OnePickerCatalogPrewarm(state: ModelPickerState, controller: OnePickerController) {
    val key = if (controller.isShown) null else OnePickerCatalogCache.Key(state, state.recentModelIDs, frozen = null)
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        delay(500)
        if (controller.isShown) return@LaunchedEffect
        controller.catalogCache.catalog(key) { OnePickerCatalogCache.build(key, state) }
    }
}

/** Each section's immutable row snapshots. */
internal fun ModelPickerState.onePickerSnapshotSections(sections: List<ModelPickerSection>): List<OnePickerSectionSnapshot> {
    val currentID = currentModelInfo?.id
    val favorites = favoriteModelIDs
    val query = modelFilter
    var highlightedCurrentYet = false
    return sections.map { section ->
        val mixesProviders = section.kind !is ModelPickerSection.Kind.Provider
        val prefix = when (section.kind) {
            ModelPickerSection.Kind.Favorites -> "model-favorite"
            ModelPickerSection.Kind.Recent -> "model-recent"
            else -> "model-row"
        }
        val duplicateShortNames = section.models.groupingBy { it.shortName }.eachCount().filterValues { it > 1 }.keys
        val rows = section.models.map { model ->
            val scale = ModelLevelScale(model.variants)
            val parsed = OnePickerCatalog.parsedSearchLevel(query, model)
            val shown = if (parsed.matched) parsed.level else rememberedLevel(model)
            val providerTitle = ModelProviderGroup.title(model.providerID)
            val needsSubtitle = mixesProviders || model.shortName in duplicateShortNames
            val isPrimaryCurrent = model.id == currentID && !highlightedCurrentYet
            if (isPrimaryCurrent) highlightedCurrentYet = true
            OnePickerRowItem(
                sectionID = section.id,
                idPrefix = prefix,
                model = model,
                shortName = model.shortName,
                providerTitle = providerTitle,
                providerSubtitle = if (needsSubtitle) (if (mixesProviders) providerTitle else model.modelID) else null,
                isCurrent = isPrimaryCurrent,
                isFavorite = model.id in favorites,
                inFavoritesSection = section.kind == ModelPickerSection.Kind.Favorites,
                isAdjustable = scale.isAdjustable,
                shownLevel = shown,
                levelLabel = ModelLevelScale.label(shown),
                hasParsedSearchLevel = parsed.matched,
                showsArc = OnePickerTunables.ROW_SHOWS_ARC,
                rowHeight = OnePickerTunables.ROW_HEIGHT,
                levelAlpha = OnePickerTunables.LEVEL_ALPHA,
            )
        }
        OnePickerSectionSnapshot(section.id, section.title, rows)
    }
}

/**
 * The grabber, the list (or its spinner or empty state) under edge fades, and the bottom search
 * bar. Shared by the morph and the sheet fallback.
 */
@Composable
private fun OnePickerPanelContent(
    state: ModelPickerState,
    controller: OnePickerController,
    isSheetFallback: Boolean,
    morph: () -> OnePickerPanelMorph,
    onEvent: (ModelPickerEvent) -> Unit,
    onHideKeyboard: () -> Unit,
    onDismiss: () -> Unit,
    onPickModelAndClose: (OpenCodeModel, String?) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val searchFocus = remember { FocusRequester() }
    var searchFieldFocused by remember { mutableStateOf(false) }
    var scrollEdges by remember { mutableStateOf(OnePickerScrollEdges()) }

    // The catalog and its row snapshots, rebuilt only when one of their inputs changes.
    val frozen = if (!controller.isOpen && !isSheetFallback) controller.frozenSections else null
    val key = OnePickerCatalogCache.Key(state, controller.recentsAtOpen, frozen)
    val catalog = controller.catalogCache.catalog(key) { OnePickerCatalogCache.build(key, state) }
    val isSearching = state.modelFilter.trim().isNotEmpty()
    val hiddenModelCount = state.hiddenModelCount
    val footer = if (hiddenModelCount > 0) OnePickerFooterSnapshot(hiddenModelCount, state.showsAllModels) else null
    val showsSpinner = state.isLoadingModels && state.models.isEmpty()
    val showsEmptyState = catalog.sections.isEmpty() && !state.isLoadingModels && hiddenModelCount == 0

    val finishInteractiveDrag = { dy: Double, vy: Double ->
        val composerDest = if (controller.restoresComposerFocus) controller.openedFromComposer else controller.restingBottomComposer
        val dest = if (composerDest == Rect.Zero) controller.openedFromComposer else composerDest
        controller.endDrag(dy, vy, dest, keepsKeyboard = controller.restoresComposerFocus && controller.searchFocused) { onDismiss() }
    }
    val listActions = remember { OnePickerListActions() }
    listActions.onPickModel = { item ->
        controller.frozenSections = catalog.sections
        onPickModelAndClose(item.model, item.shownLevel)
    }
    listActions.onPickLevel = { model, level -> onEvent(ModelPickerEvent.ModelRowLevelChanged(model, level)) }
    listActions.onToggleFavorite = { onEvent(ModelPickerEvent.ToggleFavoriteModel(it)) }
    listActions.onToggleShowAllModels = { onEvent(ModelPickerEvent.ShowAllModelsToggled) }
    listActions.onBeginSheetDrag = { if (!isSheetFallback) controller.beginDrag() }
    listActions.onUpdateSheetDrag = { if (!isSheetFallback) controller.updateDrag(it) }
    listActions.onEndSheetDrag = { dy, vy -> if (!isSheetFallback) finishInteractiveDrag(dy, vy) }
    listActions.onEdgesChanged = { scrollEdges = it }

    LaunchedEffect(searchFieldFocused) { controller.searchFocused = searchFieldFocused }
    LaunchedEffect(controller.isOpen) {
        if (!controller.isOpen && searchFieldFocused && !controller.handedKeyboardToComposer) focusManager.clearFocus()
    }
    HapticEffect(Haptic.threshold, controller.thresholdHapticCount) { old, new -> new > old }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { dismiss { onDismiss(); true } }
            .testTag("model-list"),
    ) {
        // The morph is read here, in the list's sizing scope, not in the panel's body.
        val morph = morph()
        val currentHeight = maxHeight.value.toDouble()
        val bottomReserved = max(60.0, morph.composerControlsClearance)
        val windowListHeight = max(0.0, currentHeight - 24 - bottomReserved)
        val layoutListHeight = morph.fixedListLayoutHeight ?: max(windowListHeight, 120.0)
        val curtain = morph.fixedListLayoutHeight != null && windowListHeight + 2 < layoutListHeight
        val fade = min(OnePickerTunables.EDGE_FADE, windowListHeight / 2)

        Column(Modifier.fillMaxSize()) {
            OnePickerGrabber(controller, isSheetFallback, finishInteractiveDrag)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(windowListHeight.dp)
                    .clipToBounds()
                    .onePickerEdgeFade(top = scrollEdges.topOverflow, bottom = scrollEdges.bottomOverflow || curtain, fade = fade),
            ) {
                if (showsSpinner) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
                } else {
                    OnePickerListView(
                        sections = catalog.snapshots,
                        openGeneration = controller.openGeneration,
                        isSearching = isSearching,
                        scrollDisabled = false,
                        bottomFooterRow = footer,
                        actions = listActions,
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().requiredHeight(layoutListHeight.dp),
                    )
                    if (showsEmptyState) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            ModelListEmptyState(state, includesLoadError = true, onTryAgain = { onEvent(ModelPickerEvent.ModelListOpened) })
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val reveal = morph.searchBarReveal
            Box(
                Modifier
                    .alpha(if (reveal <= 0.01) 0f else reveal.toFloat())
                    .let {
                        if (reveal > 0.5) it else it
                            .semantics { hideFromAccessibility() }
                            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
                    },
            ) {
                OnePickerSearchBar(
                    state = state,
                    controller = controller,
                    isSheetFallback = isSheetFallback,
                    focusRequester = searchFocus,
                    focused = searchFieldFocused,
                    onFocusChanged = { searchFieldFocused = it },
                    onEvent = onEvent,
                    onHideKeyboard = {
                        focusManager.clearFocus()
                        controller.dismissDockedKeyboardTracking(Motion.keyboard.curve().duration)
                        onHideKeyboard()
                    },
                    onSubmit = {
                        val pick = ModelSearch.resolvePick(state.pickerModels, state.modelFilter, state.favoriteModelIDs, controller.recentsAtOpen)
                        if (pick != null) {
                            val level = if (pick.hasExplicitLevel) pick.variant else state.rememberedLevel(pick.model)
                            controller.frozenSections = catalog.sections
                            onPickModelAndClose(pick.model, level)
                        }
                    },
                    finishInteractiveDrag = finishInteractiveDrag,
                )
            }
        }
    }
}

/** Fades rows into the top/bottom edges when they overflow there. */
private fun Modifier.onePickerEdgeFade(top: Boolean, bottom: Boolean, fade: Double): Modifier {
    if ((!top && !bottom) || fade <= 0) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadePx = fade.dp.toPx()
            if (top) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fadePx),
                    size = size.copy(height = fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottom) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fadePx, endY = size.height),
                    topLeft = Offset(0f, size.height - fadePx),
                    size = size.copy(height = fadePx),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

/**
 * [onBegin] once the finger moves [minimum] dp, then the translation (dp) from where it went down;
 * [onEnd] with translation and velocity (dp/s), or [onCancel] when the gesture is lost.
 */
private fun Modifier.onePickerDrag(
    density: Density,
    minimum: Double,
    onBegin: () -> Unit,
    onChange: (Offset) -> Unit,
    onEnd: (Double, Double) -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        var ended = false
        var translation = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            translation = (change.position - down.position) / density.density
            if (!change.pressed) {
                ended = true
                break
            }
            if (!dragging && hypot(translation.x, translation.y) >= minimum) {
                dragging = true
                onBegin()
            }
            if (dragging) {
                change.consume()
                onChange(translation)
            }
        }
        if (!dragging) return@awaitEachGesture
        if (ended) onEnd(translation.y.toDouble(), tracker.calculateVelocity().y / density.density.toDouble()) else onCancel()
    }
}

/** A 36×5 handle; tap toggles the detent, drag moves the sheet. */
@Composable
private fun OnePickerGrabber(
    controller: OnePickerController,
    isSheetFallback: Boolean,
    finishInteractiveDrag: (Double, Double) -> Unit,
) {
    val density = LocalDensity.current
    val detent = controller.detent
    val grabberDescription = stringResource(R.string.composer_sheet_grabber_description)
    val compactText = stringResource(R.string.composer_model_grabber_compact_action)
    val expandText = stringResource(R.string.composer_model_grabber_expand_action)
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!isSheetFallback) controller.toggleDetent()
            }
            .let {
                if (isSheetFallback) it else it.onePickerDrag(
                    density = density,
                    minimum = 4.0,
                    onBegin = { controller.beginDrag() },
                    onChange = { controller.updateDrag(it.y.toDouble()) },
                    onEnd = { dy, vy -> finishInteractiveDrag(dy, vy) },
                    onCancel = { controller.cancelDrag() },
                )
            }
            .clearAndSetSemantics {
                contentDescription = grabberDescription
                stateDescription = detent.accessibilityValue
                role = Role.Button
                onClick(if (detent == ModelListDetent.Large) compactText else expandText) {
                    if (!isSheetFallback) controller.toggleDetent()
                    true
                }
                progressBarRangeInfo = ProgressBarRangeInfo(if (detent == ModelListDetent.Large) 1f else 0f, 0f..1f, 0)
                setProgress { target ->
                    if (!isSheetFallback) controller.chooseDetent(detent.adjusted(if (target >= 0.5f) 1 else -1))
                    true
                }
            }
            .testTag("model-list-grabber"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(OpenCodeSheetHandleDefaults.width, OpenCodeSheetHandleDefaults.height)
                .background(OpenCodeSheetHandleDefaults.color, OpenCodeShape.capsule),
        )
    }
}

/** Search under the thumb, riding the keyboard. */
@Composable
private fun OnePickerSearchBar(
    state: ModelPickerState,
    controller: OnePickerController,
    isSheetFallback: Boolean,
    focusRequester: FocusRequester,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onEvent: (ModelPickerEvent) -> Unit,
    onHideKeyboard: () -> Unit,
    onSubmit: () -> Unit,
    finishInteractiveDrag: (Double, Double) -> Unit,
) {
    val colors = OpenCodeTheme.colors
    val fieldColors = OpenCodeTextFieldDefaults.colors()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val text = state.modelFilter
    var sheetDragging by remember { mutableStateOf(false) }
    val hasTrailing = text.isNotEmpty() || focused
    val searchModelsText = stringResource(R.string.composer_model_search_placeholder)
    Box(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .onePickerDrag(
                density = density,
                minimum = 10.0,
                onBegin = { sheetDragging = false },
                onChange = { translation ->
                    if (focused && translation.y > 24 && !sheetDragging) {
                        focusManager.clearFocus()
                        controller.dismissDockedKeyboardTracking(Motion.keyboard.curve().duration)
                    } else if (!focused && !isSheetFallback && translation.y > 4) {
                        if (!sheetDragging) {
                            sheetDragging = true
                            controller.beginDrag()
                        }
                        controller.updateDrag(translation.y.toDouble())
                    }
                },
                onEnd = { dy, vy ->
                    if (sheetDragging) finishInteractiveDrag(dy, vy)
                    sheetDragging = false
                },
                onCancel = {
                    if (sheetDragging) controller.cancelDrag()
                    sheetDragging = false
                },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                .height(44.dp)
                .openCodeFieldContainer(isFocused = focused)
                .padding(start = 10.dp, end = if (hasTrailing) 2.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpenCodeIcon(SFSymbol.Magnifyingglass, null, size = OpenCodeTextFieldDefaults.iconSize, tint = fieldColors.icon)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(searchModelsText, style = OpenCodeTextFieldDefaults.textStyle, color = fieldColors.placeholder, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = { onEvent(ModelPickerEvent.FilterChanged(it)) },
                    singleLine = true,
                    textStyle = OpenCodeTextFieldDefaults.textStyle.copy(color = colors.text),
                    cursorBrush = SolidColor(fieldColors.cursor),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) }
                        .semantics { contentDescription = searchModelsText }
                        .testTag("model-search"),
                )
            }
            if (text.isNotEmpty()) {
                OnePickerSearchButton(SFSymbol.XmarkCircleFill, stringResource(R.string.composer_model_clear_search_description), "model-search-clear") {
                    onEvent(ModelPickerEvent.FilterChanged(""))
                }
            } else if (focused) {
                OnePickerSearchButton(SFSymbol.KeyboardChevronCompactDown, stringResource(R.string.composer_model_hide_keyboard_description), "model-search-hide-keyboard", onHideKeyboard)
            }
        }
    }
}

@Composable
private fun OnePickerSearchButton(symbol: SFSymbol, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(symbol, null, size = 20.dp, tint = OpenCodeTheme.colors.tertiaryText)
    }
}
