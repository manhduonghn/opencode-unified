package ai.opencode.feature.system.notifications

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** In-App Notices' mode reaches Android as `inAppNoticesOn` (the drawer's reader: mode ≠ Off). */
class InAppNoticeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun bannersForOtherSessionsGoOnlyToNotificationCenterWhileNoticesAreOn() {
        fun presentation(sessionID: String?, visibleSessionID: String?, noticesOn: Boolean): NotificationPresentation {
            val notifications = SessionNotifications(PushTokenProvider.inert)
            notifications.visibleSessionID = visibleSessionID
            notifications.inAppNoticesOn = { noticesOn }
            return notifications.presentation(sessionID)
        }
        assertEquals(NotificationPresentation.None, presentation("ses_a", "ses_a", noticesOn = true))
        assertEquals(NotificationPresentation.Banner, presentation("ses_b", "ses_a", noticesOn = false))
        assertEquals(NotificationPresentation.ListOnly, presentation("ses_b", "ses_a", noticesOn = true))
        assertEquals(NotificationPresentation.ListOnly, presentation("ses_b", null, noticesOn = true))
        assertEquals(NotificationPresentation.Banner, presentation(null, "ses_a", noticesOn = true))
    }

    @Test fun needsInputStatusesAreTheOnlyOnesClearedOnAnswer() {
        fun content(status: String) = SessionPushNotifications.Delivered(tag = "ses_a", sessionID = "ses_a", status = status)
        assertTrue(SessionPushNotifications.isNeedsInput(content("permission")))
        assertFalse(SessionPushNotifications.isNeedsInput(content("succeeded")))
        assertEquals("ses_a", SessionPushNotifications.sessionID(content("succeeded")))
    }
}
