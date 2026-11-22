package ai.opencode.core.store

/** What a reducer returns: the next state and the effect to run. */
data class Reduced<out S, out A>(val state: S, val effect: Effect<A> = Effect.none())

/**
 * Port of TCA's `Reducer`: a pure function of (state, action). Never call `reduce` recursively;
 * extract a private helper returning [Reduced].
 */
fun interface Reducer<S, A> {
    fun reduce(state: S, action: A): Reduced<S, A>
}

/** Runs reducers in order over the same action, merging their effects (TCA's `CombineReducers`/body). */
fun <S, A> combine(vararg reducers: Reducer<S, A>): Reducer<S, A> = Reducer { state, action ->
    var current = state
    val effects = ArrayList<Effect<A>>(reducers.size)
    for (reducer in reducers) {
        val reduced = reducer.reduce(current, action)
        current = reduced.state
        effects += reduced.effect
    }
    Reduced(current, Effect.merge(effects))
}

/** Port of TCA's `Scope(state:action:)`. Put it before the parent's own reducer in [combine]. */
fun <S, A, CS, CA> scope(
    state: (S) -> CS,
    setState: (S, CS) -> S,
    action: (A) -> CA?,
    embed: (CA) -> A,
    child: Reducer<CS, CA>,
): Reducer<S, A> = Reducer { parent, parentAction ->
    val childAction = action(parentAction) ?: return@Reducer Reduced(parent)
    val reduced = child.reduce(state(parent), childAction)
    Reduced(setState(parent, reduced.state), reduced.effect.map(embed))
}

/**
 * Port of TCA's `.ifLet(\.$child, action: \.child)`. The parent reduces
 * [PresentationAction.Dismiss] first; when the child goes or its [childID] changes, its effects are
 * cancelled. [id] must be stable.
 */
fun <S, A, CS : Any, CA> ifLet(
    id: Any,
    parent: Reducer<S, A>,
    state: (S) -> CS?,
    setState: (S, CS?) -> S,
    action: (A) -> PresentationAction<CA>?,
    embed: (PresentationAction<CA>) -> A,
    child: Reducer<CS, CA>,
    childID: (CS) -> Any? = { it::class },
): Reducer<S, A> = Reducer { current, parentAction ->
    val presentation = action(parentAction)
    var next = current
    val effects = ArrayList<Effect<A>>(3)
    val before = state(current)
    val beforeID = before?.let { PresentationCancelID(id, childID(it)) }
    if (presentation is PresentationAction.Presented && before != null) {
        val reduced = child.reduce(before, presentation.action)
        next = setState(next, reduced.state)
        effects += reduced.effect
            .map { embed(PresentationAction.Presented(it)) }
            .navigationScoped(beforeID!!)
            .cancellable(beforeID)
    }
    val childBeforeParent = state(next)
    val parentReduced = parent.reduce(next, parentAction)
    next = parentReduced.state
    effects += parentReduced.effect
    if (presentation is PresentationAction.Dismiss && state(next) === childBeforeParent) {
        next = setState(next, null)
    }
    val after = state(next)
    val afterID = after?.let { PresentationCancelID(id, childID(it)) }
    if (beforeID != null && beforeID != afterID) effects += Effect.cancel(beforeID)
    Reduced(next, Effect.merge(effects))
}

/**
 * Port of TCA's plain-optional `.ifLet(\.child, action: \.child)`: runs [child] while its state
 * exists. Pass a stable [slot] when rebuilding a reducer.
 */
fun <S, A, CS : Any, CA> ifLet(
    parent: Reducer<S, A>,
    state: (S) -> CS?,
    setState: (S, CS) -> S,
    action: (A) -> CA?,
    embed: (CA) -> A,
    child: Reducer<CS, CA>,
    slot: Any = Any(),
    childID: (CS) -> Any? = { it::class },
): Reducer<S, A> = Reducer { current, parentAction ->
    var next = current
    val effects = ArrayList<Effect<A>>(2)
    val childAction = action(parentAction)
    val childState = state(next)
    val beforeID = childState?.let { PresentationCancelID(slot, childID(it)) }
    if (childAction != null && childState != null) {
        val reduced = child.reduce(childState, childAction)
        next = setState(next, reduced.state)
        effects += reduced.effect.map(embed).navigationScoped(beforeID!!).cancellable(beforeID)
    }
    val parentReduced = parent.reduce(next, parentAction)
    effects += parentReduced.effect
    val afterID = state(parentReduced.state)?.let { PresentationCancelID(slot, childID(it)) }
    if (beforeID != null && beforeID != afterID) effects += Effect.cancel(beforeID)
    Reduced(parentReduced.state, Effect.merge(effects))
}

private data class PresentationCancelID(val slot: Any, val child: Any?)

/** Port of TCA's `PresentationAction`: a presented child's action, or its dismissal. */
sealed interface PresentationAction<out A> {
    data class Presented<A>(val action: A) : PresentationAction<A>
    data object Dismiss : PresentationAction<Nothing>
}

/**
 * Port of TCA's `.forEach(\.items, action: \.items)` over an identified list. Each element has
 * its own navigation path and its effects are cancelled when the element is removed.
 */
fun <S, A, ID : Any, CS, CA> forEach(
    slot: Any,
    parent: Reducer<S, A>,
    elements: (S) -> List<CS>,
    setElements: (S, List<CS>) -> S,
    elementID: (CS) -> ID,
    action: (A) -> Pair<ID, CA>?,
    embed: (ID, CA) -> A,
    child: Reducer<CS, CA>,
): Reducer<S, A> = Reducer { current, parentAction ->
    var next = current
    val effects = ArrayList<Effect<A>>(3)
    action(parentAction)?.let { (id, childAction) ->
        val list = elements(next)
        val index = list.indexOfFirst { elementID(it) == id }
        if (index >= 0) {
            val reduced = child.reduce(list[index], childAction)
            next = setElements(next, list.toMutableList().also { it[index] = reduced.state })
            val navigationID = ForEachCancelID(slot, id)
            effects += reduced.effect.map { embed(id, it) }.navigationScoped(navigationID).cancellable(navigationID)
        }
    }
    val before = elements(current).map(elementID).toSet()
    val parentReduced = parent.reduce(next, parentAction)
    next = parentReduced.state
    effects += parentReduced.effect
    val removed = before - elements(next).map(elementID).toSet()
    if (removed.isNotEmpty()) effects += Effect.cancel(removed.map { ForEachCancelID(slot, it) })
    Reduced(next, Effect.merge(effects))
}

private data class ForEachCancelID(val slot: Any, val id: Any)

/** Port of TCA's `.onChange(of:)`. Key it on a small value with cheap equality. */
fun <S, A, V> Reducer<S, A>.onChange(
    select: (S) -> V,
    onChange: (old: V, new: V, state: S) -> Reduced<S, A>,
): Reducer<S, A> = Reducer { state, action ->
    val old = select(state)
    val reduced = this.reduce(state, action)
    val new = select(reduced.state)
    if (old == new) {
        reduced
    } else {
        val changed = onChange(old, new, reduced.state)
        Reduced(changed.state, Effect.merge(reduced.effect, changed.effect))
    }
}
