package com.silverbullet.kode.core.designsystem

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The two resting positions of a [SwipeReveal] row. */
enum class SwipeRevealValue { Closed, Open }

/**
 * The physics of the reveal, ported from T3 Code mobile's
 * `THREAD_SWIPE_SPRING` in `apps/mobile/src/features/home/thread-swipe-actions.tsx`.
 *
 * Reanimated states springs as `{ damping, mass, stiffness }` while Compose
 * fixes mass at 1 and takes a damping *ratio*, so the numbers are converted
 * rather than copied: `ratio = damping / 2√(stiffness·mass) = 26 / 2√(330·0.7)`
 * and `stiffness = ω² = 330/0.7` for the same natural frequency.
 */
private val RevealSpring: AnimationSpec<Float> = spring(
    dampingRatio = 0.85f,
    stiffness = 470f,
)

/**
 * How far the row must travel before release snaps it open, as a fraction of
 * the action panel's width. T3 Code's `rightThreshold` is `actionsWidth * 0.42`
 * — a little before halfway, so the gesture never feels like it needs
 * finishing.
 */
private const val OPEN_THRESHOLD_FRACTION = 0.42f

/**
 * Keeps at most one row revealed across a list.
 *
 * Two rows open at once reads as one of them being stuck rather than as a
 * choice, so opening a row closes the last. Deliberately *not* observable
 * state: rows call into it and never read from it, so arbitrating between them
 * cannot invalidate a composition.
 */
@Stable
class SwipeRevealCoordinator internal constructor(private val scope: CoroutineScope) {

    private var openRow: SwipeRevealState? = null

    /** Animates the revealed row shut, if there is one. */
    fun closeOpenRow() {
        val row = openRow ?: return
        openRow = null
        scope.launch { row.close() }
    }

    internal fun claim(row: SwipeRevealState) {
        val previous = openRow
        if (previous === row) return
        openRow = row
        // Launched, not awaited: the claiming row is mid-gesture and must not
        // wait on the outgoing row's animation.
        if (previous != null) scope.launch { previous.close() }
    }

    internal fun release(row: SwipeRevealState) {
        if (openRow === row) openRow = null
    }
}

/**
 * A [SwipeRevealCoordinator] living as long as the calling composition.
 *
 * Hoist this at the list, not the row: closing a row that has just scrolled out
 * of composition still needs a scope to run in.
 */
@Composable
fun rememberSwipeRevealCoordinator(): SwipeRevealCoordinator {
    val scope = rememberCoroutineScope()
    return remember(scope) { SwipeRevealCoordinator(scope) }
}

/** One row's reveal position. */
@Stable
class SwipeRevealState internal constructor(
    /**
     * The row's scope, not the action's.
     *
     * [requestClose] outlives the thing that calls it: tapping an action closes
     * the row, and the actions leave composition the instant it finishes. A
     * scope remembered alongside the button would be cancelled by its own
     * animation completing.
     */
    private val scope: CoroutineScope,
    internal val draggable: AnchoredDraggableState<SwipeRevealValue>,
) {
    /**
     * True from the first pixel of a reveal until the row is fully back at
     * rest.
     *
     * Wider than "settled open" on purpose: a tap landing during the opening
     * animation should still be read as dismissing the reveal, not as a tap on
     * the row underneath.
     */
    val isRevealing: Boolean
        get() {
            if (draggable.targetValue != SwipeRevealValue.Closed) return true
            val offset = draggable.offset
            return !offset.isNaN() && offset != 0f
        }

    /** Animates the row shut without waiting for it. */
    fun requestClose() {
        scope.launch { close() }
    }

    suspend fun close() {
        if (draggable.targetValue == SwipeRevealValue.Closed &&
            draggable.settledValue == SwipeRevealValue.Closed
        ) {
            return
        }
        draggable.animateTo(SwipeRevealValue.Closed, RevealSpring)
    }
}

/**
 * Provided around [SwipeReveal]'s actions so a button can dismiss the row it
 * belongs to without the caller threading the state down by hand.
 */
internal val LocalSwipeRevealState = staticCompositionLocalOf<SwipeRevealState?> { null }

/**
 * A row that slides aside to reveal [actions] pinned to its trailing edge.
 *
 * Only the trailing direction has an anchor, so a drag the other way is clamped
 * at rest instead of having to be rejected. [actionsWidth] is a fixed size
 * rather than a measurement: knowing it up front means the anchors exist in the
 * first composition, with no frame in which the row could be dragged against
 * empty anchors.
 *
 * The reveal runs in the layout phase only — the offset is applied inside a
 * [layout] block, so a drag re-places [content] without recomposing or
 * redrawing it. [actions] are composed only while the row is off its resting
 * position, so a long list does not pay for buttons nobody can see.
 *
 * Left-to-right is assumed throughout, matching the rest of the app: the drag
 * direction is pinned with `reverseDirection = false` and the panel is placed
 * absolutely, so the two cannot disagree.
 *
 * @param enabled when false the row cannot be dragged. Prefer omitting this
 *  container entirely for rows with no available action — a row whose action
 *  disappears while open would otherwise stay stranded.
 */
