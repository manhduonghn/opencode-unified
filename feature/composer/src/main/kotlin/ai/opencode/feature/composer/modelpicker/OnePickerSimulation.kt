package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.MotionRubberBand
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.ui.OpenCodeShapeGeometry
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

// region Catalog & section builder

/** One Picker's sections and its search's level word. */
object OnePickerCatalog {
    /**
     * Builds truthful One Picker sections:
     * - Searching: flat ranked `searchResults` across [models] (words in any order, provider
     *   matching, and optional trailing level words such as `"opus high"`).
     * - Idle: Favorites, then Recent (frozen at open, excluding favorites, always keeping
     *   [currentModelID] visible at the top if not already in Favorites or Recent), then
     *   per-provider sections in the server's order (unless [showsAllModels] is off and
     *   Favorites + Recent already offer ≥ 2 picks).
     */
    fun sections(
        models: List<OpenCodeModel>,
        favorites: Set<String>,
        recents: List<String>,
        currentModelID: String?,
        filter: String,
        recentLimit: Int = 3,
        showsAllModels: Boolean = true,
    ): List<ModelPickerSection> {
        val needle = filter.trim()
        if (needle.isNotEmpty()) {
            val ranked = ModelSearch.rank(models, needle, favorites = favorites, recents = recents, parseLevel = true)
            if (ranked.isEmpty()) return emptyList()
            return listOf(ModelPickerSection(ModelPickerSection.Kind.SearchResults, ranked))
        }

        val byID = LinkedHashMap<String, OpenCodeModel>()
        models.forEach { byID.putIfAbsent(it.id, it) }
        val favModels = models.filter { it.id in favorites }
        val favIDs = favModels.mapTo(HashSet()) { it.id }

        var recentModels = mutableListOf<OpenCodeModel>()
        val seenRecent = HashSet<String>()
        val cap = max(0, recentLimit)
        if (cap > 0) {
            for (id in recents) {
                if (id in favIDs) continue
                if (!seenRecent.add(id)) continue
                val model = byID[id] ?: continue
                recentModels.add(model)
                if (recentModels.size >= cap) break
            }
            val current = currentModelID?.let(byID::get)
            if (currentModelID != null && currentModelID !in favIDs && recentModels.none { it.id == currentModelID } && current != null) {
                recentModels.add(0, current)
                if (recentModels.size > cap) recentModels = recentModels.take(cap).toMutableList()
            }
        }

        val out = mutableListOf<ModelPickerSection>()
        if (favModels.isNotEmpty()) out += ModelPickerSection(ModelPickerSection.Kind.Favorites, favModels)
        if (recentModels.isNotEmpty()) out += ModelPickerSection(ModelPickerSection.Kind.Recent, recentModels)

        val curatedCount = favModels.size + recentModels.size
        if (showsAllModels || curatedCount < 2) {
            val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
            for (model in models) byProvider.getOrPut(model.providerID) { mutableListOf() }.add(model)
            byProvider.forEach { (provider, list) -> out += ModelPickerSection(ModelPickerSection.Kind.Provider(provider), list) }
        }
        return out
    }

    /** The search's explicit level (`"astra high"` → `"high"`), resolved against the model's variants. */
    data class ParsedSearchLevel(val level: String?, val matched: Boolean)

    /**
     * An explicit level parsed from the search [query], resolved against [model]'s variants, or
     * `(null, false)` when no level word was typed (or the model has no levels to adjust).
     */
    fun parsedSearchLevel(query: String, model: OpenCodeModel): ParsedSearchLevel {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !ModelLevelScale(model.variants).isAdjustable) return ParsedSearchLevel(null, false)
        val requested = ModelSearch.parseLevelQuery(trimmed).level ?: return ParsedSearchLevel(null, false)
        return ParsedSearchLevel(ModelSearch.resolveVariant(requested, model), true)
    }
}

// endregion

