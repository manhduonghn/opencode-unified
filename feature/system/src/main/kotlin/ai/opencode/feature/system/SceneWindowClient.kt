package ai.opencode.feature.system

/**
 * `SceneWindow` lives in `:core` ([ai.opencode.core.scene.SceneWindowClient]) so the in-app browser
 * (`:feature:media`) can open links from its window; this name keeps the system and session call
 * sites unchanged.
 */
typealias SceneWindowClient = ai.opencode.core.scene.SceneWindowClient
