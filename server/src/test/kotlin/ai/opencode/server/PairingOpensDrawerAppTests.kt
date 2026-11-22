package ai.opencode.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingOpensDrawerAppTests {
    @Test
    fun aComputerSharingAnAddressWithASavedOneIsNotNew() {
        val saved = OpenCodeServer(url = "http://100.64.0.3:4096", username = "opencode", password = "a")
        val paired = OpenCodeServer(
            url = "http://192.168.1.10:4096", username = "opencode", password = "b", alternateURLs = listOf("http://100.64.0.3:4096"),
        )
        assertFalse(paired.isNewComputer(listOf(saved)))
        assertTrue(paired.isNewComputer(emptyList()))
        assertTrue(paired.isNewComputer(listOf(OpenCodeServer(url = "http://10.0.0.9:4096", username = "opencode", password = "c"))))
    }
}
