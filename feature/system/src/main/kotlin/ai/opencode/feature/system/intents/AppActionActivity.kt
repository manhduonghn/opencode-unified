package ai.opencode.feature.system.intents

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process

/**
 * The App Actions door (`actions.intent.OPEN_APP_FEATURE` in `shortcuts.xml`): the only
 * system-action entry other apps can start, since Assistant launches App Actions as itself. It
 * reads nothing but the spoken feature and hands it to [SystemActionActivity], which is private to
 * the app (shortcuts, tiles, widgets and notifications reach it through the app's own identity).
 * Any app could send this intent, so a call (in the background or with the app open) starts only
 * for a verified assistant ([isVerifiedAssistant]); from anything else the app opens on the session
 * and the user starts the call.
 */
class AppActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val feature = intent.getStringExtra(SystemActionActivity.EXTRA_APP_FEATURE)
        if (feature != null) {
            startActivity(
                Intent(this, SystemActionActivity::class.java)
                    .setAction(SystemActionActivity.ACTION_SYSTEM)
                    .putExtra(SystemActionActivity.EXTRA_APP_FEATURE, feature)
                    .putExtra(SystemActionActivity.EXTRA_FROM_ASSISTANT, isVerifiedAssistant(this)),
            )
        }
        finish()
    }

    companion object {
        /**
         * Google's assistant, which runs App Actions, by the SHA-256 of its signing certificate
         * (Google's release key, read from the Google app on a Play emulator image). Gemini's
         * app (`com.google.android.apps.bard`) is a stub that hands off to the Google app and
         * ships under more than one key, so it isn't pinned.
         */
        private val assistants = mapOf(
            "com.google.android.googlequicksearchbox" to
                "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83".hexToByteArray(),
        )

        /**
         * Whether a verified assistant started [activity]: the system's record of the launching
         * app (`launchedFromPackage`/`launchedFromUid`, which a caller can't forge, unlike the
         * referrer) names Google's assistant, that UID owns the package, and it's signed with
         * Google's key. The system shares the launching app only when it opts in
         * (`ActivityOptions.setShareIdentityEnabled`); otherwise this is false, and the launch
         * gets the path the user confirms. On Android 13 and 14 always require that confirmation.
         */
        fun isVerifiedAssistant(activity: Activity): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                isVerifiedAssistant(activity.launchedFromPackage, activity.launchedFromUid, activity.packageManager)

        internal fun isVerifiedAssistant(launchedFromPackage: String?, launchedFromUid: Int, packages: PackageManager): Boolean {
            val certificate = assistants[launchedFromPackage ?: return false] ?: return false
            if (launchedFromUid == Process.INVALID_UID) return false
            if (packages.getPackagesForUid(launchedFromUid)?.contains(launchedFromPackage) != true) return false
            return packages.hasSigningCertificate(launchedFromUid, certificate, PackageManager.CERT_INPUT_SHA256)
        }
    }
}