@Composable
fun SwipeReveal(
    coordinator: SwipeRevealCoordinator,
    modifier: Modifier = Modifier,
    actionsWidth: Dp = SwipeRevealDefaults.ActionsWidth,
    enabled: Boolean = true,
    actions: @Composable BoxScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeRevealState(actionsWidth)
    val draggable = state.draggable

    // Read in an effect rather than in composition: `targetValue` flips as the
    // finger crosses the threshold, and depending on it here would recompose
    // the row mid-drag for a value only the coordinator cares about.
    LaunchedEffect(state, coordinator) {
        snapshotFlow { draggable.targetValue }.collect { target ->
            if (target == SwipeRevealValue.Open) coordinator.claim(state) else coordinator.release(state)
        }
    }

    // Scrolling an open row out of a lazy list disposes it, and its state is
    // not saved — it comes back closed. Dropping the coordinator's reference
    // keeps it from holding, and trying to animate, a row that no longer
    // exists.
    DisposableEffect(state, coordinator) {
        onDispose { coordinator.release(state) }
    }

    // `offset` changes every frame but this boolean does not, so the actions
    // enter and leave composition once per gesture rather than per frame.
    val revealed by remember(draggable) {
        derivedStateOf {
            val offset = draggable.offset
            !offset.isNaN() && offset != 0f
        }
    }

    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = draggable,
        positionalThreshold = { distance -> distance * OPEN_THRESHOLD_FRACTION },
        animationSpec = RevealSpring,
    )

    Box(modifier = modifier.fillMaxWidth()) {
        if (revealed) {
            // `matchParentSize` takes the row's height without contributing to
            // it, which is the only way to fill a parent whose height comes
            // from a sibling inside an unbounded LazyColumn item.
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                CompositionLocalProvider(LocalSwipeRevealState provides state) {
                    actions()
                }
            }
        }

        Box(
            modifier = Modifier
                // Order is load-bearing. `layout` must sit *outside*
                // `anchoredDraggable` so the drag's pointer region is placed by
                // it and travels with the row. The other way round, the pointer
                // region stays at the row's resting rect, covering the revealed
                // strip; because the row is the topmost sibling and a drag node
                // does not share pointer input with siblings, that swallows
                // every tap meant for the actions behind it.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        val offset = draggable.offset
                        placeable.place(if (offset.isNaN()) 0 else offset.roundToInt(), 0)
                    }
                }
                .anchoredDraggable(
                    state = draggable,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    flingBehavior = flingBehavior,
                ),
        ) {
            CompositionLocalProvider(LocalSwipeRevealState provides state) {
                content()
            }
        }
    }
}

@Composable
private fun rememberSwipeRevealState(actionsWidth: Dp): SwipeRevealState {
    val actionsWidthPx = with(LocalDensity.current) { actionsWidth.toPx() }
    val scope = rememberCoroutineScope()
    return remember(actionsWidthPx, scope) {
        SwipeRevealState(
            scope,
            AnchoredDraggableState(SwipeRevealValue.Closed).apply {
                updateAnchors(
                    DraggableAnchors {
                        SwipeRevealValue.Closed at 0f
                        SwipeRevealValue.Open at -actionsWidthPx
                    },
                )
            },
        )
    }
}

/**
 * Wraps [onClick] so the row closes as the action runs.
 *
 * Doing this for every action is what makes the panel feel like a menu rather
 * than a mode: the row is back at rest by the time the result lands.
 */
@Composable
fun rememberSwipeRevealAction(onClick: () -> Unit): () -> Unit {
    val state = LocalSwipeRevealState.current
    return remember(state, onClick) {
        {
            state?.requestClose()
            onClick()
        }
    }
}

/**
 * Wraps a row's own click so a revealed row closes instead of activating.
 *
 * The alternative — leaving the row tappable while its actions are showing — is
 * how a swipe that was meant to reveal ends up opening the thread instead.
 * Outside a [SwipeReveal] this is [onClick] unchanged.
 */
@Composable
fun rememberSwipeRevealRowClick(onClick: () -> Unit): () -> Unit {
    val state = LocalSwipeRevealState.current
    return remember(state, onClick) {
        {
            // Read at click time, not in composition: this must not subscribe
            // the row to an offset that changes every frame.
            if (state != null && state.isRevealing) state.requestClose() else onClick()
        }
    }
}

object SwipeRevealDefaults {
    /**
     * One action column, matching T3 Code mobile's `ACTION_ITEM_WIDTH`: wide
     * enough for a circular icon button with a caption beneath it.
     */
    val ActionsWidth: Dp = 58.dp
}
