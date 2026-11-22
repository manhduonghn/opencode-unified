package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.R
import ai.opencode.server.model.ModelVariant
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * The thinking levels a model offers, as the level panel and the meter's slide step through
 * them: the model's default first, then its variants lowest to highest. A null level is the
 * default.
 */
class ModelLevelScale(variants: List<String>) {
    val levels: List<String?> = listOf<String?>(null) + ModelVariant.sorted(variants.filter { it != "default" })

    val count: Int get() = levels.size

    /** A model without variants has only its default: nothing to change. */
    val isAdjustable: Boolean get() = levels.size > 1

    /** The variants alone, for the level bars. */
    val variants: List<String> get() = levels.filterNotNull()

    /** A level's stop; the default, `"default"`, or a level the model no longer offers is the first. */
    fun index(of: String?): Int {
        if (of == null || of == "default") return 0
        return levels.indexOf(of).takeIf { it >= 0 } ?: 0
    }

    fun level(at: Int): String? = levels[at.coerceIn(0, levels.size - 1)]

    /** One step up or down, stopping at the ends (TalkBack's adjust action). */
    fun stepped(from: String?, by: Int): String? = level(index(from) + by)

    // Scrub

    /**
     * Holding the meter and sliding: every [step] points (dp) from where the hold began is one
     * level, rounded, kept within the scale.
     */
    fun scrubIndex(from: Int, translation: Double, step: Double = SCRUB_STEP): Int {
        val moved = roundHalfAwayFromZero(translation / step).toInt()
        return (from + moved).coerceIn(0, levels.size - 1)
    }

    override fun equals(other: Any?): Boolean = other is ModelLevelScale && other.levels == levels
    override fun hashCode(): Int = levels.hashCode()
    override fun toString(): String = "ModelLevelScale($levels)"

    companion object {
        fun label(level: String?): String {
            if (level == null || level == "default") return localized(R.string.composer_model_level_default)
            return ModelVariant.label(level)
        }

        /** `ModelLevelScale.scrubStep` (pt → dp). */
        const val SCRUB_STEP: Double = 30.0
    }
}

internal fun roundHalfAwayFromZero(value: Double): Double = sign(value) * floor(abs(value) + 0.5)
