package ai.opencode.feature.system.intents

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.SFSymbol
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ask OpenCode's door: a small dialog over whatever is on screen that sends without opening the
 * app. When a verified assistant ([AppActionActivity.isVerifiedAssistant]) hands the message in
 * `EXTRA_TEXT` it sends at once; any other app's text only fills the field, and Send needs a tap;
 * without text it asks “What should OpenCode do?”. Activities never show over the lock screen, so
 * it needs an unlocked phone.
 *
 * Other apps can start this activity, so its Send can't be covered: other apps' overlays are
 * hidden and obscured touches dropped while it shows, and it closes when anything else comes on
 * top before the message is sent, so a caller can't put its own screen over the dialog and lift it
 * as the user taps.
 */
class AskOpenCodeActivity : ComponentActivity() {
    private val model: AskOpenCodeModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        if (savedInstanceState == null) model.start(intent, sendsAtOnce = AppActionActivity.isVerifiedAssistant(this))
        setContent {
            OpenCodeAppTheme {
                val state by model.state.collectAsStateWithLifecycle()
                AskOpenCodeView(state, model::send, model::cancel, model::update)
            }
        }
        lifecycleScope.launch {
            model.state.collect { if (it.finished) finish() }
        }
    }

    override fun onPause() {
        super.onPause()
        // Nothing sent yet: close rather than wait under whatever came on top.
        if (!isChangingConfigurations && !model.state.value.isSending) model.cancel()
    }
}

/** Ask OpenCode's state: what to send where, and how the send went. */
class AskOpenCodeModel : ViewModel() {
    data class State(
        val message: String = "",
        val sessions: List<SessionEntity> = emptyList(),
        val session: SessionEntity? = null,
        val delivery: DeliveryOption = AskOpenCodeIntent.defaultDelivery,
        val isSending: Boolean = false,
        /** Siri's spoken result: the confirmation, or why it couldn't send. */
        val result: String? = null,
        val failed: Boolean = false,
        val finished: Boolean = false,
    ) {
        val canSend: Boolean get() = !isSending && message.isNotBlank() && result == null
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var sending: Job? = null

    /**
     * [sendsAtOnce]: the message came from a verified assistant, which has already confirmed
     * it (as Siri's parameter does), and may name the session. Any other app's text only fills
     * the field and its session is ignored, so nothing is sent, or aimed, on another app's
     * say-so.
     */
    fun start(intent: Intent, sendsAtOnce: Boolean) {
        val context = dependency(AppContext)
        val directory = SessionDirectory.loadOrEmpty(SessionDirectory.file(context.filesDir))
        val sessions = directory.suggestedSessionEntities()
        val chosen = intent.getStringExtra(SystemActionActivity.EXTRA_CONTROL_TARGET)
            ?.takeIf { sendsAtOnce }
            ?.let { directory.sessionEntities(listOf(it)).firstOrNull() }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        mutableState.value = State(message = text, sessions = sessions, session = chosen ?: SessionQuery.defaultResult(directory))
        if (sendsAtOnce && text.isNotBlank()) send()
    }

    fun update(transform: (State) -> State) = mutableState.update { if (it.isSending) it else transform(it) }

    fun cancel() {
        sending?.cancel()
        mutableState.update { it.copy(finished = true) }
    }

    fun send() {
        val current = state.value
        if (!current.canSend) return
        mutableState.update { it.copy(isSending = true) }
        sending = viewModelScope.launch {
            val haptics = dependency(HapticsClient)
            // Last Viewed Session is the intent's nil.
            val target = current.session?.id?.takeIf { it != SessionEntity.LAST_VIEWED_ID }
            try {
                val sent = SystemPromptSender.send(current.message, target, current.delivery.delivery)
                AppLog.send.info { "Ask OpenCode sent newSession=${sent.isNewSession} delivery=${sent.delivery.rawValue}" }
                haptics.play(Haptic.success)
                mutableState.update { it.copy(isSending = false, result = sent.confirmation) }
                delay(confirmationLinger)
                mutableState.update { it.copy(finished = true) }
            } catch (failure: SystemPromptSender.Failure) {
                AppLog.send.error("Ask OpenCode couldn’t send")
                haptics.play(Haptic.error)
                mutableState.update { it.copy(isSending = false, result = failure.message, failed = true) }
            }
        }
    }

    private companion object {
        /** Long enough to read the confirmation before the dialog goes. */
        val confirmationLinger = 1400.milliseconds
    }
}

@Composable
private fun AskOpenCodeView(
    state: AskOpenCodeModel.State,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onUpdate: ((AskOpenCodeModel.State) -> AskOpenCodeModel.State) -> Unit,
) {
    Box(
        Modifier.fillMaxSize().clickable(onClick = onCancel, indication = null, interactionSource = null).safeDrawingPadding().imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(24.dp).widthIn(max = 520.dp).fillMaxWidth()
                .clickable(onClick = {}, indication = null, interactionSource = null),
            // The desktop dialog: floating fill, hairline ring, 12 dp corners, no tonal elevation.
            shape = OpenCodeSurfaces.dialogShape,
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            // Explicit: `contentColorFor` matches roles by value, and in the dark themes the
            // container shares its colour with a role whose content colour is dim.
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OpenCodeIcon(SFSymbol.TextBubble, contentDescription = null)
                    Text(stringResource(R.string.system_ask_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
                val result = state.result
                if (result != null) {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.failed) OpenCodeTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (state.failed) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OpenCodeSecondaryButton(stringResource(CoreR.string.common_done), onClick = onCancel)
                        }
                    }
                    return@Column
                }
                val focus = remember { FocusRequester() }
                OpenCodeTextField(
                    value = state.message,
                    onValueChange = { text -> onUpdate { it.copy(message = text) } },
                    label = stringResource(R.string.system_ask_message_title),
                    placeholder = stringResource(R.string.system_ask_message_placeholder),
                    minLines = 2,
                    maxLines = 6,
                    enabled = !state.isSending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                SessionPicker(state, onUpdate)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DeliveryOption.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.delivery == option,
                            onClick = { onUpdate { it.copy(delivery = option) } },
                            shape = SegmentedButtonDefaults.itemShape(index, DeliveryOption.entries.size),
                            icon = { OpenCodeIcon(option.symbol, contentDescription = null) },
                            enabled = !state.isSending,
                        ) { Text(option.title) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OpenCodeSecondaryButton(stringResource(CoreR.string.common_cancel), onClick = onCancel)
                    OpenCodePrimaryButton(onClick = onSend, enabled = state.canSend) {
                        if (state.isSending) {
                            CircularProgressIndicator(Modifier.heightIn(max = 18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(CoreR.string.common_send))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionPicker(
    state: AskOpenCodeModel.State,
    onUpdate: ((AskOpenCodeModel.State) -> AskOpenCodeModel.State) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = !state.isSending,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.system_ask_session_title), style = MaterialTheme.typography.labelMedium)
                    Text(state.session?.title ?: stringResource(R.string.system_last_viewed_session), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    state.session?.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                OpenCodeIcon(SFSymbol.ChevronUpChevronDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (entity in state.sessions) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(entity.title, maxLines = 1)
                            entity.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        }
                    },
                    leadingIcon = { OpenCodeIcon(entity.symbol, contentDescription = null) },
                    trailingIcon = if (entity.id == state.session?.id) {
                        { OpenCodeIcon(SFSymbol.Checkmark, contentDescription = stringResource(R.string.system_selected_description)) }
                    } else null,
                    onClick = {
                        expanded = false
                        onUpdate { it.copy(session = entity) }
                    },
                )
            }
        }
    }
}
