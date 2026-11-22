package com.termux.app

import android.content.Context
import java.io.File

/**
 * Public, Kotlin-friendly view of the storage helpers that arrived with the Termux runtime.
 *
 * Callers must ask for storage access through the platform permission flow themselves; this
 * object deliberately does not expose [StorageAccess.requestStoragePermission], which would
 * request the very broad "All files access" permission.
 */
object RuntimeStorage {
    fun hasPermission(context: Context): Boolean = StorageAccess.hasStoragePermission(context)

    fun hasAllFilesAccess(context: Context): Boolean = StorageAccess.hasAllFilesAccess(context)

    fun primaryRoot(): File = StorageAccess.primaryRoot()

    fun volumeRoots(context: Context): List<File> = StorageAccess.volumeRoots(context)

    fun isPrimaryRoot(root: File): Boolean = StorageAccess.isPrimaryRoot(root)

    /** Creates the `~/storage` symlinks on a background thread. */
    fun setupSymlinks(context: Context, onComplete: Runnable? = null) {
        TermuxInstaller.setupStorageSymlinks(context, onComplete)
    }
}
