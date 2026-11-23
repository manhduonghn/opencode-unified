package com.termux.app;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * Public, argument-array-only façade for running commands in the Termux environment.
 *
 * <p>Callers must not pass user text as a `sh -c` string. This class exists so Compose features
 * (GitHub clone/login) can invoke `gh` and `git` without reimplementing the environment patching
 * and without giving a second component control of the local daemon.</p>
 */
public final class RuntimeShell {

    public interface LineListener {
        void onLine(String line);
    }

    private RuntimeShell() {
    }

    public static String run(Context context, String[] args, long timeoutMs) {
        return TermuxShell.runArgs(context, args, timeoutMs, null);
    }

    public static String run(Context context, String[] args, long timeoutMs, LineListener listener) {
        return TermuxShell.runArgs(context, args, timeoutMs,
            listener == null ? null : listener::onLine);
    }

    /**
     * Runs a command from an explicit directory. Used by `git clone` so the repository is created
     * inside the folder the file browser had open. The directory must be a real directory; an
     * invalid one falls back to the Termux home in [TermuxShell].
     */
    public static String runInDirectory(Context context, File workingDirectory, String[] args,
                                        long timeoutMs, LineListener listener) {
        return TermuxShell.runArgsInDirectory(context, workingDirectory, args, timeoutMs,
            listener == null ? null : listener::onLine);
    }

    /** Feeds a token to stdin without persisting it in app preferences. */
    public static String runWithInput(Context context, String input, String[] args, long timeoutMs,
                                      LineListener listener) {
        return TermuxShell.runWithInput(context, input, args, timeoutMs,
            listener == null ? null : listener::onLine);
    }

    /** Whether an executable is present in the bootstrap prefix. */
    public static boolean hasExecutable(String name) {
        if (name == null || name.isEmpty() || name.contains("/")) return false;
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, name).canExecute();
    }
}
