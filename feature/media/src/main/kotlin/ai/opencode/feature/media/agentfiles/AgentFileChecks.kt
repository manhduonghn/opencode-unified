package ai.opencode.feature.media

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

/**
 * Whether the files a reply names in its session folder are there, so a card shows only for a file
 * that exists. Images keep their own probe ([AgentImagePreviews]); files outside the folder aren't
 * read until tapped, since reading one starts the server in that folder. Main thread only.
 */
class AgentFileChecks(
    private val checker: AgentFileChecker?,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** The server's files may have changed (live: the shared image cache's freshness ends). */
    private val onInvalidate: (serverID: String) -> Unit = {},
) {
    sealed interface Result {
        /** There, at [reference] (a bare name found in a subfolder resolves to its path). */
        data class Found(val reference: AgentFileReference) : Result
        data object Missing : Result
    }

    sealed interface Status {
        /** Its card shows and opens [reference]: checked and there, or a file that isn't checked. */
        data class Found(val reference: AgentFileReference) : Status
        data object Missing : Status
        /** Not checked yet, so whether its card shows isn't known. */
        data object Pending : Status
    }

    data class FileKey(val server: String, val directory: String, val path: String)

    private class Checked(val result: Result, val generation: Int)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val results = mutableStateMapOf<FileKey, Checked>()
    private val generations = mutableStateMapOf<String, Int>()
    private val missingTargets = mutableStateSetOf<AgentImageKey>()
    private val watchers = mutableMapOf<FileKey, Int>()
    private val permits = Semaphore(CONCURRENT_CHECKS)
    private val queued = mutableSetOf<FileKey>()

    /** Bumped when the agent may have changed files in [directory]: what was missing is checked again. */
    fun generation(server: OpenCodeServer, directory: String?): Int =
        if (directory == null) 0 else generations[folder(server.persistenceID, directory)] ?: 0

    /** Reads snapshot state, so a composable reading it updates when a check lands. */
    fun status(server: OpenCodeServer?, directory: String?, reference: AgentFileReference): Status {
        if (checker == null || server == null) return Status.Found(reference)
        val target = reference.target(directory)
        if (target != null && AgentImageKey(server.persistenceID, target.absolutePath) in missingTargets) return Status.Missing
        if (directory == null || !checks(reference, directory)) return Status.Found(reference)
        return when (val result = results[FileKey(server.persistenceID, directory, reference.path)]?.result) {
            is Result.Found -> Status.Found(result.reference)
            Result.Missing -> Status.Missing
            null -> Status.Pending
        }
    }

    /** The file a card for [reference] opens, or null while it's unchecked or missing. */
    fun shown(server: OpenCodeServer?, directory: String?, reference: AgentFileReference): AgentFileReference? =
        (status(server, directory, reference) as? Status.Found)?.reference

    /**
     * Checks [references] that need it while they're on screen. Returns the release: a check still
     * waiting its turn when nothing watches it any more is skipped.
     */
    fun watch(server: OpenCodeServer, directory: String, references: List<AgentFileReference>): () -> Unit {
        val checker = checker ?: return {}
        val watched = mutableListOf<FileKey>()
        for (reference in references) {
            if (!checks(reference, directory)) continue
            val key = FileKey(server.persistenceID, directory, reference.path)
            if (key in watched) continue
            watched += key
            watchers[key] = (watchers[key] ?: 0) + 1
            if (!isStale(key, server, directory)) continue
            if (!queued.add(key)) continue
            scope.launch {
                permits.withPermit {
                    // Again when the folder changed during the check.
                    while (key in watchers && isStale(key, server, directory)) {
                        val generation = generation(server, directory)
                        val result = try {
                            checker(server, directory, reference)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Result.Found(reference)
                        }
                        results[key] = Checked(result, generation)
                    }
                }
                queued -= key
            }
        }
        return {
            for (key in watched) {
                val count = (watchers[key] ?: 1) - 1
                if (count <= 0) watchers.remove(key) else watchers[key] = count
            }
        }
    }

    /** Not checked yet, or missing when the folder has changed since. */
    private fun isStale(key: FileKey, server: OpenCodeServer, directory: String): Boolean {
        val checked = results[key] ?: return true
        return checked.result == Result.Missing && checked.generation < generation(server, directory)
    }

    /** The agent may have changed files in [directory]. Safe from any thread. */
    fun invalidate(server: OpenCodeServer, directory: String) {
        onInvalidate(server.persistenceID)
        scope.launch {
            val folder = folder(server.persistenceID, directory)
            generations[folder] = (generations[folder] ?: 0) + 1
        }
    }

    /** The viewer found no file at [absolutePath]: its cards go until the app restarts. */
    fun markMissing(server: OpenCodeServer, absolutePath: String) {
        if (checker == null) return
        missingTargets += AgentImageKey(server.persistenceID, absolutePath)
    }

    private fun folder(server: String, directory: String) = "$server|${AgentFileReference.normalized(directory)}"

    companion object Key : DependencyKey<AgentFileChecks> {
        const val CONCURRENT_CHECKS = 4
        private const val FIND_LIMIT = 50

        /** Card kinds other than images, in the session folder. */
        fun checks(reference: AgentFileReference, directory: String): Boolean {
            if (!reference.kind.showsCard || reference.kind == AgentFileReference.Kind.Image) return false
            val target = reference.target(directory) ?: return false
            return AgentImagePlan.isInside(target, directory)
        }

        /** A name with no folder: if it isn't at the folder's root it may be in a subfolder. */
        fun isBareName(path: String): Boolean =
            !path.contains('/') && !path.contains('\\') && !path.startsWith("~")

        /**
         * One read of the file's first bytes. A bare name that isn't at the root is looked up by its
         * exact name; it resolves only when one file has it. A failure other than a 404 keeps the card.
         */
        suspend fun check(
            server: OpenCodeServer,
            directory: String,
            reference: AgentFileReference,
            client: OkHttpClient = AgentFileLoader.client(),
        ): Result {
            val target = reference.target(directory) ?: return Result.Found(reference)
            val request = server.fileReadRequest(target) ?: return Result.Found(reference)
            try {
                AgentFileLoader.probe(request, server, client = client)
                return Result.Found(reference)
            } catch (error: AgentFileError.NotFound) {
                if (!isBareName(reference.path)) return Result.Missing
            } catch (error: AgentFileError) {
                return Result.Found(reference)
            }
            val files = try {
                dependency(OpenCodeClient).findFiles(server, directory, reference.name, FIND_LIMIT)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.network.debug("File check: find failed")
                return Result.Missing
            }
            var match: String? = null
            for (file in files) {
                if (file.name != reference.name) continue
                if (match != null) return Result.Missing
                match = file.relativePath
            }
            if (match == null) return Result.Missing
            return Result.Found(reference.copy(path = match))
        }

        override val liveValue: AgentFileChecks by lazy {
            val client = AgentFileLoader.client()
            val images = dependency(AgentImageCache)
            AgentFileChecks(checker = { server, directory, reference -> check(server, directory, reference, client) }, onInvalidate = images::invalidate)
        }

        /** Inert: checks nothing, shows every card. */
        override val testValue: AgentFileChecks get() = AgentFileChecks(checker = null, dispatcher = Dispatchers.Unconfined)
    }
}

