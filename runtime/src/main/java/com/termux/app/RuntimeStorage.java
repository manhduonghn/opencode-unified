package com.termux.app;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.List;

/**
 * Public view of the storage helpers that arrived with the Termux runtime.
 *
 * <p>Callers must ask for storage access through the platform permission flow themselves; this
 * class deliberately does not expose {@link StorageAccess#requestStoragePermission}, which would
 * request the very broad "All files access" permission.</p>
 */
public final class RuntimeStorage {

    private RuntimeStorage() {
    }

    /** The runtime home directory, i.e. the workspace root that ships inside the APK. */
    public static File home() {
        return TermuxConstants.TERMUX_HOME_DIR;
    }

    /** The reinstallable prefix. Browsed read-only so a user cannot break the runtime by accident. */
    public static File prefix() {
        return TermuxConstants.TERMUX_PREFIX_DIR;
    }

    /** The runtime home's Downloads folder, where shared files are saved. Created on demand. */
    public static File downloads() {
        File directory = new File(TermuxConstants.TERMUX_HOME_DIR, "Downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create " + directory.getAbsolutePath());
        }
        return directory;
    }

    public static boolean hasPermission(Context context) {
        return StorageAccess.hasStoragePermission(context);
    }

    public static boolean hasAllFilesAccess(Context context) {
        return StorageAccess.hasAllFilesAccess(context);
    }

    public static File primaryRoot() {
        return StorageAccess.primaryRoot();
    }

    public static List<File> volumeRoots(Context context) {
        return StorageAccess.volumeRoots(context);
    }

    public static boolean isPrimaryRoot(File root) {
        return StorageAccess.isPrimaryRoot(root);
    }

    /** Creates the `~/storage` symlinks on a background thread. */
    public static void setupSymlinks(Context context, Runnable onComplete) {
        TermuxInstaller.setupStorageSymlinks(context, onComplete);
    }
}
