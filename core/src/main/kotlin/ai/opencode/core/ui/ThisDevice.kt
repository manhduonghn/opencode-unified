package ai.opencode.core.ui

import ai.opencode.core.R
import ai.opencode.core.strings.localized
import android.content.res.Resources

/**
 * The device the app runs on, as its copy names it. iPhone/iPad map to phone/tablet; a tablet is a
 * smallest width of at least 600 dp, Android's tablet breakpoint.
 */
object ThisDevice {
    // `getSystem()` is null only off-device (JVM unit tests stub the framework); that reads as a phone.
    val isTablet: Boolean get() = (Resources.getSystem()?.configuration?.smallestScreenWidthDp ?: 0) >= 600

    /** “this phone”, “this tablet”. */
    val name: String get() = if (isTablet) localized(R.string.core_this_device_name_tablet) else localized(R.string.core_this_device_name_phone)

    /** “your phone”, “your tablet”. */
    val casualName: String get() = if (isTablet) localized(R.string.core_this_device_casual_name_tablet) else localized(R.string.core_this_device_casual_name_phone)
}
