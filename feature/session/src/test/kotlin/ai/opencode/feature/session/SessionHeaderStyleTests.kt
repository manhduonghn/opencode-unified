package ai.opencode.feature.session

import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.session.header.SessionHeaderStyle.Appearance
import ai.opencode.feature.session.header.SessionHeaderStyle.Resolved
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * Android's defaults to the app-drawn band (no system scroll edge, see
 * `SessionHeaderStyle.Environment`).
 */
class SessionHeaderStyleTests {
    private fun env(
        osVersion: String = "27.0",
        forceAppBlur: Boolean = false,
        reduceTransparency: Boolean = false,
        increaseContrast: Boolean = false,
        isDark: Boolean = false,
        headerHeight: Float = SessionHeaderStyle.DEFAULT_HEADER_HEIGHT,
    ) = SessionHeaderStyle.Environment(
        osVersion = osVersion,
        forceAppBlur = forceAppBlur,
        reduceTransparency = reduceTransparency,
        increaseContrast = increaseContrast,
        isDark = isDark,
        headerHeight = headerHeight,
    )

    /** The header styles from `prototypes/header-blur`, kept as fixtures. */
    private enum class Preset {
        A, C, D, F, G;

        val style: SessionHeaderStyle get() = preset(this)
    }

    private companion object {
        fun preset(preset: Preset): SessionHeaderStyle = when (preset) {
            Preset.A -> SessionHeaderStyle(pocketExtension = 0f)
            Preset.C -> {
                val fade = Appearance(opacity = 1.0, fadeStart = -20f, fadeEnd = 38f)
                SessionHeaderStyle(pocketExtension = null, light = fade, dark = fade)
            }
            Preset.D -> SessionHeaderStyle(
                pocketExtension = 62f,
                light = Appearance(opacity = 0.94, fadeStart = -38f, fadeEnd = 56f, blur = SessionHeaderStyle.Blur.Regular),
                dark = Appearance(opacity = 0.9, fadeStart = -46f, fadeEnd = 56f, blur = SessionHeaderStyle.Blur.Regular),
            )
            Preset.F -> SessionHeaderStyle(
                pocketExtension = 42f,
                light = Appearance(opacity = 1.0, fadeStart = -2f, fadeEnd = 46f),
                dark = Appearance(opacity = 0.96, fadeStart = -21f, fadeEnd = 36f),
            )
            Preset.G -> SessionHeaderStyle(
                pocketExtension = 36f,
                light = Appearance(opacity = 0.35, fadeStart = -18f, fadeEnd = 36f, blur = SessionHeaderStyle.Blur.Thin),
                dark = Appearance(opacity = 0.92, fadeStart = -18f, fadeEnd = 36f, blur = SessionHeaderStyle.Blur.UltraThin),
            )
        }
    }

    @Test fun shippedStyleIsTheSoftEdgeOverTheHeader() {
        assertEquals(Preset.A.style, SessionHeaderStyle.shipped)
        val resolved = SessionHeaderStyle.shipped.resolved(env())
        assertEquals(Resolved.SystemEdge(pocket = 52f, isHard = false), resolved.systemEdge)
        assertNull(resolved.backdrop)
    }

    @Test fun presetValues() {
        val c = Preset.C.style
        assertNull(c.pocketExtension)
        assertEquals(c.light, c.dark)
        assertTrue(c.light.opacity == 1.0 && c.light.fadeStart == -20f && c.light.fadeEnd == 38f && c.light.blur == null)

        val d = Preset.D.style
        assertEquals(62f, d.pocketExtension)
        assertTrue(d.light.opacity == 0.94 && d.light.fadeStart == -38f && d.light.fadeEnd == 56f && d.light.blur == SessionHeaderStyle.Blur.Regular)
        assertTrue(d.dark.opacity == 0.9 && d.dark.fadeStart == -46f && d.dark.blur == SessionHeaderStyle.Blur.Regular)

        val f = Preset.F.style
        assertEquals(42f, f.pocketExtension)
        assertTrue(f.light.opacity == 1.0 && f.light.fadeStart == -2f && f.light.fadeEnd == 46f && f.light.blur == null)
        assertTrue(f.dark.opacity == 0.96 && f.dark.fadeStart == -21f && f.dark.fadeEnd == 36f)

        // Codex: a partial wash over a blur in light mode, near-solid in dark.
        val g = Preset.G.style
        assertEquals(36f, g.pocketExtension)
        assertTrue(g.light.opacity == 0.35 && g.light.blur == SessionHeaderStyle.Blur.Thin)
        assertTrue(g.dark.opacity == 0.92 && g.dark.blur == SessionHeaderStyle.Blur.UltraThin)
        assertTrue(g.light.fadeStart == -18f && g.light.fadeEnd == 36f && g.dark.fadeStart == -18f && g.dark.fadeEnd == 36f)

        for (preset in Preset.entries) {
            assertTrue(preset.style.light.fadeStart <= preset.style.light.fadeEnd)
            assertTrue(preset.style.dark.fadeStart <= preset.style.dark.fadeEnd)
            assertFalse(preset.style.hairline)
        }
    }

