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

    private OpenCodePrefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
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
