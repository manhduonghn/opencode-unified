package ai.opencode.feature.composer

import ai.opencode.feature.composer.CameraDeviceDescription.Lens
import ai.opencode.feature.composer.CameraDeviceDescription.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AttachmentCameraLensTests {
    /** iPhone 15 Pro–style triple camera: ultra wide at 1.0, wide at 2.0, 3× telephoto at 6.0. */
    private val triple = CameraDeviceDescription(
        position = Position.Back,
        lenses = listOf(Lens.UltraWide, Lens.Wide, Lens.Telephoto),
        switchOverFactors = listOf(2.0, 6.0),
        minZoom = 1.0,
        maxZoom = 123.0,
        nativeCropFactors = listOf(4.0),
    )

    private fun displayed(model: CameraZoomModel): List<Double> = model.presets.map { it.displayFactor }

    @Test fun tripleCameraShowsUltraWideWideCropAndTelephoto() {
        val model = CameraZoomModel(triple)

        assertEquals(2.0, model.wideFactor, 0.0)
        assertEquals(listOf(0.5, 1.0, 2.0, 3.0), displayed(model))
        assertEquals(listOf("Ultra Wide", "Wide", "Wide 2×", "Telephoto 3×"), model.presets.map { it.accessibilityLabel })
        assertEquals(2.0, model.defaultFactor, 0.0)
    }

    @Test fun lensOrderDoesNotDependOnConstituentOrder() {
        val shuffled = triple.copy(lenses = listOf(Lens.Telephoto, Lens.UltraWide, Lens.Wide))
        assertEquals(CameraZoomModel(triple), CameraZoomModel(shuffled))
    }

    @Test fun fiveTimesTelephotoLabelsFromItsSwitchOver() {
        val device = triple.copy(switchOverFactors = listOf(2.0, 10.0), nativeCropFactors = emptyList())
        val model = CameraZoomModel(device)

        assertEquals(listOf(0.5, 1.0, 5.0), displayed(model))
        assertEquals("Telephoto 5×", model.presets.last().accessibilityLabel)
    }

    @Test fun dualWideCameraHasUltraWideAndWide() {
        val model = CameraZoomModel(
            CameraDeviceDescription(
                position = Position.Back, lenses = listOf(Lens.UltraWide, Lens.Wide), switchOverFactors = listOf(2.0), minZoom = 1.0, maxZoom = 123.0,
            ),
        )

        assertEquals(listOf(0.5, 1.0), displayed(model))
        assertEquals(1.0..20.0, model.range)
    }

    @Test fun dualCameraHasWideAndTwoTimesTelephoto() {
        val model = CameraZoomModel(
            CameraDeviceDescription(
                position = Position.Back, lenses = listOf(Lens.Wide, Lens.Telephoto), switchOverFactors = listOf(2.0), minZoom = 1.0, maxZoom = 16.0,
            ),
        )

        assertEquals(1.0, model.wideFactor, 0.0)
        assertEquals(listOf(1.0, 2.0), displayed(model))
        assertEquals(listOf("Wide", "Telephoto 2×"), model.presets.map { it.accessibilityLabel })
        assertEquals(1.0..10.0, model.range)
    }

    @Test fun singleWideCameraClampsToTheDevicesMaximum() {
        val model = CameraZoomModel(CameraDeviceDescription(position = Position.Back, lenses = listOf(Lens.Wide), minZoom = 1.0, maxZoom = 5.0))

        assertEquals(listOf(1.0), displayed(model))
        assertEquals(1.0..5.0, model.range)
        assertEquals(5.0, model.clamp(9.0), 0.0)
        assertEquals(1.0, model.clamp(0.4), 0.0)
    }

    @Test fun frontCameraZoomsToTwoTimesWithoutUltraWide() {
        val model = CameraZoomModel(CameraDeviceDescription(position = Position.Front, lenses = listOf(Lens.Wide), minZoom = 1.0, maxZoom = 16.0))

        assertEquals(listOf(1.0), displayed(model))
        assertEquals(1.0..2.0, model.range)
        assertTrue(model.canZoom)
    }

    @Test fun cropsDuplicatingALensOrPastTheNextLensAreDropped() {
        val device = triple.copy(nativeCropFactors = listOf(2.0, 6.1, 8.0, 0.5))
        assertEquals(listOf(0.5, 1.0, 3.0), displayed(CameraZoomModel(device)))
    }

    @Test fun mismatchedSwitchOverFactorsKeepOnlyKnownLenses() {
        val model = CameraZoomModel(
            CameraDeviceDescription(
                position = Position.Back, lenses = listOf(Lens.UltraWide, Lens.Wide, Lens.Telephoto), switchOverFactors = listOf(2.0), minZoom = 1.0, maxZoom = 100.0,
            ),
        )
        assertEquals(listOf(0.5, 1.0), displayed(model))
    }

    @Test fun pinchMultipliesTheStartingZoomAndClamps() {
        val model = CameraZoomModel(triple)

        assertEquals(3.2, model.pinched(from = 2.0, scale = 1.6), 0.0)
        assertEquals(1.6, model.displayed(model.pinched(from = 2.0, scale = 1.6)), 0.0)
        assertEquals(1.0, model.pinched(from = 2.0, scale = 0.1), 0.0)
        assertEquals(20.0, model.pinched(from = 2.0, scale = 50.0), 0.0)
        assertEquals(1.0, model.pinched(from = 2.0, scale = 0.0), 0.0)
    }

    @Test fun activePresetIsTheNarrowestLensAtOrBelowTheZoom() {
        val model = CameraZoomModel(triple)

        assertEquals(0.5, model.activePreset(1.0)?.displayFactor)
        assertEquals(0.5, model.activePreset(1.9)?.displayFactor)
        assertEquals(1.0, model.activePreset(2.0)?.displayFactor)
        assertEquals(1.0, model.activePreset(3.2)?.displayFactor)
        assertEquals(2.0, model.activePreset(4.0)?.displayFactor)
        assertEquals(2.0, model.activePreset(5.9)?.displayFactor)
        assertEquals(3.0, model.activePreset(6.0)?.displayFactor)
        assertEquals(3.0, model.activePreset(20.0)?.displayFactor)
    }

    @Test fun labelsShowTheLiveValueOnTheActiveLens() {
        assertEquals("1×", CameraZoomModel.label(1.0, active = true))
        assertEquals("1.6×", CameraZoomModel.label(1.6, active = true))
        assertEquals("1×", CameraZoomModel.label(1.04, active = true))
        assertEquals("0.5×", CameraZoomModel.label(0.5, active = true))
        assertEquals(".5", CameraZoomModel.label(0.5, active = false))
        assertEquals("3", CameraZoomModel.label(3.0, active = false))
        assertEquals("10×", CameraZoomModel.label(9.97, active = true))
        assertEquals("12×", CameraZoomModel.label(12.4, active = true))
    }

    @Test fun voiceOverStepsThroughLensesAndWholeZooms() {
        val model = CameraZoomModel(triple)
        var factor = model.range.start
        val stops = mutableListOf(model.displayed(factor))
        while (true) {
            val next = model.step(from = factor, up = true)
            if (next == factor) break
            factor = next
            stops.add(model.displayed(factor))
        }

        assertEquals(listOf(0.5, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), stops)
        assertEquals(1.0, model.displayed(model.step(from = 3.2, up = false)), 0.0)
        assertEquals(model.range.start, model.step(from = model.range.start, up = false), 0.0)
    }

    private fun preset(display: Double, model: CameraZoomModel): CameraZoomModel.Preset =
        requireNotNull(model.presets.firstOrNull { it.displayFactor == display })

    @Test fun liftingAPinchKeepsItsLastZoomDespiteLateReports() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(2.0)
        val earlier = UUID.randomUUID()
        state.select(preset(3.0, model), model, animated = true, id = earlier)
        state.rampProgressed(earlier, 3.0)

        state.beginPinch()
        state.pinch(scale = 1.2, model = model)
        val last = state.pinch(scale = 1.5, model = model)
        state.endPinch()
        // Reports from the interrupted ramp drain after the lift.
        state.rampProgressed(earlier, 4.0)
        state.rampProgressed(earlier, 12.0)

        assertEquals(4.5, last)
        assertEquals(4.5, state.factor, 0.0)
        assertEquals(4.5, state.shownFactor, 0.0)
        assertEquals(2.0, model.activePreset(state.shownFactor)?.displayFactor)
    }

    @Test fun aTappedLensShowsAsChosenThroughoutItsRamp() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(1.0)
        val id = UUID.randomUUID()
        val (target, ramp) = state.select(preset(3.0, model), model, animated = true, id = id)

        assertEquals(6.0, target, 0.0)
        assertEquals(id, ramp)
        for (value in listOf(1.4, 2.2, 3.5, 4.8)) {
            state.rampProgressed(id, value)
            assertEquals(3.0, model.activePreset(state.shownFactor)?.displayFactor)
            assertEquals("3×", CameraZoomModel.label(model.displayed(state.shownFactor), active = true))
        }
        state.rampProgressed(id, 6.0)
        assertNull(state.ramp)
        assertEquals(6.0, state.factor, 0.0)
    }

    @Test fun pinchingMidRampTakesOverFromWhereTheRampIs() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(1.0)
        val id = UUID.randomUUID()
        state.select(preset(3.0, model), model, animated = true, id = id)
        state.rampProgressed(id, 3.0)

        state.beginPinch()
        assertNull(state.ramp)
        assertEquals(3.0, state.shownFactor, 0.0)
        val pinched = state.pinch(scale = 1.5, model = model)
        assertEquals(4.5, pinched)
        state.rampProgressed(id, 5.0)
        assertEquals(4.5, state.factor, 0.0)
    }

    @Test fun reduceMotionAndRepeatTapsSetTheZoomWithoutARamp() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(2.0)

        val direct = state.select(preset(0.5, model), model, animated = false)
        assertEquals(1.0, direct.target, 0.0)
        assertNull(direct.ramp)
        assertEquals(1.0, state.factor, 0.0)
        val ultraWide = preset(0.5, model)
        val repeated = state.select(ultraWide, model, animated = true)
        assertNull(repeated.ramp)
        assertNull(state.ramp)
    }

    @Test fun voiceOverStepsFromATappedLensAndCancelsItsRamp() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(1.0)
        val id = UUID.randomUUID()
        state.select(preset(1.0, model), model, animated = true, id = id)

        val stepped = state.step(up = true, model = model)
        assertEquals(4.0, stepped, 0.0)
        assertNull(state.ramp)
        state.rampProgressed(id, 1.5)
        assertEquals(4.0, state.factor, 0.0)
    }

    @Test fun resettingEndsAPinchAndARamp() {
        val model = CameraZoomModel(triple)
        val state = CameraZoomState()
        state.reset(2.0)
        state.beginPinch()
        state.reset(1.0)

        assertFalse(state.isPinching)
        val ignored = state.pinch(scale = 2.0, model = model)
        assertNull(ignored)
        val id = UUID.randomUUID()
        state.select(preset(3.0, model), model, animated = true, id = id)
        state.reset(1.0)
        assertEquals(1.0, state.shownFactor, 0.0)
    }

    @Test fun flashCyclesOffAutoOn() {
        assertEquals(AttachmentCameraFlash.Auto, AttachmentCameraFlash.Off.next)
        assertEquals(AttachmentCameraFlash.On, AttachmentCameraFlash.Auto.next)
        assertEquals(AttachmentCameraFlash.Off, AttachmentCameraFlash.On.next)
    }
}
