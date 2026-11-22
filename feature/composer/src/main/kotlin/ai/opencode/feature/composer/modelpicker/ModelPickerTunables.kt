package ai.opencode.feature.composer.modelpicker

// Lengths in dp.

/** The model list's rests, which [ModelListDetent] and [ModelPickerDetentsSheet] read. */
object ModelPickerTunables {
    const val mediumFraction = 0.5
    const val mediumMinimum = 300.0
    const val rubberMax = 48.0
    const val rubberSoftness = 160.0
    const val closeBelow = 0.55
    /** `modelPicker.sheet.flickCloses`: a flick down from full closes the sheet at this speed (dp/s). */
    const val flickCloses = 800.0
}
