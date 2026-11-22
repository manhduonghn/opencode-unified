package com.termux.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes uncaught-exception reports into Downloads so a build that crashes on launch can still be
 * diagnosed from outside the app.
 *
 * <p>Installed before {@code TermuxCrashUtils} so the Termux handler chains to this one: the fork's
 * {@code ~/crash_log.md} keeps working while this handler leaves a copy in the runtime home's
 * Downloads (readable from the app's workspace browser) and in public Downloads via MediaStore
 * (readable from any file manager, with no broad storage permission).</p>
 */
public final class DownloadsCrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context context;
    private final Thread.UncaughtExceptionHandler previous;

    private DownloadsCrashHandler(Context context, Thread.UncaughtExceptionHandler previous) {
        this.context = context;
        this.previous = previous;
    }

    /** Idempotent: installing twice never stacks two copies of this handler. */
    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof DownloadsCrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
            new DownloadsCrashHandler(context.getApplicationContext(), current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeReport(thread, throwable);
        } catch (Throwable ignored) {
            // Crash reporting must never mask the original crash.
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private void writeReport(Thread thread, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fileName = "opencode-crash-" + timestamp + ".txt";
        byte[] report = buildReport(thread, throwable).getBytes(StandardCharsets.UTF_8);

        // Runtime home: visible in the app's workspace browser and through its FileProvider.
        try {
            File directory = new File(TermuxConstants.TERMUX_HOME_DIR, "Downloads");
            if (directory.isDirectory() || directory.mkdirs()) {
                try (FileOutputStream out = new FileOutputStream(new File(directory, fileName))) {
                    out.write(report);
                }
            }
        } catch (Throwable ignored) {
        }

        // Public Downloads: scoped MediaStore write, no MANAGE_EXTERNAL_STORAGE required.
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                    if (out != null) out.write(report);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private String buildReport(Thread thread, Throwable throwable) {
        StringBuilder report = new StringBuilder();
        report.append("OpenCode Android crash report\n");
        report.append("=============================\n\n");
        report.append("Time: ")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
            .append('\n');
        report.append("Thread: ").append(thread.getName()).append('\n');
        report.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        report.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            report.append("App: ").append(info.versionName)
                .append(" (").append(info.getLongVersionCode()).append(")\n");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        report.append('\n');
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        report.append(stack);
        return report.toString();
    }
}
