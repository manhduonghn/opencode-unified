package ai.opencode.core.experiments

import ai.opencode.core.R
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.experimental
import ai.opencode.core.strings.localized

/**
 * The Experimental settings groups, in `:core` beside the registry itself. The typealiases let code
 * import everything experimental from this package.
 */
typealias ExperimentalFeature = ai.opencode.core.sharing.ExperimentalFeature
typealias ExperimentalFeatures = ai.opencode.core.sharing.ExperimentalFeatures

/** One experimental feature's switch: `experimental-<raw>`, off by default. */
val ExperimentalFeature.key: SharedKey<Boolean> get() = experimentalKeys.getValue(this)

private val experimentalKeys: Map<ExperimentalFeature, SharedKey<Boolean>> =
    ExperimentalFeature.entries.associateWith { experimental(it) }

/** SF Symbol name. */
val ExperimentalFeature.systemImage: String get() = symbol

/** Settings › Experimental's sections, in order, all open. */
enum class ExperimentalGroup {
    composer, calls, drawer, transcript;

    val id: String get() = name

    val title: String
        get() = when (this) {
            composer -> localized(R.string.core_experiment_group_composer_title)
            calls -> localized(R.string.core_experiment_group_calls_title)
            transcript -> localized(R.string.core_experiment_group_transcript_title)
            drawer -> localized(R.string.core_experiment_group_drawer_title)
        }

    /** The group's experiments, in Settings order. */
    val features: List<ExperimentalFeature>
        get() = ExperimentalFeature.allCases.filter { it.group == this }
}
