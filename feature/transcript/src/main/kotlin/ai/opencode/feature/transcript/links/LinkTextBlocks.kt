package ai.opencode.feature.transcript.links

import ai.opencode.feature.media.LinkTextBlock
import ai.opencode.feature.media.LinkTextRun
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock

// The block type is this module's, above media, so the transcript maps each block into media's
// link-bearing text first.

/** The block's link-bearing text, as media's link readers take it. */
fun OpenCodeMarkdownBlock.linkTextBlock(): LinkTextBlock = when (val kind = kind) {
    is OpenCodeMarkdownBlock.Kind.Paragraph -> LinkTextBlock(id, prose = kind.text.linkTextRuns())
    is OpenCodeMarkdownBlock.Kind.NumberedItem -> LinkTextBlock(id, prose = kind.text.linkTextRuns())
    is OpenCodeMarkdownBlock.Kind.ListItem -> LinkTextBlock(id, prose = kind.text.linkTextRuns())
    is OpenCodeMarkdownBlock.Kind.Blockquote -> LinkTextBlock(id, prose = kind.text.linkTextRuns())
    is OpenCodeMarkdownBlock.Kind.Heading -> LinkTextBlock(id, prose = kind.text.linkTextRuns())
    is OpenCodeMarkdownBlock.Kind.Table -> LinkTextBlock(id, cells = (kind.header + kind.rows.flatten()).map { it.linkTextRuns() })
    else -> LinkTextBlock(id)
}

fun List<OpenCodeMarkdownBlock>.linkTextBlocks(): List<LinkTextBlock> = map { it.linkTextBlock() }

private fun InlineText.linkTextRuns(): List<LinkTextRun> = runs.map { LinkTextRun(it.text, it.link, it.code) }
