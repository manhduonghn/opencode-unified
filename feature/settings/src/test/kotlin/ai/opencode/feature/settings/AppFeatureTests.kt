package ai.opencode.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android reads the package's version name and code, so there is no Info.plist key to prefer; the
 * label's format is what's shared.
 */
class AppFeatureTests {
    @Test
    fun appVersionPrefersOpenCodeVersion() {
        val version = AppVersion(version = "0.2.0", build = "37", commit = "abc1234")
        assertEquals("0.2.0", version.version)
        assertEquals("37", version.build)
        assertEquals("abc1234", version.commit)
        assertEquals("Version 0.2.0 (37) · abc1234", version.label)
    }
}
