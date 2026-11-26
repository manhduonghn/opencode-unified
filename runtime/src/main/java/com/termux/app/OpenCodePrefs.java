package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** Shared preference keys used by the OpenCode shell. */
final class OpenCodePrefs {

    static final String FILE = "opencode_cfg";
    static final String THEME_MODE = "theme_mode";
    static final String DYNAMIC_COLOR = "dynamic_color";
    static final String WAKE_LOCK = "wake_lock";
    /** Set when the person stops the daemon from the notification; consumed by the next handoff. */
    private static final String SERVICE_STOPPED_BY_USER = "service_stopped_by_user";

    private OpenCodePrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Records a user-initiated stop durably: the app process may be gone before it opens again, so
     * the in-memory flag in {@link OpenCodeService} is not enough.
     */
    static void markServiceStoppedByUser(Context context) {
        get(context).edit().putBoolean(SERVICE_STOPPED_BY_USER, true).commit();
    }

    /** Reads and clears the durable stop flag, so the runtime is restarted and re-paired only once. */
    static boolean consumeServiceStoppedByUser(Context context) {
        SharedPreferences prefs = get(context);
        boolean stopped = prefs.getBoolean(SERVICE_STOPPED_BY_USER, false);
        if (stopped) prefs.edit().remove(SERVICE_STOPPED_BY_USER).commit();
        return stopped;
    }

    /** The night mode chosen in Settings, defaulting to follow system. */
    static int getThemeMode(Context context) {
        return get(context).getInt(THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Whether the user has chosen an appearance in the app settings. */
    static boolean hasThemeMode(Context context) {
        return get(context).contains(THEME_MODE);
    }

    static boolean isDynamicColorEnabled(Context context) {
        return get(context).getBoolean(DYNAMIC_COLOR, true);
    }
}
