package com.termux.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

/**
 * Drives the whole "prepare the environment, then hand over to the chat client" flow inherited from
 * OpenCode for Termux.
 *
 * <p>The fork used to do this from {@code OpenCodeWebActivity}: extract the Termux bootstrap,
 * install the OpenCode runtime, start the daemon and create a one-time pair link for its WebView.
 * The unified app has no WebView, so this class exposes exactly that pipeline to the Compose shell,
 * which uses the pair result to connect the native client directly and skips its pairing UI.</p>
 */
public final class UnifiedSetup {

    private static final String LOG_TAG = "UnifiedSetup";

    private static final long SERVICE_START_TIMEOUT_MS = 60_000L;
    private static final long CLI_TIMEOUT_MS = 30_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Stable phase ids used by the shell to render the right progress copy. */
    public static final String PHASE_BOOTSTRAP = "bootstrap";
    public static final String PHASE_RUNTIME = "runtime";
    public static final String PHASE_SERVICE = "service";
    public static final String PHASE_PAIR = "pair";

    /** Progress snapshot handed to the shell on the main thread. */
    public static final class Progress {
        public final String phase;
        /** -1 means indeterminate. */
        public final int percent;
        public final String message;
        public final String detail;

        public Progress(String phase, int percent, String message, String detail) {
            this.phase = phase;
            this.percent = percent;
            this.message = message;
            this.detail = detail;
        }
    }

    /** Result of a successful setup, containing the one-time pair link of the daemon. */
    public static final class Result {
        /** {@code http://127.0.0.1:<port>/auth/connect/<code>} or the link printed by `opencode pair`. */
        public final String pairUrl;

        public Result(String pairUrl) {
            this.pairUrl = pairUrl;
        }

        /** The daemon base URL, without the one-time auth path. */
        public String baseUrl() {
            int index = pairUrl.indexOf("/auth/connect/");
            return index > 0 ? pairUrl.substring(0, index) : pairUrl;
        }

        /** The one-time code, or null when the CLI returned a link in an older format. */
        public String code() {
            int index = pairUrl.indexOf("/auth/connect/");
            return index > 0 ? pairUrl.substring(index + "/auth/connect/".length()).trim() : null;
        }
    }

    public interface Listener {
        void onProgress(Progress progress);

        void onReady(Result result);

        void onError(String message);
    }

    private static Listener sListener;
    private static OpenCodeBootstrap.InstallListener sInstallListener;
    private static boolean sRunning;

    private UnifiedSetup() {
    }

    /**
     * Starts (or resumes) the preparation pipeline. All listener callbacks are delivered on the
     * main thread, matching the fork's install listener contract.
     */
    public static synchronized void start(final Activity activity, final Listener listener) {
        if (activity == null || listener == null) return;
        if (sRunning) {
            // A screen recreated during configuration change reattaches to the pipeline in flight;
            // dropping its listener would leave the new screen stuck with no progress or result.
            sListener = listener;
            return;
        }
        sRunning = true;
        sListener = listener;

        sInstallListener = state -> {
            if (!sRunning) return;
            if (state.running) {
                publish(new Progress(PHASE_RUNTIME, state.getOverallPercent(), state.message, state.detail));
            } else if (state.failed) {
                fail(state.error == null || state.error.isEmpty()
                    ? "The OpenCode runtime could not be installed." : state.error);
            }
        };
        OpenCodeBootstrap.addInstallListener(sInstallListener);

        activity.runOnUiThread(() -> {
            publish(new Progress(PHASE_BOOTSTRAP, -1, "Preparing the Termux environment…", null));
            if (OpenCodeBootstrap.isTermuxBootstrapReady()) {
                installRuntime(activity);
            } else {
                TermuxInstaller.setupBootstrapIfNeeded(activity, () -> installRuntime(activity));
            }
        });
    }

    /** Whether the pipeline is currently running, so callers never start it twice. */
    public static synchronized boolean isRunning() {
        return sRunning;
    }

    /** The shell has redeemed the pair link; release the pipeline so a later launch can retry. */
    public static synchronized void complete() {
        clear();
    }

    /** Cancel before destroying the setup activity so no callback targets a dead screen. */
    public static synchronized void cancel() {
        clear();
    }

    private static synchronized void clear() {
        sRunning = false;
        if (sInstallListener != null) {
            OpenCodeBootstrap.removeInstallListener(sInstallListener);
            sInstallListener = null;
        }
        sListener = null;
    }

    private static void installRuntime(final Activity activity) {
        if (!sRunning) return;
        final Context app = activity.getApplicationContext();
        // Bootstrap had its own modal progress dialog; the Compose screen owns progress from here.
        UnifiedApp.dismissStartupProgress();
        publish(new Progress(PHASE_RUNTIME, -1, "Installing the OpenCode runtime…", null));

        OpenCodeBootstrap.restoreState();
        OpenCodeBootstrap.setupIfNeeded(app, () -> {
            if (!sRunning) return;
            if (!OpenCodeBootstrap.isRuntimeComplete()) {
                fail(readLastError());
                return;
            }
            startServiceAndPair(app);
        });
    }

    private static void startServiceAndPair(final Context app) {
        publish(new Progress(PHASE_SERVICE, -1, "Starting the local OpenCode service…", null));

        Intent service = new Intent(app, OpenCodeForegroundService.class)
            .setAction(OpenCodeForegroundService.ACTION_START);
        try {
            app.startForegroundService(service);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not start the foreground service", e);
        }

        publish(new Progress(PHASE_PAIR, -1, "Connecting the chat client…", null));
        new Thread(() -> {
            try {
                String pairUrl = OpenCodeService.startAndPair(app, SERVICE_START_TIMEOUT_MS, CLI_TIMEOUT_MS);
                final Result result = new Result(pairUrl);
                post(() -> {
                    // Stay "running" until the shell has redeemed and stored the token. Otherwise a
                    // resumed MainActivity can start a second pipeline while the handoff is in flight.
                    if (sRunning && sListener != null) sListener.onReady(result);
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Could not create the pair link", e);
                String message = e.getMessage() == null ? "Could not connect to OpenCode." : e.getMessage();
                fail(message);
            }
        }, "opencode-pair").start();
    }

    private static void publish(final Progress progress) {
        Listener listener = sListener;
        if (listener != null) listener.onProgress(progress);
    }

    private static void fail(final String message) {
        post(() -> {
            if (!sRunning) return;
            Listener listener = sListener;
            clear();
            if (listener != null) listener.onError(message);
        });
    }

    private static void post(Runnable runnable) {
        MAIN.post(runnable);
    }

    private static String readLastError() {
        String detail = OpenCodeBootstrap.getLastErrorDetail();
        if (detail != null && !detail.trim().isEmpty()) return detail;
        return "The OpenCode runtime is not ready yet. Check the connection and try again.";
    }
}