    @Test fun appBlurBeforeIOS27OrWhenForced() {
        assertTrue(SessionHeaderStyle.usesAppBlur(osVersion = "26.6.1", forced = false))
        assertTrue(SessionHeaderStyle.usesAppBlur(osVersion = "26.0", forced = false))
        assertFalse(SessionHeaderStyle.usesAppBlur(osVersion = "27.0", forced = false))
        assertFalse(SessionHeaderStyle.usesAppBlur(osVersion = "27.2.0", forced = false))
        assertFalse(SessionHeaderStyle.usesAppBlur(osVersion = "28.0", forced = false))
        assertTrue(SessionHeaderStyle.usesAppBlur(osVersion = "27.2.0", forced = true))
    }

    @Test fun theOSPicksOnlyWhereTheBlurComesFrom() {
        for (preset in Preset.entries) {
            for (isDark in listOf(false, true)) {
                val ios26 = preset.style.resolved(env(osVersion = "26.6.1", isDark = isDark))
                val ios27 = preset.style.resolved(env(osVersion = "27.2.0", isDark = isDark))
                val forced = preset.style.resolved(env(osVersion = "27.2.0", forceAppBlur = true, isDark = isDark))
                assertEquals("Force App Blur shows iOS 26's header", ios26, forced)
                assertTrue("The wash is the same everywhere", ios26.backdrop == ios27.backdrop && ios26.contentTop == ios27.contentTop)
                val system = ios27.blur
                val app = ios26.blur
                when {
                    system is Resolved.ResolvedBlur.System && app is Resolved.ResolvedBlur.App -> {
                        // The app's blur covers the same pocket, softly past its end.
                        assertEquals(Resolved.AppBlur(fadeStart = system.edge.pocket - 12, fadeEnd = system.edge.pocket + 16), app.blur)
                        assertNull("The system edge is off where the app blurs", ios26.systemEdge)
                    }
                    system == Resolved.ResolvedBlur.None && app == Resolved.ResolvedBlur.None ->
                        assertEquals("Only the fade-only preset has no blur", Preset.C, preset)
                    else -> fail("$preset: $system on 27, $app on 26")
                }
            }
        }
    }

    @Test fun appBlurIsRevealedOnScroll() {
        val top = SessionHeaderStyle.shipped.resolved(env(osVersion = "26.6.1")).transcriptTop
        assertNull(top.systemEdge)
        assertEquals(24f, top.revealDistance)
        assertEquals(60f, top.contentTop)
    }

    @Test fun reduceTransparencyHasNoBlurOnAnyOS() {
        for (os in listOf("26.6.1", "27.2.0")) {
            for (forced in listOf(false, true)) {
                val resolved = Preset.G.style.resolved(env(osVersion = os, forceAppBlur = forced, reduceTransparency = true))
                assertEquals(Resolved.ResolvedBlur.None, resolved.blur)
            }
        }
    }

