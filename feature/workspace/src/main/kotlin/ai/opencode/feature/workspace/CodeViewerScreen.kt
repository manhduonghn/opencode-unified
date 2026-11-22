package ai.opencode.feature.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Matches the fork's built-in editor limit: larger files stay read-only. */
private const val TEXT_LIMIT = 4L * 1024L * 1024L
private const val UNDO_LIMIT = 30
private const val UNDO_DISABLED_ABOVE = 200_000

/**
 * Compose replacement for the fork's `CodeViewerActivity`. Keeps its atomic save, POSIX mode
 * preservation and undo limits, but drops the XML editor chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(path: String, modifier: Modifier = Modifier) {
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(path) { mutableStateOf("") }
    var loadFailed by remember(path) { mutableStateOf(false) }
    var dirty by remember(path) { mutableStateOf(false) }
    var readOnly by remember(path) { mutableStateOf(!file.canWrite()) }
    var wrap by rememberSaveable(path) { mutableStateOf(false) }
    var fontSize by rememberSaveable(path) { mutableStateOf(14f) }
    var searchOpen by remember(path) { mutableStateOf(false) }
    var query by rememberSaveable(path) { mutableStateOf("") }
    val undo = remember(path) { mutableStateListOf<String>() }

    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() > TEXT_LIMIT) null
            else runCatching { file.readText() }.getOrNull()
        }
        if (loaded == null) {
            loadFailed = true
            readOnly = true
        } else {
            text = loaded
        }
    }

    val matches = remember(text, query) { countMatches(text, query) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(file.name) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!dirty || readOnly) return@IconButton
                        scope.launch(Dispatchers.IO) { runCatching { saveAtomic(file, text) } }
                        dirty = false
                    }) { Text("←") }
                },
                actions = {
                    if (!readOnly) {
                        IconButton(onClick = {
                            val previous = undo.removeLastOrNull()
                            if (previous != null) {
                                text = previous
                                dirty = true
                            }
                        }) { Text("↶") }
                        IconButton(onClick = { searchOpen = !searchOpen }) { Text("⌕") }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                val ok = runCatching { saveAtomic(file, text) }.isSuccess
                                if (ok) dirty = false
                            }
                        }) { Text(stringResource(R.string.oc_workspace_save)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loadFailed) {
                Text(stringResource(R.string.oc_workspace_too_large), Modifier.padding(16.dp))
                return@Column
            }
            if (readOnly) {
                Text(stringResource(R.string.oc_workspace_read_only), Modifier.padding(horizontal = 16.dp))
            }
            if (searchOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.oc_workspace_search)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(R.string.oc_workspace_matches, matches), Modifier.padding(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextButton(onClick = { wrap = !wrap }) {
                    Text("${stringResource(R.string.oc_workspace_wrap)}: ${if (wrap) "ON" else "OFF"}")
                }
                TextButton(onClick = { fontSize = if (fontSize >= 24f) 10f else fontSize + 2f }) {
                    Text("${stringResource(R.string.oc_workspace_text_size)}: ${fontSize.toInt()}")
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    if (!readOnly && next.length <= UNDO_DISABLED_ABOVE && undo.size < UNDO_LIMIT) {
                        undo.add(text)
                    }
                    text = next
                    if (!readOnly) dirty = true
                },
                readOnly = readOnly,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                ),
                minLines = 12,
                maxLines = if (wrap) Int.MAX_VALUE else 1,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            )
        }
    }
}

private fun countMatches(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var from = 0
    while (count < 999) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        count++
        from = at + query.length
    }
    return count
}

/** Writes to a sibling temp file and renames over the original, preserving POSIX mode. */
private fun saveAtomic(file: File, content: String) {
    val permissions = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
    val temp = File(file.parentFile, ".${file.name}.oc-save-${System.nanoTime()}")
    try {
        temp.writeText(content)
        if (permissions != null) {
            runCatching { Files.setPosixFilePermissions(temp.toPath(), permissions) }
        }
        if (!temp.renameTo(file)) throw IOException("Could not replace ${file.name}")
    } finally {
        if (temp.exists()) temp.delete()
    }
}
