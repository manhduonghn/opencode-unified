package com.termux.app;

import android.content.Context;
import android.os.SystemClock;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;

/** Small helper for running commands in the Termux environment from the settings screen. */
final class TermuxShell {

    interface StreamListener {
        void onLine(String line);
    }

    private TermuxShell() {
    }

    static String run(Context context, String command, long timeoutMs) {
        return runStreaming(context, command, timeoutMs, null);
    }

    static String runStreaming(Context context, String command, long timeoutMs,
                               StreamListener listener) {
        try {
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
            applyEnvironment(context, builder);
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);

            final Process process = builder.start();
            final StringBuilder output = new StringBuilder();

            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                        if (listener != null) {
                            try {
                                listener.onLine(line);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "opencode-shell-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = SystemClock.uptimeMillis() + timeoutMs;
            while (true) {
                try {
                    process.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    if (SystemClock.uptimeMillis() > deadline) {
                        process.destroy();
                        try {
                            process.waitFor();
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        process.destroy();
                        break;
                    }
                }
            }

            reader.join(1000L);
            synchronized (output) {
                return output.toString();
            }
        } catch (Exception ex) {
            return ex.toString();
        }
    }

    private static void applyEnvironment(Context context, ProcessBuilder builder) {
        Map<String, String> processEnv = builder.environment();
        processEnv.clear();
        try {
            processEnv.putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        } catch (Exception ignored) {
            processEnv.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            processEnv.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            String existingPath = processEnv.get("PATH");
            String path = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
                + (existingPath == null || existingPath.isEmpty() ? "" : ":" + existingPath);
            processEnv.put("PATH", path);
            processEnv.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        }
        processEnv.put("TERM", "xterm-256color");
        processEnv.put("LANG", "en_US.UTF-8");
        processEnv.put("COLORTERM", "truecolor");
        processEnv.remove("LD_PRELOAD");
        processEnv.remove("LD_LIBRARY_PATH");
    }
}
