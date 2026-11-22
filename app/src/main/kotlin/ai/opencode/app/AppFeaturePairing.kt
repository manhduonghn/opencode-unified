package ai.opencode.app

import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.ThisDevice
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.notices.AccessibilityAnnouncer
import ai.opencode.server.LocalNetworkPermission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName

/*
 * Everything pairing in one place. Validating a pasted or scanned code, an unsolicited link waiting
 * for confirmation, redeeming it, and where a pairing lands once its computer's sessions load.
 * The install's first computer opens a ready session, another new computer opens the drawer
 * on its sessions, and re-pairing a known one stays put. Every pairing that lands plays the
 * handshake: `success` and, for TalkBack, the computer's name.
 */

enum class PairingArrival {
    /** This install's first computer ever: a new session in its latest project. */
    firstComputer,

    /** A computer no saved server is or shares an address with. */
    newComputer,
    knownComputer,
    ;

    companion object {
        fun of(server: OpenCodeServer, saved: List<OpenCodeServer>, history: InstallHistory): PairingArrival = when {
            saved.isEmpty() && history != InstallHistory.paired -> firstComputer
            server.isNewComputer(saved) -> newComputer
            else -> knownComputer
        }
    }
}

/**
 * The pairing's Home just loaded. Anything the person asked for on the way in ([asked]: a share,
 * notification or system action) wins over where the pairing would take them; the handshake
 * plays either way.
 * The install's first computer opens a ready session in its latest project
 * (`HomeFeature.Action.FirstComputerPaired`); any other new computer opens the drawer.
 */
internal fun landPairing(arrival: PairingArrival?, server: OpenCodeServer, asked: Boolean): Effect<AppFeature.Action> {
    if (arrival == null) return Effect.none()
    dependency(HapticsClient).play(Haptic.success)
    dependency(AccessibilityAnnouncer).announce(localized(R.string.app_pairing_connected_announcement, server.friendlyName), false)
    if (asked) return Effect.none()
    return when (arrival) {
        PairingArrival.firstComputer -> Effect.send(AppFeature.Action.Home(HomeFeature.Action.FirstComputerPaired))
        PairingArrival.newComputer -> Effect.send(AppFeature.Action.Home(HomeFeature.Action.PairedComputerOpened))
        PairingArrival.knownComputer -> Effect.none()
    }
}

/** A pasted or scanned code, as a target to redeem, or as the reason it isn't one. */
internal fun validatePairing(text: String): Result<OpenCodePairingLink> {
    val link = OpenCodePairingLink.decodeCopied(text)
    if (link == null) {
        // A code with one address the app can't use names it, rather than "isn't a pairing code".
        val unusable = OpenCodePairingLink.unusableAddress(text)
            ?: return Result.failure(OpenCodeClientError(invalidPairingCodeMessage))
        return Result.failure(OpenCodeClientError(unusableAddressMessage(unusable)))
    }
    // `opencode pair` reports whatever the service is bound to. If that's only loopback, no
    // amount of retrying will help — say so precisely.
    if (link.isLoopback) return Result.failure(OpenCodeClientError(loopbackPairingMessage))
    return Result.success(link.normalizedForRedemption)
}

/**
 * What a failed pairing says beside Paste: a code that isn't a pairing link at all reads as one,
 * not as the long "run opencode pair" hint.
 */
internal fun pairingProblem(message: String): String =
    if (message == invalidPairingCodeMessage) AppFeature.NOT_A_PAIRING_LINK_MESSAGE else message

/**
 * An unsolicited pairing link (an opened link, a transcript or browser tap) waits for
 * confirmation; one arriving while another waits is ignored.
 */
internal fun requestPairing(text: String, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    LaunchBloomController.completeColdLaunch()
    val pending = state.pendingPairing
    if (pending != null) {
        val count = state.ignoredPendingPairingCount + 1
        AppLog.app.info { "pairing link ignored while pending id=${pending.id} ignoredCount=$count" }
        return Reduced(state.copy(ignoredPendingPairingCount = count))
    }
    val result = validatePairing(text)
    val link = result.getOrNull()
    if (link == null) {
        dependency(HapticsClient).play(Haptic.error)
        return Reduced(state.copy(pairingError = result.exceptionOrNull()?.message))
    }
    return Reduced(
        state.copy(
            pairingError = null,
            ignoredPendingPairingCount = 0,
            pendingPairing = AppFeature.PendingPairing(
                id = dependency(UuidClient).next().toString(),
                link = link,
                replacedServerNames = link.replacedServers(state.knownServers).map { it.displayName },
            ),
        ),
    )
}

/** The person confirmed the waiting link: leave Home and redeem it. */
internal fun beginPairing(link: OpenCodePairingLink, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val next = leaveHome(
        state.copy(
            pendingPairing = null,
            ignoredPendingPairingCount = 0,
            pairingError = null,
            isScanningPairingCode = false,
            isEditingServer = false,
            editingSavedServer = null,
            manualEntryDraft = null,
        ),
    )
    dependency(HapticsClient).play(Haptic.tap)
    return redeem(link, next)
}

/** Exchange a one-time pairing link for a token, then connect normally. */
internal fun redeem(link: OpenCodePairingLink, state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val normalized = link.normalizedForRedemption
    val generation = state.connectionGeneration + 1
    val attempt = AppFeature.ConnectionAttempt(id = generation, server = normalized.server)
    val next = state.copy(
        pendingNotification = null,
        connection = AppFeature.State.Connection.Pairing,
        connectionGeneration = generation,
        connectionAttempt = attempt,
    )
    val client = dependency(OpenCodeClient)
    val localNetwork = dependency(LocalNetworkPermission)
    return Reduced(
        next,
        Effect.run<AppFeature.Action> { send ->
            if (localNetwork.blocks(normalized.server)) localNetwork.request()
            val result = try {
                Result.success(client.redeemPairing(normalized))
            } catch (error: OpenCodeClientError) {
                Result.failure(error)
            }
            send(AppFeature.Action.PairingRedeemed(attempt.id, result))
        }.cancellable(AppFeature.CancelID.Health, cancelInFlight = true),
    )
}

internal val invalidPairingCodeMessage: String
    get() = localized(R.string.app_pairing_invalid_code)

internal fun unusableAddressMessage(unusable: OpenCodePairingLink.UnusableAddress): String =
    localized(R.string.app_pairing_unusable_address, unusable.address, unusable.reason)

internal val loopbackPairingMessage: String
    get() = localized(R.string.app_pairing_loopback, ThisDevice.casualName)
