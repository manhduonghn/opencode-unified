package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation

internal class HomeTestContext(
    val shared: SharedStore = SharedStore.inMemory(),
    val composer: ComposerPersistence = ComposerPersistence.inMemory(),
) {
    private val pinned: DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = shared
        this[ComposerPersistence] = composer
    }

    /** Runs [block] with these dependencies installed (builds state, writes settings). */
    fun <R> read(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing().update(pinned), block)

    fun store(
        scope: CoroutineScope,
        initialState: () -> HomeFeature.State,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<HomeFeature.State, HomeFeature.Action> {
        val all: DependencyValues.Builder.() -> Unit = {
            pinned()
            dependencies()
        }
        val state = DependencyValues.withValues(DependencyValues.testing().update(all), initialState)
        return TestStore(state, HomeFeature(), scope, all)
    }
}

/** The open screen's action inside `.sessionDetail(.presented(x))`, if this is one. */
internal val HomeFeature.Action.detailAction: SessionFeature.Action?
    get() = ((this as? HomeFeature.Action.SessionDetail)?.action as? PresentationAction.Presented)?.action

/** The call's action inside `.sessionDetail(.presented(.voice(.presented(x))))`, if this is one. */
internal val HomeFeature.Action.voiceAction: VoiceFeature.Action?
    get() = ((detailAction as? SessionFeature.Action.Voice)?.action as? PresentationAction.Presented)?.action

/** A transcript read that never answers. */
internal val hangingTranscript: suspend (OpenCodeServer, String, Int) -> List<OpenCodeTranscriptItem> = { _, _, _ -> awaitCancellation() }