    @Test fun distancesFollowTheMeasuredHeader() {
        val f = Preset.F.style
        val regular = f.resolved(env(headerHeight = 52f))
        assertEquals(94f, regular.systemEdge?.pocket)
        assertTrue(regular.backdrop?.fadeStart == 50f && regular.backdrop?.fadeEnd == 98f)
        // Accessibility text makes the header taller; everything moves with it.
        val large = f.resolved(env(headerHeight = 80f))
        assertEquals(122f, large.systemEdge?.pocket)
        assertTrue(large.backdrop?.fadeStart == 78f && large.backdrop?.fadeEnd == 126f)
        assertEquals(80f, SessionHeaderStyle.shipped.resolved(env(headerHeight = 80f)).systemEdge?.pocket)
    }

    @Test fun appearanceSelectsTheStops() {
        val f = Preset.F.style
        assertEquals(1.0, f.resolved(env(isDark = false)).backdrop?.opacity)
        assertEquals(0.96, f.resolved(env(isDark = true)).backdrop?.opacity)
    }

    @Test fun reduceTransparencyIsAnOpaqueFadeWithoutEdgeOrMaterial() {
        for (preset in Preset.entries) {
            for (isDark in listOf(false, true)) {
                val resolved = preset.style.resolved(env(reduceTransparency = true, isDark = isDark, headerHeight = 60f))
                assertNull(resolved.systemEdge)
                assertNull(resolved.backdrop?.blur)
                assertEquals(1.0, resolved.backdrop?.opacity)
                // Opaque through the header, ending where content rests, always shown.
                assertEquals(60f, resolved.backdrop?.fadeStart)
                assertEquals(68f, resolved.backdrop?.fadeEnd)
                assertEquals(0f, resolved.backdrop?.fadeIn)
            }
        }
    }

    @Test fun increaseContrastMakesTheWashOpaqueThroughTheHeader() {
        // Even the plain soft edge gets a wash.
        val a = SessionHeaderStyle.shipped.resolved(env(increaseContrast = true))
        assertEquals(Resolved.SystemEdge(pocket = 52f, isHard = false), a.systemEdge)
        assertTrue(a.backdrop?.opacity == 0.9 && a.backdrop?.fadeStart == 52f && a.backdrop?.fadeEnd == 76f)
        // G's partial wash becomes nearly opaque; F's longer fade is kept.
        val g = Preset.G.style.resolved(env(increaseContrast = true))
        assertTrue(g.backdrop?.opacity == 0.9 && g.backdrop?.fadeStart == 52f && g.backdrop?.fadeEnd == 88f)
        val f = Preset.F.style.resolved(env(increaseContrast = true))
        assertTrue(f.backdrop?.opacity == 1.0 && f.backdrop?.fadeStart == 52f && f.backdrop?.fadeEnd == 98f)
    }

    @Test fun contentRestsBelowTheMeasuredHeader() {
        assertEquals(60f, SessionHeaderStyle.MINIMUM_CONTENT_TOP)
        assertEquals(TranscriptMetrics.clustered.value, SessionHeaderStyle.RESTING_GAP)
        assertEquals(60f, SessionHeaderStyle.shipped.resolved(env(headerHeight = 52f)).contentTop)
        assertEquals(88f, SessionHeaderStyle.shipped.resolved(env(headerHeight = 80f)).contentTop)
        assertEquals(88f, Preset.C.style.resolved(env(reduceTransparency = true, headerHeight = 80f)).contentTop)
        val f = Preset.F.style.resolved(env(headerHeight = 80f)).transcriptTop
        assertEquals(
            Resolved.TranscriptTop(contentTop = 88f, systemEdge = Resolved.SystemEdge(pocket = 122f, isHard = false), revealDistance = 24f, header = 80f),
            f,
        )
        assertEquals(0f, SessionHeaderStyle.shipped.resolved(env()).transcriptTop.revealDistance)
    }

    @Test fun scrollUnderTheHeaderIsCappedWhereTheBackdropIsRevealed() {
        val bridge = SessionScrollEdgeBridge()
        bridge.scrolled(underHeader = 500f, revealDistance = 24f)
        assertEquals(24f, bridge.scrolledUnderHeader)
        bridge.scrolled(underHeader = -30f, revealDistance = 24f)
        assertEquals(0f, bridge.scrolledUnderHeader)
        bridge.scrolled(underHeader = 11.6f, revealDistance = 24f)
        assertEquals(12f, bridge.scrolledUnderHeader)
    }

