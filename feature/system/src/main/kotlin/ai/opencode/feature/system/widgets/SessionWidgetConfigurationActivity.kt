package ai.opencode.feature.system.widgets

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeSearchField
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.feature.system.LeftToRight
import ai.opencode.feature.system.R
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.intents.SessionEntity
import ai.opencode.feature.system.intents.sessionEntities
import ai.opencode.feature.system.intents.suggestedSessionEntities
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Choose Session: which session a Start Call or Open Session widget is for. Last Viewed Session and
 * New Session, then the current server's pinned and recent sessions, searchable. Reads the
 * controls' copy of the directory, never the network.
 */
class SessionWidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetID = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(LeftToRight.configuration())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appWidgetID = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetID == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val directory = OpenCodeControls.directory(this)
        setContent {
            OpenCodeAppTheme {
                ChooseSessionView(directory, onChoose = ::choose, onClose = ::finish)
            }
        }
    }

    private fun choose(entity: SessionEntity) {
        lifecycleScope.launch {
            val manager = GlanceAppWidgetManager(this@SessionWidgetConfigurationActivity)
            val glanceID = runCatching { manager.getGlanceIdBy(appWidgetID) }.getOrNull()
            if (glanceID != null) {
                updateAppWidgetState(this@SessionWidgetConfigurationActivity, glanceID) { preferences ->
                    preferences[OpenCodeControls.targetKey] = entity.id
                    preferences[OpenCodeControls.titleKey] = entity.title
                    entity.detail?.let { preferences[OpenCodeControls.detailKey] = it } ?: preferences.remove(OpenCodeControls.detailKey)
                }
                val provider = AppWidgetManager.getInstance(this@SessionWidgetConfigurationActivity).getAppWidgetInfo(appWidgetID)?.provider
                when (provider?.className) {
                    StartCallWidgetReceiver::class.java.name -> StartCallWidget().update(this@SessionWidgetConfigurationActivity, glanceID)
                    else -> OpenSessionWidget().update(this@SessionWidgetConfigurationActivity, glanceID)
                }
            }
            setResult(RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetID)
}

@Composable
private fun ChooseSessionView(directory: SessionDirectory, onChoose: (SessionEntity) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val entities = remember(query, directory) {
        if (query.isBlank()) directory.suggestedSessionEntities() else directory.sessionEntities(query)
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 640.dp),
            // The desktop dialog: floating fill, hairline ring, 12 dp corners, no tonal elevation.
            shape = OpenCodeSurfaces.dialogShape,
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            // Explicit: `contentColorFor` matches roles by value, and the container shares its
            // colour with a role whose content colour is dim (as Ask OpenCode's dialog).
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.system_widget_choose_session_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    OpenCodeIconButton(SFSymbol.Xmark, contentDescription = stringResource(CoreR.string.common_close), onClick = onClose, style = OpenCodeIconButtonStyle.Standard)
                }
                OpenCodeSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(CoreR.string.common_search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(entities, key = { _, entity -> entity.id }) { index, entity ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChoose(entity) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            OpenCodeIcon(entity.symbol, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(entity.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                entity.detail?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                        // Separators between rows only, none under the last.
                        if (index < entities.lastIndex) HorizontalDivider(Modifier.padding(start = 64.dp))
                    }
                }
            }
        }
    }
}
