package ai.opencode.feature.workspace

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.core.content.FileProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.RuntimeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose replacement for the fork's `FileManagerActivity`.
 *
 * It browses the Termux home (plus the prefix and shared storage) through the same runtime façade
 * the shell uses. All write operations go through [WorkspaceFiles], which keeps the fork's
 * protected-path, symlink and zip-slip rules; no broad provider or storage permission is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(initialPath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val homePath = remember { RuntimeStorage.home().absolutePath }
    var path by rememberSaveable { mutableStateOf(safePath(context, initialPath) ?: homePath) }
    var query by rememberSaveable { mutableStateOf("") }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var goToOpen by remember { mutableStateOf(false) }
    var goToText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var opFailed by remember { mutableStateOf(false) }
    var nameAction by remember { mutableStateOf<NameAction?>(null) }
    var nameText by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf<File?>(null) }
    var cutMode by remember { mutableStateOf(false) }

    LaunchedEffect(path, query, showHidden, refresh) {
        val listing = withContext(Dispatchers.IO) { listWorkspace(File(path), showHidden, query) }
        entries = listing.entries
        failed = listing.failed
    }

    fun finish(result: Result<*>) {
        opFailed = result.isFailure
        if (result.isSuccess) refresh++
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        File(path).name.ifEmpty { path },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        File(path).parentFile?.let { path = it.absolutePath }
                    }) { Text("↑") }
                },
                actions = {
                    IconButton(onClick = {
                        nameAction = NameAction.NewFolder
                        nameText = ""
                    }) { Text("＋") }
                    IconButton(onClick = { goToOpen = true }) { Text("⌕") }
                    IconButton(onClick = { showHidden = !showHidden }) {
                        Text(if (showHidden) "◉" else "○")
                    }
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(context, GitHubActivity::class.java)
                                .putExtra(GitHubActivity.EXTRA_TARGET_DIRECTORY, path),
                        )
                    }) { Text(stringResource(R.string.oc_workspace_github_title)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                path,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.oc_workspace_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = {
                    nameAction = NameAction.NewFile
                    nameText = ""
                }) { Text(stringResource(R.string.oc_workspace_new_file)) }
                TextButton(onClick = {
                    nameAction = NameAction.NewFolder
                    nameText = ""
                }) { Text(stringResource(R.string.oc_workspace_new_folder)) }
                TextButton(onClick = {
                    val source = copied ?: return@TextButton
                    val moving = cutMode
                    scope.launch(Dispatchers.IO) {
                        val target = WorkspaceFiles.uniqueTarget(File(path), source.name)
                        val result = if (moving) WorkspaceFiles.move(source, target)
                        else WorkspaceFiles.copy(source, target)
                        withContext(Dispatchers.Main) {
                            finish(result)
                            if (moving && result.isSuccess) copied = null
                        }
                    }
                }) { Text(stringResource(R.string.oc_workspace_paste)) }
                TextButton(onClick = {
                    val here = File(path)
                    val parent = here.parentFile ?: return@TextButton
                    val target = WorkspaceFiles.uniqueTarget(
                        parent,
                        "${here.name}.zip",
                    )
                    scope.launch(Dispatchers.IO) {
                        val result = WorkspaceFiles.createZip(listOf(here), target)
                        withContext(Dispatchers.Main) { finish(result) }
                    }
                }) { Text(stringResource(R.string.oc_workspace_zip)) }
            }
            if (opFailed) {
                Text(stringResource(R.string.oc_workspace_operation_failed), Modifier.padding(horizontal = 16.dp))
            }
            when {
                failed -> Text(stringResource(R.string.oc_workspace_error), Modifier.padding(16.dp))
                entries.isEmpty() -> Text(stringResource(R.string.oc_workspace_empty), Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(entries, key = { it.file.absolutePath }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) path = entry.file.absolutePath
                                    else selected = entry
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (entry.isDirectory) "▸" else if (entry.isLink) "↗" else "·")
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (entry.isDirectory) stringResource(R.string.oc_workspace_directory)
                                    else formatSize(entry.size),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    PropertyRow(
                        stringResource(R.string.oc_workspace_type),
                        when {
                            entry.isDirectory -> stringResource(R.string.oc_workspace_directory)
                            entry.isLink -> stringResource(R.string.oc_workspace_link)
                            else -> stringResource(R.string.oc_workspace_file)
                        },
                    )
                    PropertyRow(stringResource(R.string.oc_workspace_size), formatSize(entry.size))
                    PropertyRow(stringResource(R.string.oc_workspace_modified), formatDate(entry.modified))
                    PropertyRow(stringResource(R.string.oc_workspace_path), entry.file.absolutePath)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        TextButton(onClick = {
                            nameAction = NameAction.Rename(entry.file)
                            nameText = entry.name
                            selected = null
                        }) { Text(stringResource(R.string.oc_workspace_rename)) }
                        TextButton(onClick = {
                            copied = entry.file
                            cutMode = false
                            selected = null
                        }) { Text(stringResource(R.string.oc_workspace_copy)) }
                        TextButton(onClick = {
                            copied = entry.file
                            cutMode = true
                            selected = null
                        }) { Text(stringResource(R.string.oc_workspace_cut)) }
                        if (!entry.isDirectory && !entry.isLink) {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val downloads = RuntimeStorage.downloads()
                                    val result = WorkspaceFiles.copy(
                                        entry.file,
                                        WorkspaceFiles.uniqueTarget(downloads, entry.file.name),
                                    )
                                    withContext(Dispatchers.Main) { finish(result) }
                                }
                                selected = null
                            }) { Text(stringResource(R.string.oc_workspace_save_downloads)) }
                            TextButton(onClick = {
                                shareEntry(context, entry.file)
                                selected = null
                            }) { Text(stringResource(R.string.oc_workspace_share)) }
                            if (entry.name.endsWith(".zip", ignoreCase = true)) {
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val result = WorkspaceFiles.extractZip(entry.file, File(path))
                                        withContext(Dispatchers.Main) { finish(result) }
                                    }
                                    selected = null
                                }) { Text(stringResource(R.string.oc_workspace_extract)) }
                            }
                        }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val deleted = WorkspaceFiles.delete(entry.file)
                                withContext(Dispatchers.Main) {
                                    opFailed = !deleted
                                    if (deleted) refresh++
                                }
                            }
                            selected = null
                        }) { Text(stringResource(R.string.oc_workspace_delete)) }
                    }
                }
            },
            confirmButton = {
                if (!entry.isDirectory && !entry.isLink && isTextFile(entry.file)) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(context, CodeViewerActivity::class.java)
                                .putExtra(CodeViewerActivity.EXTRA_PATH, entry.file.absolutePath),
                        )
                        selected = null
                    }) { Text(stringResource(R.string.oc_workspace_open)) }
                } else {
                    TextButton(onClick = { selected = null }) {
                        Text(stringResource(R.string.oc_workspace_close))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.oc_workspace_close))
                }
            },
        )
    }

    if (goToOpen) {
        AlertDialog(
            onDismissRequest = { goToOpen = false },
            title = { Text(stringResource(R.string.oc_workspace_go_to)) },
            text = {
                OutlinedTextField(
                    value = goToText,
                    onValueChange = { goToText = it },
                    label = { Text(stringResource(R.string.oc_workspace_path)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    safePath(context, goToText)?.let { path = it }
                    goToOpen = false
                    goToText = ""
                }) { Text(stringResource(R.string.oc_workspace_open)) }
            },
            dismissButton = {
                TextButton(onClick = { goToOpen = false }) {
                    Text(stringResource(R.string.oc_workspace_cancel))
                }
            },
        )
    }

    nameAction?.let { action ->
        AlertDialog(
            onDismissRequest = { nameAction = null },
            title = {
                Text(
                    stringResource(
                        when (action) {
                            NameAction.NewFile -> R.string.oc_workspace_new_file
                            NameAction.NewFolder -> R.string.oc_workspace_new_folder
                            is NameAction.Rename -> R.string.oc_workspace_rename
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text(stringResource(R.string.oc_workspace_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val result = when (action) {
                            NameAction.NewFile -> WorkspaceFiles.createFile(File(path), nameText)
                            NameAction.NewFolder -> WorkspaceFiles.createDirectory(File(path), nameText)
                            is NameAction.Rename -> WorkspaceFiles.rename(action.file, nameText)
                        }
                        withContext(Dispatchers.Main) { finish(result) }
                    }
                    nameAction = null
                }) { Text(stringResource(R.string.oc_workspace_save)) }
            },
            dismissButton = {
                TextButton(onClick = { nameAction = null }) {
                    Text(stringResource(R.string.oc_workspace_cancel))
                }
            },
        )
    }
}

/**
 * Shares a file through the app's single existing FileProvider. Files outside `files/home` (for
 * example a shared-storage document) are staged in the provider's `cache/workspace-share` root
 * first, so no second provider or root-path rule is needed.
 */
private fun shareEntry(context: Context, file: File) {
    val insideHome = runCatching {
        file.canonicalFile.startsWith(context.filesDir.canonicalFile)
    }.getOrDefault(false)
    val target = if (insideHome) file else runCatching {
        val stageRoot = File(context.cacheDir, "workspace-share")
        stageRoot.mkdirs()
        val staged = WorkspaceFiles.uniqueTarget(stageRoot, file.name)
        if (WorkspaceFiles.copy(file, staged).isSuccess) staged else null
    }.getOrNull() ?: return

    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.devtools.files",
            target,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.oc_workspace_share)),
        )
    }
}