    @Test fun onlyTheLabOverridesTheShippedStyle() {
        val environment = env(osVersion = "27.0")
        assertEquals(SessionHeaderStyle.shipped.resolved(environment), HeaderBlurTunables.resolved(environment))
    }

    @Test fun fadeInFollowsScrollDistance() {
        val appearance = Appearance(opacity = 1.0, fadeStart = 30f, fadeEnd = 90f, fadeIn = 24f)
        assertEquals(0.0, appearance.revealed(-40f), 0.0)
        assertEquals(0.0, appearance.revealed(0f), 0.0)
        assertEquals(0.5, appearance.revealed(12f), 0.0)
        assertEquals(1.0, appearance.revealed(24f), 0.0)
        assertEquals(1.0, appearance.revealed(500f), 0.0)
        assertEquals(1.0, Appearance(opacity = 1.0, fadeEnd = 90f, fadeIn = 0f).revealed(0f), 0.0)
    }

    @Test fun strengthHoldsThenEases() {
        val smooth = Appearance(opacity = 1.0, fadeStart = 30f, fadeEnd = 90f)
        assertEquals(1.0, smooth.strength(0f), 0.0)
        assertEquals(1.0, smooth.strength(30f), 0.0)
        assertEquals(0.5, smooth.strength(60f), 0.0)
        assertEquals(0.0, smooth.strength(90f), 0.0)
        assertTrue(smooth.strength(33f) > 0.99)
        val linear = smooth.copy(curve = SessionHeaderStyle.Curve.Linear)
        assertTrue(abs(linear.strength(45f) - 0.75) < 1e-9)
    }

    @Test fun stopsSpanTheScreenTopToTheFadeEnd() {
        val appearance = Appearance(opacity = 0.5, fadeStart = 30f, fadeEnd = 90f)
        val stops = appearance.stops(safeTop = 62f, color = Color.Black, opacity = appearance.opacity)
        assertEquals(0f, stops.first().first)
        assertEquals(1f, stops.last().first)
        assertEquals(stops.map { it.first }.sorted(), stops.map { it.first })
        assertTrue(abs(stops[1].first - 92.0 / 152.0) < 1e-6)
    }

    @Test fun headerBlurTunablesRegisterAndResolveLive() {
        assertEquals(18f, HeaderBlurTunables.titleInset.value)
        assertEquals(44f, HeaderBlurTunables.buttonSize.value)
        assertEquals(ai.opencode.feature.session.header.HeaderTextStyle.Subheadline, HeaderBlurTunables.titleFont)
        assertEquals(ai.opencode.feature.session.header.HeaderTextStyle.Footnote, HeaderBlurTunables.projectFont)

        val defaulted = HeaderBlurTunables.resolved(env(osVersion = "27.0"))
        assertEquals(SessionHeaderStyle.shipped.resolved(env(osVersion = "27.0")), defaulted)
        assertEquals(Resolved.SystemEdge(pocket = 52f, isHard = false), defaulted.systemEdge)
        assertNull(defaulted.appBlur)
        val defaultedAppBlur = HeaderBlurTunables.resolved(env(osVersion = "26.6.1"))
        assertEquals(SessionHeaderStyle.shipped.resolved(env(osVersion = "26.6.1")), defaultedAppBlur)
        assertEquals(Resolved.AppBlur(fadeStart = 40f, fadeEnd = 68f, radius = 8f), defaultedAppBlur.appBlur)
    }

    /**
     * The system edge reveals nothing with the scroll, but how far content
     * sits under the header is still tracked, so an app blur a tuning change
     * switches to is drawn at once.
     */
    @Test fun scrollUnderHeaderIsTrackedWhileTheSystemEdgeDraws() {
        val bridge = SessionScrollEdgeBridge()
        bridge.scrolled(underHeader = 300f, revealDistance = 0f)
        assertEquals(SessionScrollEdgeBridge.MINIMUM_TRACKED_REVEAL, bridge.scrolledUnderHeader)
        bridge.scrolled(underHeader = 300f, revealDistance = 40f)
        assertEquals(40f, bridge.scrolledUnderHeader)
        bridge.scrolled(underHeader = -10f, revealDistance = 0f)
        assertEquals(0f, bridge.scrolledUnderHeader)
    }
}
