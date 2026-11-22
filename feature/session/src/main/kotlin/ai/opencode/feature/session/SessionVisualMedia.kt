package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.AgentImageKey
import ai.opencode.feature.media.AgentImageSighting
import ai.opencode.feature.media.ImageLinkPolicy
import ai.opencode.feature.media.MermaidCacheKey
import ai.opencode.feature.media.MermaidSyntax
import ai.opencode.feature.media.MermaidTheme
import ai.opencode.feature.media.SessionVisualImageSource
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptImagePreview.Page
import ai.opencode.feature.transcript.links.linkTextBlocks
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem

/** Ordered, I/O-free snapshot of the current transcript window, shared by every visual entry point. */
object SessionVisualMedia {
    fun preview(
        opening: TranscriptImagePreview, items: List<OpenCodeTranscriptItem>, server: OpenCodeServer, directory: String?,
        theme: MermaidTheme = MermaidTheme.Light,
        shownFile: (AgentFileReference) -> AgentFileReference? = { it },
    ): TranscriptImagePreview {
        val gallery = collect(items, server, directory, theme, shownFile)
        for (page in opening.pages) gallery.add(page, prefer = page.id == opening.id)
        return TranscriptImagePreview(opening.id, gallery.pages.toList(), server)
    }

    fun preview(
        file: AgentFileDestination, items: List<OpenCodeTranscriptItem>, server: OpenCodeServer, directory: String?,
        theme: MermaidTheme = MermaidTheme.Light,
        shownFile: (AgentFileReference) -> AgentFileReference? = { it },
    ): TranscriptImagePreview? {
        var tapped = filePage(file) ?: return null
        val gallery = collect(items, server, directory, theme, shownFile)
        if (file.directory != directory) {
            // A link in a document has no transcript anchor of its own. Resolve it there,
            // without changing the base of any sibling already collected from the session.
            val absolute = gallery.path(tapped)
            if (absolute != null) {
                gallery.byPath[absolute]?.let { return TranscriptImagePreview(gallery.pages[it].id, gallery.pages.toList(), server) }
                tapped = tapped.copy(id = "file:$absolute")
            }
        }
        gallery.add(tapped, prefer = true)
        return TranscriptImagePreview(tapped.id, gallery.pages.toList(), server)
    }

    private class Gallery {
        val pages = mutableListOf<Page>()
        val byPath = mutableMapOf<String, Int>()
        private val byID = mutableMapOf<String, Int>()
        fun path(page: Page) = page.file?.let { it.reference.target(it.directory)?.absolutePath }

        fun add(page: Page, prefer: Boolean = false) {
            val path = path(page)
            val index = path?.let(byPath::get) ?: byID[page.id] ?: pages.size
            byID[page.id] = index // Remember aliases even when the first occurrence keeps its slot.
            if (path != null) byPath[path] = index
            if (index == pages.size) {
                pages += page
                return
            }
            val old = pages[index]
            if (prefer || (page.id == old.id && old.image == null && page.image != null)) {
                pages[index] = page.copy(
                    // Confirmed pixels from the row must not be reopened as a file.
                    file = page.file ?: old.file.takeIf { page.image == null },
                    imageSource = page.imageSource ?: old.imageSource,
                    original = page.original ?: old.original,
                    mediaHash = page.mediaHash ?: old.mediaHash,
                )
            }
        }
    }

    private fun filePage(file: AgentFileDestination, id: String = file.reference.zoomSourceID): Page? {
        val ref = file.reference
        if (ref.kind != AgentFileReference.Kind.Image && ref.kind != AgentFileReference.Kind.Video) return null
        return Page(id, null, ref.name, name = ref.name, file = file, isVideo = ref.kind == AgentFileReference.Kind.Video)
    }

    private fun collect(
        items: List<OpenCodeTranscriptItem>, server: OpenCodeServer, directory: String?, theme: MermaidTheme,
        shownFile: (AgentFileReference) -> AgentFileReference?,
    ): Gallery {
        val gallery = Gallery()
        fun attachments(images: List<OpenCodeImageAttachment>, label: String) {
            for ((index, image) in images.withIndex()) {
                if (image.mediaHash == null && image.data.isEmpty() && !image.isUnavailable) continue
                gallery.add(Page(image.id, null, image.name ?: localized(R.string.session_visual_image_page_label, label, index + 1, images.size), mediaHash = image.mediaHash,
                    name = image.name, isUnavailable = image.isUnavailable, original = image.data.takeIf { it.isNotEmpty() }, labelIsPlaceholder = image.name == null))
            }
        }
        fun appendFile(ref: AgentFileReference, occurrence: String? = null) {
            val path = if (occurrence != null && ref.kind == AgentFileReference.Kind.Image) ref.target(directory)?.absolutePath else null
            val id = if (path != null && occurrence != null) AgentImageSighting(AgentImageKey(server.persistenceID, path), occurrence).mediaID else ref.zoomSourceID
            filePage(AgentFileDestination(ref, directory), id)?.let(gallery::add)
        }
        fun transcriptFile(file: OpenCodeTranscriptFile) {
            val ref = file.path?.let { AgentFileReference(it) } ?: file.mediaViewerReference
            if (ref != null) appendFile(ref)
            else if (file.isUnavailable && file.mime.startsWith("video/", ignoreCase = true)) {
                gallery.add(Page(file.id, null, file.name, name = file.name, isVideo = true, isUnavailable = true))
            }
        }
        for (item in items) when (item) {
            is OpenCodeTranscriptItem.UserImages -> attachments(item.images, localized(R.string.session_visual_attached_image))
            is OpenCodeTranscriptItem.UserFiles -> item.files.forEach(::transcriptFile)
            is OpenCodeTranscriptItem.Tool -> {
                item.call.fileReference?.let { appendFile(it) }
                attachments(item.call.imageAttachments, localized(R.string.session_visual_tool_image))
                item.call.videos.orEmpty().forEach(::transcriptFile)
            }
            is OpenCodeTranscriptItem.AssistantMarkdown -> {
                val blocks = OpenCodeMarkdownBlock.blocks(item.text)
                val links = ImageLinkPolicy.plan(blocks.linkTextBlocks(), item.inProgress)
                for (block in blocks) {
                    when (val kind = block.kind) {
                        is Kind.Files -> for (file in kind.files) {
                            shownFile(file)?.let { appendFile(it, item.id) }
                        }
                        is Kind.Image -> {
                            val key = OpenCodeClient.markdownImageKey(server, directory, kind.source)
                            val local = AgentFileReference.fromLink(kind.source)?.let { filePage(AgentFileDestination(it, directory), key) }
                            gallery.add(local ?: Page(key, null, kind.alt.ifEmpty { localized(R.string.session_visual_image) }, labelIsPlaceholder = kind.alt.isEmpty(),
                                imageSource = SessionVisualImageSource.Markdown(kind.source, directory)))
                        }
                        is Kind.Mermaid -> gallery.add(Page("mermaid|${MermaidCacheKey(kind.source, theme).string}", null,
                            localized(R.string.session_visual_diagram_label, MermaidSyntax.diagramType(kind.source) ?: "Mermaid"), imageSource = SessionVisualImageSource.Diagram(kind.source, theme)))
                        else -> Unit
                    }
                    for (url in links[block.id].orEmpty()) gallery.add(Page("image-link:${item.id}|${block.id}|$url", null,
                        url.substringBefore('?').substringBefore('#').substringAfterLast('/'), imageSource = SessionVisualImageSource.Link(url)))
                }
            }
            else -> Unit
        }
        return gallery
    }
}
