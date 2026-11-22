package ai.opencode.core.sharing

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog

/**
 * One switch that turns every experiment on except [ExperimentalFeature.joinsAll] opt-outs, writing
 * each experiment's own switch. Switching it off restores what was saved.
 */
object AllExperiments {
    /** Settings › Experimental › All Experiments. */
    val key: SharedKey<Boolean> = SharedKey.appStorage("experimental-all", false)

    /** One experiment whose switch All Experiments changed, and what it's now. */
    data class Change(val feature: ExperimentalFeature, val on: Boolean)
}

/** Switched off by hand while All Experiments was on. */
fun allExperimentsSkips(feature: ExperimentalFeature): SharedKey<Boolean> =
    SharedKey.appStorage("experimental-all-skip-${feature.rawValue}", false)

/**
 * The switch as it was before All Experiments turned on, put back when it turns off; null while
 * it's off.
 */
fun allExperimentsSaved(feature: ExperimentalFeature): SharedKey<Boolean?> =
    SharedKey.appStorage<Boolean?>("experimental-all-saved-${feature.rawValue}", null)

/** The experiments All Experiments switches, in Settings order. */
val ExperimentalFeature.Companion.joiningAll: List<ExperimentalFeature>
    get() {
        val result = mutableListOf<ExperimentalFeature>()
        for (feature in ExperimentalFeature.allCases) {
            if (feature.joinsAll) result.add(feature)
        }
        return result
    }

/** Saves each switch, then turns on every experiment not skipped. Returns the ones whose switch changed. */
fun ExperimentalFeature.Companion.switchAllOn(): List<AllExperiments.Change> {
    val changed = mutableListOf<AllExperiments.Change>()
    for (feature in joiningAll) {
        val enabled = shared(experimental(feature))
        val skipped = shared(allExperimentsSkips(feature)).value
        shared(allExperimentsSaved(feature)).set(enabled.value)
        val next = !skipped
        if (enabled.value != next) {
            enabled.set(next)
            changed.add(AllExperiments.Change(feature, next))
        }
    }
    return changed
}

/** Puts back each saved switch and forgets it, keeping the skips. Returns the ones whose switch changed. */
fun ExperimentalFeature.Companion.restoreFromAll(): List<AllExperiments.Change> {
    val store = dependency(SharedStore)
    val changed = mutableListOf<AllExperiments.Change>()
    for (feature in joiningAll) {
        val enabled = shared(experimental(feature))
        val previous = shared(allExperimentsSaved(feature)).value ?: continue
        store.remove(allExperimentsSaved(feature))
        if (enabled.value != previous) {
            enabled.set(previous)
            changed.add(AllExperiments.Change(feature, previous))
        }
    }
    return changed
}

/**
 * At launch with All Experiments on, an experiment with nothing saved arrived since: save its
 * switch and turn it on unless it was skipped.
 */
fun ExperimentalFeature.Companion.adoptNewExperimentsIntoAll() {
    if (!shared(AllExperiments.key).value) return
    for (feature in joiningAll) {
        val saved = shared(allExperimentsSaved(feature))
        if (saved.value != null) continue
        val enabled = shared(experimental(feature))
        saved.set(enabled.value)
        val next = !shared(allExperimentsSkips(feature)).value
        if (enabled.value != next) {
            enabled.set(next)
            AppLog.app.info("All Experiments turned on a new experiment feature=${feature.rawValue}")
        }
    }
}