// region Pure 120 Hz analytical morph & keyboard simulation

/**
 * The pure simulation behind One Picker's glass morph, detents, interactive drag and bottom-search
 * keyboard ride, stepped by the caller each frame. Mutable in place, as its springs are.
 */
class OnePickerSimulation(
    var metrics: Metrics = Metrics(
        viewport = Rect(0f, 0f, 402f, 874f),
        sourceRect = Rect(20f, 780f, 382f, 836f),
    ),
) {
    enum class Phase { Closed, Armed, Open, Closing }

    sealed interface DragOutcome {
        data class StayAtDetent(val detent: ModelListDetent) : DragOutcome
        data object ExpandToLarge : DragOutcome
        data object CollapseToMedium : DragOutcome
        data object Dismiss : DragOutcome
    }

    data class Metrics(
        val viewport: Rect,
        val sourceRect: Rect,
        val sourceRadius: Double = 28.0,
        val ceiling: Double = 104.0,
        val compactHeight: Double = 460.0,
        val sideInset: Double = 8.0,
        val bottomInset: Double = 8.0,
        val keyboardGap: Double = 6.0,
        val topRadius: Double = 32.0,
        val bottomRadius: Double = 46.0,
        val keyboardTop: Double? = null,
    ) {
        val floorY: Double get() = viewport.bottom - bottomInset

        val activeBottomY: Double
            get() {
                val kt = keyboardTop
                return if (kt != null && kt < floorY) max(ceiling + 140, kt - keyboardGap) else floorY
            }

        val compactTopY: Double get() = min(activeBottomY - 140, max(ceiling, floorY - compactHeight))

        fun restingTopY(detent: ModelListDetent): Double =
            if (keyboardTop != null || detent == ModelListDetent.Large) ceiling else compactTopY

        val targetMinX: Double get() = viewport.left + sideInset
        val targetMaxX: Double get() = viewport.right - sideInset
    }

    data class FramePose(
        val rect: Rect,
        val targetRect: Rect,
        val layoutSize: Size,
        val topRadius: Double,
        val bottomRadius: Double,
        val contentReveal: Double,
        val contentClipHeight: Double = 0.0,
        val searchBarReveal: Double = 0.0,
        val glassOpacity: Double,
        val composerOpacity: Double,
        val backdropStrength: Double,
    )

    var phase: Phase = Phase.Closed
        private set
    var detent: ModelListDetent = ModelListDetent.Medium
        private set
    var isDragging: Boolean = false
        private set
    var reduceMotion: Boolean = false
        private set

    var top: SpringSolver = topSolver(metrics.sourceRect.top.toDouble())
    var bottom: SpringSolver = bottomSolver(metrics.sourceRect.bottom.toDouble())
    var sideProgress: SpringSolver = unitSolver(0.0, 0.25)
    var reveal: SpringSolver = unitSolver(0.0, 0.24)
    var reduceFade: SpringSolver = unitSolver(0.0, 0.20)

    private var dragOriginTop = 0.0
    private var dragOriginDetent = ModelListDetent.Medium
    private var didCrossCloseThreshold = false
    private var frozenClosingLayoutSize: Size? = null
    private var lastPinnedClosingBottom: Double? = null
    private var closingComposerRestFrames = 0

    val isShown: Boolean get() = phase != Phase.Closed
    val isOpen: Boolean get() = phase == Phase.Open

    val isAllSettled: Boolean
        get() = if (reduceMotion) {
            reduceFade.isSettled
        } else {
            top.isSettled && bottom.isSettled && sideProgress.isSettled && reveal.isSettled
        }

    fun arm(metrics: Metrics, initialDetent: ModelListDetent = ModelListDetent.Medium, reduceMotion: Boolean) {
        this.metrics = metrics
        detent = initialDetent
        this.reduceMotion = reduceMotion
        isDragging = false
        frozenClosingLayoutSize = null
        if (phase == Phase.Closing) return
        phase = Phase.Armed
        resetSolvers()
    }

    /** Launches or retargets the morph open, preserving in-flight velocity when reversing a closing picker mid-flight. */
    fun launchOpen(
        openDuration: Double = 0.35,
        openBounce: Double = 0.06,
        widthDuration: Double = 0.25,
        bottomDuration: Double = 0.267,
        contentDuration: Double = 0.24,
        reduceFadeDuration: Double = 0.20,
    ) {
        val wasClosing = phase == Phase.Closing
        frozenClosingLayoutSize = null
        phase = Phase.Open
        isDragging = false
        val targetTop = metrics.restingTopY(detent)
        val targetBottom = metrics.activeBottomY
        val keep: Double? = if (wasClosing) null else 0.0
        if (reduceMotion) {
            top = solver(targetTop, openDuration, 0.0)
            bottom = solver(targetBottom, bottomDuration, 0.0)
            sideProgress = unitSolver(1.0, widthDuration)
            reveal = unitSolver(1.0, contentDuration)
            reduceFade.retarget(1.0, keep, reduceFadeDuration, 0.0)
            return
        }
        top.retarget(targetTop, keep, openDuration, openBounce)
        bottom.retarget(targetBottom, keep, bottomDuration, 0.0)
        sideProgress.retarget(1.0, keep, widthDuration, 0.0)
        reveal.retarget(1.0, keep, contentDuration, 0.0)
    }

    /**
     * Updates keyboard geometry (the bottom search focusing or dismissing) on a critically damped
     * spring, carrying existing velocity so there is zero jump.
     */
    fun updateKeyboardTop(keyboardTop: Double?, rideDuration: Double = 0.267, omega: Double? = null, zeta: Double? = null) {
        val changed = metrics.keyboardTop != keyboardTop
        metrics = metrics.copy(keyboardTop = keyboardTop)
        if (phase != Phase.Open || isDragging) return
        val targetTop = metrics.restingTopY(detent)
        val targetBottom = metrics.activeBottomY
        if (reduceMotion) {
            top.value = targetTop
            top.target = targetTop
            bottom.value = targetBottom
            bottom.target = targetBottom
            return
        }
        if (changed) {
            top.retarget(targetTop, null, rideDuration, 0.0)
            bottom.retarget(targetBottom, null, rideDuration, 0.0)
            if (omega != null && zeta != null) {
                top.omega = omega
                top.zeta = zeta
                bottom.omega = omega
                bottom.zeta = zeta
            }
        } else if (omega != null && zeta != null && !bottom.isSettled) {
            bottom.omega = omega
            bottom.zeta = zeta
        }
    }

    /**
     * Retargets an already-closing fold (such as a grabber drag release) onto the resolved
     * composer destination, clamping downward velocity when the rising keyboard carries the
     * destination above the dragged top edge.
     */
    fun retargetClose(
        destinationRect: Rect,
        keepsKeyboard: Boolean = false,
        closeDuration: Double = 0.28,
        omega: Double? = null,
        zeta: Double? = null,
    ) {
        if (phase != Phase.Closing) return
        metrics = metrics.copy(sourceRect = destinationRect)
        // The composer only starts its keyboard move next frame: rest from before the retarget
        // isn't rest on the new destination.
        lastPinnedClosingBottom = null
        closingComposerRestFrames = -COMPOSER_REST_HOLD_AFTER_RETARGET
        if (!keepsKeyboard) metrics = metrics.copy(keyboardTop = null)
        if (reduceMotion) return
        val clampedVy: Double? = if (top.value > destinationRect.top && top.velocity > 0) 0.0 else null
        top.retarget(destinationRect.top.toDouble(), clampedVy, closeDuration, 0.0)
        bottom.retarget(destinationRect.bottom.toDouble(), null, closeDuration, 0.0)
        sideProgress.retarget(0.0, null, closeDuration, 0.0)
        if (omega != null && zeta != null) {
            for (s in listOf(top, bottom, sideProgress)) {
                s.omega = omega
                s.zeta = zeta
            }
        }
    }

    fun setDetent(next: ModelListDetent, preservingVelocity: Double? = null, duration: Double = 0.34, bounce: Double = 0.04) {
        detent = next
        isDragging = false
        if (phase != Phase.Open) return
        val targetTop = metrics.restingTopY(next)
        if (reduceMotion) {
            top.value = targetTop
            top.target = targetTop
            top.velocity = 0.0
            return
        }
        top.retarget(targetTop, preservingVelocity, duration, bounce)
    }

    fun beginDrag() {
        if (phase != Phase.Open) return
        isDragging = true
        dragOriginTop = top.value
        dragOriginDetent = detent
        didCrossCloseThreshold = false
        top.velocity = 0.0
    }

    /**
     * Applies a 1:1 finger translation ([dy] > 0 downward). Returns true once when crossing
     * [dragCloses] from Medium, so the caller plays `threshold`.
     */
    fun updateDrag(dy: Double, dragCloses: Double = 88.0): Boolean {
        if (phase != Phase.Open) return false
        if (!isDragging) beginDrag()
        val ceiling = metrics.ceiling
        val minHeight = 64.0
        val rawTop = dragOriginTop + dy
        val clampedTop = if (rawTop < ceiling) {
            val over = ceiling - rawTop
            val rubber = MotionRubberBand(36.0, 0.3).stretch(over)
            ceiling - rubber
        } else {
            min(bottom.value - minHeight, rawTop)
        }
        top.value = clampedTop
        top.target = clampedTop
        top.velocity = 0.0

        if (dragOriginDetent == ModelListDetent.Medium && dy >= dragCloses && !didCrossCloseThreshold) {
            didCrossCloseThreshold = true
            return true
        }
        if (dy < dragCloses * 0.75) didCrossCloseThreshold = false
        return false
    }

    fun endDrag(
        dy: Double,
        vy: Double,
        dragCloses: Double = 88.0,
        throwCloses: Double = 680.0,
        detentDuration: Double = 0.34,
        detentBounce: Double = 0.04,
        closeDuration: Double = 0.30,
        contentOutDuration: Double = 0.16,
        destinationRect: Rect? = null,
        keepsKeyboard: Boolean = false,
    ): DragOutcome {
        if (!isDragging) return DragOutcome.StayAtDetent(detent)
        isDragging = false
        fun close() = startClose(
            destinationRect ?: metrics.sourceRect,
            preservingTopVelocity = vy,
            keepsKeyboard = keepsKeyboard,
            closeDuration = closeDuration,
            contentOutDuration = contentOutDuration,
        )
        return when (dragOriginDetent) {
            ModelListDetent.Medium -> when {
                (dy < -36 || (vy < -420 && dy < -14)) && metrics.keyboardTop == null -> {
                    setDetent(ModelListDetent.Large, vy, detentDuration, detentBounce)
                    DragOutcome.ExpandToLarge
                }
                dy > dragCloses || (vy > throwCloses && dy > 16) -> {
                    close()
                    DragOutcome.Dismiss
                }
                else -> {
                    setDetent(ModelListDetent.Medium, vy, detentDuration, detentBounce)
                    DragOutcome.StayAtDetent(ModelListDetent.Medium)
                }
            }
            ModelListDetent.Large -> when {
                (vy > max(throwCloses * 1.25, 850.0) && dy > 40) || top.value > metrics.compactTopY + dragCloses -> {
                    close()
                    DragOutcome.Dismiss
                }
                (dy > 36 || (vy > 380 && dy > 14)) && metrics.keyboardTop == null -> {
                    setDetent(ModelListDetent.Medium, vy, detentDuration, detentBounce)
                    DragOutcome.CollapseToMedium
                }
                else -> {
                    setDetent(ModelListDetent.Large, vy, detentDuration, detentBounce)
                    DragOutcome.StayAtDetent(ModelListDetent.Large)
                }
            }
        }
    }

    /** Folds the picker smoothly back into [destinationRect], preserving any existing (or release) velocity with zero bounce. */
    fun startClose(
        destinationRect: Rect,
        preservingTopVelocity: Double? = null,
        keepsKeyboard: Boolean = false,
        holdBottomUntilKeyboardSync: Boolean = false,
        closeDuration: Double = 0.30,
        contentOutDuration: Double = 0.16,
        reduceFadeDuration: Double = 0.20,
    ) {
        if (phase == Phase.Armed || phase == Phase.Closed) {
            reset()
            return
        }
        if (frozenClosingLayoutSize == null) frozenClosingLayoutSize = drawnFrame().targetRect.size
        metrics = metrics.copy(sourceRect = destinationRect)
        if (!keepsKeyboard) metrics = metrics.copy(keyboardTop = null)
        isDragging = false
        phase = Phase.Closing
        lastPinnedClosingBottom = null
        closingComposerRestFrames = 0
        if (reduceMotion) {
            reduceFade.retarget(0.0, null, reduceFadeDuration, 0.0)
            return
        }
        top.retarget(destinationRect.top.toDouble(), preservingTopVelocity, closeDuration, 0.0)
        if (holdBottomUntilKeyboardSync) {
            bottom.retarget(bottom.value, null, closeDuration, 0.0)
        } else {
            bottom.retarget(destinationRect.bottom.toDouble(), null, closeDuration, 0.0)
        }
        sideProgress.retarget(0.0, null, closeDuration, 0.0)
        reveal.retarget(0.0, null, contentOutDuration, 0.0)
    }

    fun reset() {
        phase = Phase.Closed
        isDragging = false
        detent = ModelListDetent.Medium
        frozenClosingLayoutSize = null
        lastPinnedClosingBottom = null
        closingComposerRestFrames = 0
        metrics = metrics.copy(keyboardTop = null)
        resetSolvers()
    }

    /**
     * Closing with no keyboard, the live composer owns the bottom edge: the controller pins it
     * there once a frame. Once the composer has come to rest, the fold lands on it: off its
     * destination (the keyboard came or went, or the synthetic resting rect was off), the
     * destination moves onto it and `top` retargets there with its velocity, so the glass never
     * detaches or stretches; and once the rest of the fold has settled, the bottom takes the
     * composer's edge as its target, so the close can't be held open forever. While the close
     * still waits on the keyboard ([countsRest] false) the composer only pins the bottom.
     */
    fun pinClosingToComposer(live: Rect, countsRest: Boolean = true) {
        try {
            bottom.value = live.bottom.toDouble()
            val last = lastPinnedClosingBottom
            if (phase != Phase.Closing || reduceMotion || last == null) return
            if (!countsRest || abs(live.bottom - last) > bottom.positionEpsilon) {
                closingComposerRestFrames = 0
                return
            }
            closingComposerRestFrames += 1
            if (closingComposerRestFrames < COMPOSER_REST_FRAMES) return
            val src = metrics.sourceRect
            if (abs(live.top - src.top) > 0.5f || abs(live.bottom - src.bottom) > 0.5f) {
                metrics = metrics.copy(sourceRect = Rect(src.left, live.top, src.right, live.top + live.height))
                top.target = live.top.toDouble()
            }
            if (!(top.isSettled && sideProgress.isSettled && reveal.isSettled)) return
            bottom.target = live.bottom.toDouble()
            bottom.velocity = 0.0
        } finally {
            lastPinnedClosingBottom = live.bottom.toDouble()
        }
    }

    /** Advances all active springs by [dt] seconds. Returns true when settled. */
    fun step(dt: Double): Boolean {
        if (phase != Phase.Open && phase != Phase.Closing) return true
        if (isDragging) {
            bottom.step(dt)
            sideProgress.step(dt)
            reveal.step(dt)
            return false
        }
        if (reduceMotion) {
            val done = reduceFade.step(dt).isSettled
            if (done && phase == Phase.Closing) phase = Phase.Closed
            return done
        }
        val tDone = top.step(dt).isSettled
        val bDone = bottom.step(dt).isSettled
        val sDone = sideProgress.step(dt).isSettled
        val rDone = reveal.step(dt).isSettled
        val allDone = tDone && bDone && sDone && rDone
        if (allDone && phase == Phase.Closing) phase = Phase.Closed
        return allDone
    }

    fun drawnFrame(): FramePose {
        val targetTop = metrics.restingTopY(detent)
        val targetBottom = metrics.activeBottomY
        val tx0 = snapGrid(metrics.targetMinX)
        val tx1 = snapGrid(metrics.targetMaxX)
        val ty0 = snapGrid(targetTop)
        val ty1 = snapGrid(max(targetTop + 120, targetBottom))
        val targetRect = rect(tx0, ty0, max(1.0, tx1 - tx0), max(120.0, ty1 - ty0))
        val layoutSize = (if (phase == Phase.Closing) frozenClosingLayoutSize else null) ?: targetRect.size

        if (reduceMotion) {
            val alpha = reduceFade.value.coerceIn(0.0, 1.0)
            val minSide = min(targetRect.width, targetRect.height)
            val bRad = if (metrics.keyboardTop != null) metrics.topRadius else metrics.bottomRadius
            val gAlpha = if (phase == Phase.Armed || phase == Phase.Closed) 0.0 else alpha
            val compOp = 1 - gAlpha
            val clipH = when {
                gAlpha <= 0 -> 0.0
                compOp > 0.02 -> max(0.0, targetRect.height - metrics.sourceRect.height - 8.0)
                else -> targetRect.height.toDouble()
            }
            return FramePose(
                rect = targetRect,
                targetRect = targetRect,
                layoutSize = layoutSize,
                topRadius = radius(metrics.topRadius, minSide),
                bottomRadius = radius(bRad, minSide),
                contentReveal = alpha,
                contentClipHeight = clipH,
                searchBarReveal = if (compOp > 0.02) 0.0 else alpha,
                glassOpacity = gAlpha,
                composerOpacity = compOp,
                backdropStrength = alpha,
            )
        }

        val pw = sideProgress.value.coerceIn(0.0, 1.0)
        val src = metrics.sourceRect
        val rawLeft = src.left + (metrics.targetMinX - src.left) * pw
        val rawRight = src.right + (metrics.targetMaxX - src.right) * pw
        val x0 = snapGrid(rawLeft)
        val x1 = snapGrid(rawRight)
        val y0 = snapGrid(top.value)
        val y1 = snapGrid(max(top.value + 44, bottom.value))
        val rect = rect(x0, y0, max(1.0, x1 - x0), max(44.0, y1 - y0))

        val minSide = min(rect.width, rect.height)
        val keyboardLift = ((metrics.floorY - bottom.value) / 140).coerceIn(0.0, 1.0)
        val targetBottomR = metrics.bottomRadius + (metrics.topRadius - metrics.bottomRadius) * keyboardLift
        val rawTopR = metrics.sourceRadius + (metrics.topRadius - metrics.sourceRadius) * pw
        val rawBottomR = metrics.sourceRadius + (targetBottomR - metrics.sourceRadius) * pw

        if (phase == Phase.Closed) {
            return FramePose(
                rect = rect,
                targetRect = targetRect,
                layoutSize = layoutSize,
                topRadius = radius(rawTopR, minSide),
                bottomRadius = radius(rawBottomR, minSide),
                contentReveal = 0.0,
                glassOpacity = 0.0,
                composerOpacity = 1.0,
                backdropStrength = 0.0,
            )
        }

        // Pure geometric height fraction with strict spatial disjointness: while the composer
        // shows (geomFrac < 0.28) the clip keeps the picker's header and rows at least 8 above
        // the composer's top edge, unrolling downward over 0.28…0.48 only once it has gone.
        // max(composerOpacity, contentReveal) ≥ 0.45 throughout, so the glass is never empty.
        val baseH = min(src.height.toDouble(), targetRect.height.toDouble())
        val spanH = max(targetRect.height - baseH, 1.0)
        val geomFrac = ((rect.height - baseH) / spanH).coerceIn(0.0, 1.0)
        val composerAlpha = smoothstep(1 - ((geomFrac - 0.06) / 0.22).coerceIn(0.0, 1.0))
        val compBand = src.height + 8.0
        val curtainRelease = smoothstep(((geomFrac - 0.28) / 0.20).coerceIn(0.0, 1.0))
        val clipH = snapGrid(max(0.0, rect.height - compBand * (1 - curtainRelease)))
        val roomGate = smoothstep(((clipH - 16) / 64).coerceIn(0.0, 1.0))
        val geomReveal = smoothstep(((geomFrac - 0.08) / 0.28).coerceIn(0.0, 1.0))
        val searchReveal = smoothstep(((geomFrac - 0.38) / 0.20).coerceIn(0.0, 1.0))

        return FramePose(
            rect = rect,
            targetRect = targetRect,
            layoutSize = layoutSize,
            topRadius = radius(rawTopR, minSide),
            bottomRadius = radius(rawBottomR, minSide),
            contentReveal = min(geomReveal, roomGate),
            contentClipHeight = clipH,
            searchBarReveal = searchReveal,
            glassOpacity = 1.0,
            composerOpacity = composerAlpha,
            backdropStrength = pw,
        )
    }

    private fun resetSolvers() {
        top = topSolver(metrics.sourceRect.top.toDouble())
        bottom = bottomSolver(metrics.sourceRect.bottom.toDouble())
        sideProgress = unitSolver(0.0, 0.25)
        reveal = unitSolver(0.0, 0.24)
        reduceFade = unitSolver(0.0, 0.20)
    }

    companion object {
        /**
         * Frames the live composer must hold still before the close lands on it, so the first
         * frames of a keyboard curve don't read as rest.
         */
        const val COMPOSER_REST_FRAMES = 3

        /**
         * Extra still frames after a retarget before the close lands on the composer, unless it
         * moves first: a keyboard curve can start a few frames after its notification.
         */
        const val COMPOSER_REST_HOLD_AFTER_RETARGET = 12

        /**
         * Two-edge `1/64` snapping (`kit-motion` rule): snapping both edges and subtracting
         * guarantees an anchored edge never jiggles by one sub-pixel quantum.
         */
        fun snapGrid(value: Double): Double {
            val scaled = value * 64
            return sign(scaled) * floor(abs(scaled) + 0.5) / 64
        }

        fun smoothstep(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        private fun radius(radius: Double, side: Float): Double =
            OpenCodeShapeGeometry.radius(radius.toFloat(), side).toDouble()

        private fun rect(x: Double, y: Double, width: Double, height: Double): Rect =
            Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())

        private fun solver(value: Double, duration: Double, bounce: Double, epsilons: Boolean = false): SpringSolver {
            val p = SpringSolver.parameters(duration, bounce)
            return if (epsilons) {
                SpringSolver(value, 0.0, value, p.omega, p.zeta, positionEpsilon = 0.001, velocityEpsilon = 0.02)
            } else {
                SpringSolver(value, 0.0, value, p.omega, p.zeta)
            }
        }

        private fun topSolver(value: Double) = solver(value, 0.35, 0.06)
        private fun bottomSolver(value: Double) = solver(value, 0.267, 0.0)
        private fun unitSolver(value: Double, duration: Double) = solver(value, duration, 0.0, epsilons = true)
    }
}

// endregion
