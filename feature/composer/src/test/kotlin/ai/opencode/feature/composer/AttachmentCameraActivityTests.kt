package ai.opencode.feature.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCameraActivityTests {
    @Test fun hiddenAndBackgroundCamerasNeverActivate() {
        for (authorized in listOf(false, true)) {
            for (requests in listOf(false, true)) {
                assertFalse(AttachmentCameraActivity(visible = false, sceneActive = true, authorized = authorized, requestsAccess = requests).shouldActivate)
                assertFalse(AttachmentCameraActivity(visible = true, sceneActive = false, authorized = authorized, requestsAccess = requests).shouldActivate)
            }
        }
    }

    @Test fun tileOnlyStartsWithExistingPermission() {
        assertFalse(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = false, requestsAccess = false).shouldActivate)
        assertTrue(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = true, requestsAccess = false).shouldActivate)
    }

    @Test fun expandedCameraMayRequestPermission() {
        assertTrue(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = false, requestsAccess = true).shouldActivate)
    }
}