typealias AgentFileChecker = suspend (OpenCodeServer, String, AgentFileReference) -> AgentFileChecks.Result

/**
 * Checks [files] while they're on screen (or once composed, off a transcript) and gives [content]
 * the ones whose cards show, resolved to the file each opens.
 */
@Composable
fun CheckedAgentFiles(
    files: List<AgentFileReference>,
    server: OpenCodeServer?,
    directory: String?,
    content: @Composable (List<AgentFileReference>) -> Unit,
) {
    val checks = remember { dependency(AgentFileChecks) }
    // The cards show together once every check is in, so a card never lands beside one already shown.
    val shown = mutableListOf<AgentFileReference>()
    var pending = false
    for (file in files) {
        when (val status = checks.status(server, directory, file)) {
            is AgentFileChecks.Status.Found -> shown += status.reference
            AgentFileChecks.Status.Missing -> Unit
            AgentFileChecks.Status.Pending -> pending = true
        }
    }
    if (pending) shown.clear()
    Box(Modifier.fillMaxWidth()) {
        if (shown.isNotEmpty()) content(shown)
        if (server == null || directory == null) return@Box
        val needed = remember(files, directory) {
            val needed = mutableListOf<AgentFileReference>()
            for (file in files) {
                if (AgentFileChecks.checks(file, directory)) needed += file
            }
            needed
        }
        if (needed.isEmpty()) return@Box
        val generation = checks.generation(server, directory)
        var visible by remember { mutableStateOf(false) }
        val watches = visible || LocalAgentImageViewport.current == null
        if (LocalAgentImageViewport.current != null) {
            AgentImageVisibilityMarker(Modifier.matchParentSize()) { isVisible, _ -> visible = isVisible }
        }
        DisposableEffect(watches, server, directory, needed, generation) {
            val release = if (watches) checks.watch(server, directory, needed) else null
            onDispose { release?.invoke() }
        }
    }
}
