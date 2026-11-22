@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.OpenCodeBottomSheet
import ai.opencode.feature.composer.R
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The stock sheet One Picker opens in when its morph can't present ([OnePickerSheetHost]), plus the
 * favourite swipe its rows share. The plain (Today) picker these used to sit with graduated away
 * with One Picker.
 */

/**
 * One Picker's sheet (while the composer is covered) opens at the partially expanded rest and drag
 * up to full. Dragged down from full, a flick at [ModelPickerTunables.flickCloses] (800 dp/s) or
 * faster closes them and a slower drag rests partially expanded (a probe beside the sheet's own
 * drag reads the release velocity, which the settled value doesn't carry). Under TalkBack the
 * grabber still steps between the two rests: collapsing there isn't a request to close.
 *
 * [onClose] asks the owner to close it (`activePicker = nil`); [onClosed] runs once it leaves
 * however it closed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPickerDetentsSheet(
    onClose: () -> Unit,
    onClosed: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnClosed by rememberUpdatedState(onClosed)
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    // How fast the latest drag on the sheet was moving down (dp/s), kept as it moves.
    var downwardVelocity by remember { mutableFloatStateOf(0f) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SheetState>(1) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { target ->
            val current = holder[0]?.currentValue
            val talkBack = accessibility?.isTouchExplorationEnabled == true
            if (current == SheetValue.Expanded && target == SheetValue.PartiallyExpanded && !talkBack &&
                downwardVelocity >= ModelPickerTunables.flickCloses
            ) {
                scope.launch { holder[0]?.hide() }.invokeOnCompletion { currentOnClose() }
                false
            } else {
                true
            }
        },
    )
    holder[0] = sheetState
    DisposableEffect(Unit) { onDispose { currentOnClosed() } }

    val probe = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                // Local positions move with the sheet and under-report a flick. Track the original
                // pointer samples.
                tracker.addPointerInputChange(change)
                val vy = tracker.calculateVelocity().y
                downwardVelocity = with(density) { maxOf(vy, 0f).toDp().value }
            }
        }
    }

    // A pick, Cancel or Done closes the sheet by removing it (`activePicker = nil`), which on its
    // own would drop it from the screen in one frame: it slides down first, then the event goes.
    var hiding by remember { mutableStateOf(false) }
    val hideThen: (() -> Unit) -> Unit = { action ->
        if (!hiding) {
            hiding = true
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
        }
    }
    OpenCodeBottomSheet(
        onDismissRequest = { currentOnClose() },
        sheetState = sheetState,
        dragHandle = { Box(probe) { BottomSheetDefaults.DragHandle() } },
    ) {
        // Search's keyboard lifts a medium sheet. Keep that expanded rest after the keyboard goes,
        // but don't fight a subsequent drag back down.
        val imeVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        var liftedForIme by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible, sheetState.currentValue) {
            if (!imeVisible) {
                liftedForIme = false
            } else if (sheetState.currentValue == SheetValue.PartiallyExpanded && sheetState.targetValue != SheetValue.Hidden) {
                if (liftedForIme) {
                    // A slow drag back down is the reader's choice, not another keyboard rise.
                    // Don't bounce back to full.
                    focusManager.clearFocus()
                } else {
                    liftedForIme = true
                    sheetState.expand()
                }
            }
        }
        CompositionLocalProvider(LocalModelSheetHideThen provides hideThen) {
            Box(probe.fillMaxWidth()) { content() }
        }
    }
}

/** The enclosing [ModelPickerDetentsSheet]'s slide-down, run before an event that closes it. */
internal val LocalModelSheetHideThen = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** Yellow swipe tint (One Picker's swipe actions). */
internal val FavoriteYellow = Color(0xFFFFCC00)

/**
 * A trailing swipe that favorites or unfavorites the row: swiped past the threshold it toggles and
 * springs back.
 */
@Composable
internal fun FavoriteSwipeBox(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    tint: Color = FavoriteYellow,
    /** The Favorite action's glyph. */
    favoriteSymbol: SFSymbol = SFSymbol.StarFill,
    /** The row's own fill, drawn over the action while it rests. */
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    /** One Picker's rows also swipe from the leading edge. */
    swipesFromStart: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Not saveable: a row that leaves its section mid-swipe and comes back would toggle again.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val toggle by rememberUpdatedState(onToggle)
    // SwipeToDismissBox calls onDismiss again whenever the lambda changes, so it must stay the same.
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember(swipe, scope) {
        { value ->
            if (value != SwipeToDismissBoxValue.Settled) toggle()
            scope.launch { swipe.reset() }
        }
    }
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = swipesFromStart,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(tint).padding(horizontal = 20.dp),
                horizontalArrangement = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(if (isFavorite) SFSymbol.StarSlash else favoriteSymbol, null, size = 20.dp, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(if (isFavorite) stringResource(R.string.composer_model_unfavorite) else stringResource(R.string.composer_model_favorite), color = Color.White, style = OpenCodeTextStyles.subheadline)
            }
        },
        onDismiss = onDismiss,
    ) {
        Box(Modifier.background(containerColor)) { content() }
    }
}
