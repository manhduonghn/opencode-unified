package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State

/**
 * The session's shells are read in its own directory, while the server answers: otherwise the
 * glyph keeps moving on what it last measured.
 */
val State.shellGlyphProbe: ShellGlyphProbe?
    get() {
        if (!requestsConnectionIsAvailable) return null
        val session = session ?: return null
        return ShellGlyphProbe(server = server, directory = session.directory)
    }
