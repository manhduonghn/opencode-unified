package ai.opencode.core.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable

/**
 * The [MotionScheme] Material components move with: M3 Expressive's own springs, or a 0.2 s
 * cross-fade under Reduce Motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Immutable
class OpenCodeMotionScheme(private val reduceMotion: Boolean = false) : MotionScheme {
    private val expressive = MotionScheme.expressive()

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.fastSpatialSpec()

    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.defaultSpatialSpec()

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.slowSpatialSpec()

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.fastEffectsSpec()

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.defaultEffectsSpec()

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        if (reduceMotion) Motion.reducedFadeSpec() else expressive.slowEffectsSpec()

    override fun equals(other: Any?): Boolean = other is OpenCodeMotionScheme && other.reduceMotion == reduceMotion
    override fun hashCode(): Int = reduceMotion.hashCode()
}
