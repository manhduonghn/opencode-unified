package ai.opencode.server

import ai.opencode.core.dependencies.DependencyKey
import java.util.UUID

/**
 * The sessions on screen, which the event stream must never shed deltas for ([EventBatcher]). Each
 * window's Home publishes its own set under its own id, so with several windows a session counts
 * while any window shows it. A window that closes without clearing its set only keeps those
 * sessions whole, never the other way round.
 */
class EventStreamFocusClient(
    val publish: (owner: UUID, sessionIDs: Set<String>) -> Unit,
    val contains: (sessionID: String) -> Boolean,
) {
    companion object Key : DependencyKey<EventStreamFocusClient> {
        /** One store for the process; tests make their own. */
        fun inMemory(): EventStreamFocusClient {
            val lock = Any()
            val store = HashMap<UUID, Set<String>>()
            return EventStreamFocusClient(
                publish = { owner, sessionIDs ->
                    synchronized(lock) {
                        if (sessionIDs.isEmpty()) store.remove(owner) else store[owner] = sessionIDs
                    }
                },
                contains = { sessionID ->
                    synchronized(lock) {
                        var found = false
                        for (sessionIDs in store.values) {
                            if (sessionID in sessionIDs) {
                                found = true
                                break
                            }
                        }
                        found
                    }
                },
            )
        }

        override val liveValue: EventStreamFocusClient = inMemory()

        /** Every session counts as on screen, so tests shed nothing unless they install their own. */
        override val testValue: EventStreamFocusClient = EventStreamFocusClient(publish = { _, _ -> }, contains = { true })
    }
}
