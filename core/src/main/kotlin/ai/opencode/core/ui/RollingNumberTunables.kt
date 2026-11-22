package ai.opencode.core.ui

import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningStore

/** The rolling number's look as live tunables (group `numbers`). */
object RollingNumberTunables {
    val blurAmount = Tunable("numbers.blur", "Vertical smear", default = 1.0, range = 0.0..2.0, step = 0.05)
    val edgeFade = Tunable("numbers.edgeFade", "Reel edge fade", default = 0.18f, range = 0.0..0.35, step = 0.01)

    /** The roll's spring (`Motion.layout`), for hosts that move a number's width or neighbours with its digits. */
    val duration: Tunable<Double> get() = MotionTunables.layoutSpring.duration
    val bounce: Tunable<Double> get() = MotionTunables.layoutSpring.bounce
    val stagger: Tunable<Double> get() = MotionTunables.staggerStep

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(blurAmount)
        store.register(edgeFade)
    }

    /** The live style. */
    fun style(store: TuningStore = TuningStore.shared): RollingNumberStyle {
        val blur = store.value(blurAmount)
        return RollingNumberStyle(
            duration = store.value(duration),
            bounce = store.value(bounce),
            motionBlur = blur > 0.01,
            blurAmount = blur,
            edgeFade = store.value(edgeFade),
            stagger = store.value(stagger),
        )
    }
}