private sealed interface NameAction {
    object NewFile : NameAction
    object NewFolder : NameAction
    data class Rename(val file: File) : NameAction
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.weight(2f))
    }
}

/** Rejects deep-link paths outside the runtime roots. Symlinks are resolved before the check. */
private fun safePath(context: Context, requested: String?): String? = runCatching {
    if (requested.isNullOrBlank()) return null
    val candidate = File(requested).canonicalFile
    val roots = listOf(
        RuntimeStorage.home(),
        RuntimeStorage.prefix(),
        RuntimeStorage.primaryRoot(),
    )
    val allowed = roots.any { root -> candidate.startsWith(root.canonicalFile) }
    if (allowed) candidate.absolutePath else null
}.getOrNull()

private data class WorkspaceEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
)

private data class Listing(val entries: List<WorkspaceEntry>, val failed: Boolean)

private fun listWorkspace(dir: File, showHidden: Boolean, query: String): Listing {
    val children = runCatching { dir.listFiles() }.getOrNull()
        ?: return Listing(emptyList(), true)
    val entries = children.asSequence()
        .filter { showHidden || !it.name.startsWith(".") }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .map { file ->
            WorkspaceEntry(
                file = file,
                name = file.name,
                isDirectory = file.isDirectory,
                isLink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false),
                size = runCatching { file.length() }.getOrDefault(0L),
                modified = file.lastModified(),
            )
        }
        .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy {
            it.name.lowercase(Locale.ROOT)
        })
        .toList()
    return Listing(entries, false)
}

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonc", "xml", "html", "htm", "css", "js", "mjs",
    "cjs", "ts", "tsx", "jsx", "java", "kt", "kts", "gradle", "groovy", "c", "h", "cc",
    "cpp", "hpp", "py", "rb", "php", "sh", "bash", "zsh", "fish", "yml", "yaml", "toml",
    "ini", "cfg", "conf", "properties", "csv", "tsv", "sql", "log", "gitignore",
    "gitattributes", "editorconfig", "env", "service", "desktop", "srt", "vtt", "tex",
    "diff", "patch", "lock", "pro", "mk", "cmake", "makefile",
)

private fun isTextFile(file: File): Boolean {
    val name = file.name.lowercase(Locale.ROOT)
    if (name in setOf("dockerfile", "makefile")) return true
    val dot = name.lastIndexOf('.')
    return dot >= 0 && name.substring(dot + 1) in TEXT_EXTENSIONS
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[0]}"
    else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatDate(millis: Long): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}.getOrDefault("—")
