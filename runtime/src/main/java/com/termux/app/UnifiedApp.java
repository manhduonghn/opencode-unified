package com.termux.app;

import android.content.Context;
import android.content.Intent;

/**
 * Identity of the unified application.
 *
 * <p>The runtime module is Java (copied from the Termux-based app) and must not depend on the
 * Compose shell module. Keeping the application id and launcher activity here breaks that cycle:
 * the shell overrides these values with resources if it is renamed later.</p>
 */
public final class UnifiedApp {

    /** Must match TermuxConstants: bootstrap binaries hardcode this package path. */
    public static final String APPLICATION_ID = "com.termux";

    /**
     * Fully qualified launcher activity of the unified app shell. This is the long-lived window;
     * the setup screen is a transient step started by it and must never own the app task.
     */
    public static final String LAUNCHER_ACTIVITY = "ai.opencode.app.MainActivity";

    /** Version reported in diagnostics; the shell supplies the real version at runtime. */
    public static final String VERSION_NAME = "0.1.0";

    private UnifiedApp() {
    }

    /**
     * The fork's branded startup dialog is only needed while the shell has no UI of its own. The
     * unified app shows its own preparation screen, so the runtime wrapper closes the fork dialog
     * once bootstrap extraction is over and the Compose flow takes over.
     */
    public static void dismissStartupProgress() {
        TermuxInstaller.dismissStartupProgress();
    }

    /** An explicit intent that opens the shell regardless of which activity is exported. */
    public static Intent openAppIntent(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return launch;
        }
        Intent explicit = new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(context.getPackageName(), LAUNCHER_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return explicit;
    }
}
