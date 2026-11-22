package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.sheet.clientResult
import ai.opencode.feature.session.sheet.asClientError
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDirectoryListing

/**
 * Browsing the connected server's folders for a new session, from the Project sheet. Each folder is
 * read only when it's opened, one `GET /api/fs/list` through the server's default location, so
 * browsing never boots another location and never reads ahead.
 */
class FolderBrowserFeature : Reducer<FolderBrowserFeature.State, FolderBrowserFeature.Action> {
    /** A folder pushed in the Project sheet; null is where browsing starts, the server's default folder. */
    data class Route(val path: String?)

    sealed interface Listing {
        data object Loading : Listing
        data class Loaded(val listing: OpenCodeDirectoryListing) : Listing
        data class Failed(val message: String) : Listing
    }

    data class State(
        val server: OpenCodeServer,
        /** The Project sheet's navigation path. */
        val routes: List<Route> = emptyList(),
        val listings: Map<Route, Listing> = emptyMap(),
    ) {
        /** A route's absolute path, once known. */
        fun directory(route: Route): String? {
            route.path?.let { return it }
            return (listings[route] as? Listing.Loaded)?.listing?.directory
        }

        /** The folder can be chosen once the server has listed it. */
        fun canSelect(route: Route): Boolean = listings[route] is Listing.Loaded
    }

    sealed interface Action {
        /** The Project sheet's navigation path, from its stack. */
        data class RoutesChanged(val routes: List<Route>) : Action
        data class FolderAppeared(val route: Route) : Action
        data class RetryTapped(val route: Route) : Action
        data class Listed(val route: Route, val result: Result<OpenCodeDirectoryListing>) : Action
        /** A folder above the current one, from the title menu. */
        data class AncestorTapped(val path: String) : Action
        data class SelectTapped(val route: Route) : Action
        data class Delegate(val delegate: FolderBrowserFeature.Delegate) : Action
    }

    sealed interface Delegate {
        data class Selected(val directory: String) : Delegate
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.Delegate -> Reduced(state)
        is Action.RoutesChanged -> Reduced(state.copy(routes = action.routes))
        // Coming back to a folder shows what it showed; a failure waits for Try Again.
        is Action.FolderAppeared -> if (state.listings[action.route] != null) Reduced(state) else load(action.route, state)
        is Action.RetryTapped -> load(action.route, state)
        is Action.Listed -> action.result.fold(
            onSuccess = { Reduced(state.copy(listings = state.listings + (action.route to Listing.Loaded(it)))) },
            onFailure = { Reduced(state.copy(listings = state.listings + (action.route to Listing.Failed(it.asClientError().message)))) },
        )
        is Action.AncestorTapped -> {
            val index = state.routes.indexOfLast { state.directory(it) == action.path }
            if (index >= 0) {
                Reduced(state.copy(routes = state.routes.take(index + 1)))
            } else {
                // Above where browsing started: it replaces the stack, and loads.
                val route = Route(action.path)
                val next = state.copy(routes = listOf(route))
                if (next.listings[route] == null) load(route, next) else Reduced(next)
            }
        }
        is Action.SelectTapped -> {
            val directory = state.directory(action.route)
            if (!state.canSelect(action.route) || directory == null) {
                Reduced(state)
            } else {
                Reduced(state, Effect.send(Action.Delegate(Delegate.Selected(directory))))
            }
        }
    }

    private fun load(route: Route, state: State): Reduced<State, Action> {
        val client = dependency(OpenCodeClient)
        val server = state.server
        return Reduced(
            state.copy(listings = state.listings + (route to Listing.Loading)),
            Effect.run<Action> { send ->
                send(Action.Listed(route, clientResult { client.listDirectory(server, route.path) }))
            }.cancellable(LoadID(route), cancelInFlight = true),
        )
    }

    private data class LoadID(val route: Route)

    companion object {
        /** The folders above [path], nearest last, for the title menu. */
        fun ancestors(path: String): List<String> {
            val result = mutableListOf<String>()
            var current = deletingLastPathComponent(path)
            while (current.isNotEmpty() && current != path && current !in result) {
                result.add(0, current)
                if (current == "/") break
                current = deletingLastPathComponent(current)
            }
            return result
        }

        fun name(path: String): String {
            val name = lastPathComponent(path)
            return name.ifEmpty { path }
        }

        /** `NSString.lastPathComponent`: trailing slashes ignored; "/" stays "/". */
        private fun lastPathComponent(path: String): String {
            if (path.isEmpty()) return ""
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty()) return "/"
            return trimmed.substringAfterLast('/')
        }

        /** `NSString.deletingLastPathComponent`: "/a/b" → "/a", "/a" → "/", "a" → "", "/" → "/". */
        private fun deletingLastPathComponent(path: String): String {
            if (path.isEmpty()) return ""
            val trimmed = path.trimEnd('/')
            if (trimmed.isEmpty()) return "/"
            val slash = trimmed.lastIndexOf('/')
            return when {
                slash < 0 -> ""
                slash == 0 -> "/"
                else -> trimmed.substring(0, slash).trimEnd('/').ifEmpty { "/" }
            }
        }
    }
}
