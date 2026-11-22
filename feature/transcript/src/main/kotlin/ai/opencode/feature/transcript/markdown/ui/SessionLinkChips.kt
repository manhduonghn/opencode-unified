package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.label
import ai.opencode.feature.transcript.markdown.marked
import ai.opencode.server.model.SessionLink
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity

/** Developer Mode → Tuning → Transcript → Session Links. */
object SessionLinkTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order. */
    val sectionTitles = listOf("Session Link")

    /** The chip's corner radius at body size (17 pt); it scales with the text. */
    val radius = Tunable("transcript.sessionLink.radius", "Chip corner radius (pt at body size)", default = 4.5f, range = 0.0..10.0, step = 0.25)
    /** The chip's height and how far its centre sits above the baseline, in ems. */
    val height = Tunable("transcript.sessionLink.height", "Chip height (em)", default = 1.16f, range = 0.9..1.3, step = 0.01)
    val rise = Tunable("transcript.sessionLink.rise", "Chip centre above baseline (em)", default = 0.31f, range = 0.2..0.45, step = 0.005)
    val fill = Tunable("transcript.sessionLink.fill", "Chip fill opacity", default = 0.08, range = 0.02..0.25, step = 0.005)

    /** The radius options, at body size. */
    val options: TuningPresets by lazy {
        TuningPresets(
            "transcript.sessionLink", "Session Links",
            listOf(
                TuningPreset("3 pt", radius.preset(3f)),
                TuningPreset("4.5 pt", radius.preset(4.5f)),
                TuningPreset("6 pt", radius.preset(6f)),
            ),
        )
    }

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(options)
        store.register(radius, height, rise)
        store.register(fill)
    }
}

/**
 * Reports the session IDs a block shows, once per set: the transcript resolves them in one batch,
 * never per row, and hands the targets back through [LocalSessionLinks].
 */
@Composable
fun ReportsSessionLinks(ids: List<String>) {
    if (ids.isEmpty()) return
    val send by rememberUpdatedState(LocalTranscriptRowContext.current.send)
    LaunchedEffect(ids) { send(TranscriptAction.SessionLinksAppeared(ids)) }
}

/** Session mentions in a user message's plain text, as chips. */
object SessionMentions {
    /** The text as shown, titles in place of the tokens. */
    fun shown(text: String, context: SessionLinkContext): String {
        val found = SessionLink.mentions(text)
        if (found.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        for (mention in found) {
            out.append(text, cursor, mention.range.first)
            val target = context.targets[mention.id] ?: SessionLinkTarget.Resolving
            out.append(MarkdownPresentation.chipLeadingPad).append(target.label(mention.id).replace(" ", "\u00A0")).append(MarkdownPresentation.chipTrailingPad)
            cursor = mention.range.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }
}

/**
 * A sent message's body text with its `@session:<id>` mentions (as desktop sends them) drawn as
 * session chips showing their titles.
 */
@Composable
fun SessionMentionText(text: String, modifier: Modifier = Modifier) {
    val style = currentMarkdownStyle()
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val targets = LocalSessionLinks.current.targets
    val listener = rememberLinkListener()
    val presented = remember(text, targets, colors, density) {
        MarkdownPresentation.present(
            SessionLink.marked(text) ?: InlineText.plain(text),
            PresentationOptions(foreground = colors.text, textSize = OpenCodeTextStyles.body.fontSize, tonesBody = false),
            style, targets, density, listener, siteIcon = null,
        )
    }
    ArrivingText(presented, emptyList(), OpenCodeTextStyles.body, colors.text, modifier)
    ReportsSessionLinks(presented.sessionIDs)
}
