package ai.opencode.feature.system.widgets

import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.core.ui.toColorScheme
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as MaterialColorProviders
import ai.opencode.core.log.AppLog
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.system.ControlDirectoryStore
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.OpenCodeControlKind
import ai.opencode.feature.system.intents.SessionControlValue
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.intents.SessionEntity
import ai.opencode.feature.system.intents.SystemActionActivity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * IOS's Control Center, Lock Screen and Action button controls become Quick Settings tiles and
 * home-screen widgets. Each opens the app through [SystemActionActivity], which runs the control's
 * intent (`ControlIntents`). Start Call and Open Session are set up for a session, Last Viewed
 * Session by default (a widget's Choose Session; a tile always means Last Viewed Session, since
 * tiles take no configuration). The Live Activities half of the bundle is
 * `liveactivity/LiveUpdateNotifications.kt`.
 */
object OpenCodeControls {
    suspend fun reloadSessionControls(context: Context) {
        runCatching {
            StartCallWidget().updateAll(context)
            OpenSessionWidget().updateAll(context)
        }.onFailure { AppLog.app.error { "Couldn’t redraw widgets: ${it::class.java.simpleName}" } }
        for (tile in listOf(StartCallTileService::class.java, OpenSessionTileService::class.java)) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, tile)) }
        }
    }

    /** The controls' copy of the directory. */
    fun directory(context: Context): SessionDirectory =
        ControlDirectoryStore.file(context.filesDir).load() ?: SessionDirectory.loadOrEmpty(SessionDirectory.file(context.filesDir)).forControls

    /** A control's tap: its kind and choice only, so the tap never reads the directory. */
    fun intent(context: Context, kind: String, target: String?): Intent =
        Intent(context, SystemActionActivity::class.java)
            .setAction(SystemActionActivity.ACTION_SYSTEM)
            .putExtra(SystemActionActivity.EXTRA_CONTROL_KIND, kind)
            .putExtra(SystemActionActivity.EXTRA_CONTROL_TARGET, target)
            .setData(Uri.parse("opencode-control://$kind/${target ?: ""}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // MARK: Widget configuration

    internal val targetKey = stringPreferencesKey("session-target")
    internal val titleKey = stringPreferencesKey("session-title")
    internal val detailKey = stringPreferencesKey("session-detail")

    /** The widget's chosen entity, as it was chosen; null is Last Viewed Session. */
    internal fun chosenEntity(preferences: Preferences): SessionEntity? {
        val id = preferences[targetKey] ?: return null
        return SessionEntity(id, preferences[titleKey].orEmpty(), preferences[detailKey])
    }
}

// MARK: Quick Settings tiles

/** Shared tile behaviour: label from the controls' copy, tap through [SystemActionActivity]. */
abstract class OpenCodeTileService : TileService() {
    protected abstract val kind: String
    protected abstract val symbol: SFSymbol
    protected abstract fun label(value: SessionControlValue): String
    protected abstract fun subtitle(value: SessionControlValue): String?

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val value = SessionControlValue.of(null, OpenCodeControls.directory(this))
        tile.label = label(value)
        tile.subtitle = subtitle(value)
        tile.icon = Icon.createWithResource(this, symbol.drawable)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = OpenCodeControls.intent(this, kind, null)
        // A PendingIntent on Android 14+, the Intent itself on 13 (its only form there).
        val pending = PendingIntentActivityWrapper(this, kind.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
        TileServiceCompat.startActivityAndCollapse(this, pending)
    }
}

/** “Starts a voice call in a session you choose, or the one you viewed last.” */
class StartCallTileService : OpenCodeTileService() {
    override val kind = OpenCodeControlKind.START_CALL
    override val symbol = SFSymbol.Waveform
    override fun label(value: SessionControlValue) = getString(R.string.system_start_call)
    override fun subtitle(value: SessionControlValue): String? =
        OpenCodeControls.directory(this).current?.lastViewed?.title ?: value.title
}

/** “Opens a session you choose.” A tile names Last Viewed Session. */
class OpenSessionTileService : OpenCodeTileService() {
    override val kind = OpenCodeControlKind.OPEN_SESSION
    override val symbol = SFSymbol.BubbleLeft
    override fun label(value: SessionControlValue) = value.title
    override fun subtitle(value: SessionControlValue): String? = OpenCodeControls.directory(this).current?.lastViewed?.title
}

/** “Opens a new OpenCode session.” */
class NewSessionTileService : OpenCodeTileService() {
    override val kind = OpenCodeControlKind.NEW_SESSION
    override val symbol = SFSymbol.SquareAndPencil
    override fun label(value: SessionControlValue) = getString(R.string.system_new_session)
    override fun subtitle(value: SessionControlValue): String? = null
}

// MARK: Home-screen widgets

/** Shared widget behaviour: one button, labelled. */
abstract class OpenCodeControlWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    protected abstract val kind: String

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val directory = OpenCodeControls.directory(context)
        provideContent {
            val preferences = currentState<Preferences>()
            val entity = OpenCodeControls.chosenEntity(preferences)
            val value = SessionControlValue.of(entity, directory)
            GlanceTheme(colors = widgetColors()) {
                ControlButton(
                    symbol = symbol(value),
                    title = title(value),
                    detail = detail(value),
                    description = description(value),
                    intent = OpenCodeControls.intent(context, kind, value.targetID),
                )
            }
        }
    }

    protected abstract fun symbol(value: SessionControlValue): SFSymbol
    protected abstract fun title(value: SessionControlValue): String
    protected abstract fun detail(value: SessionControlValue): String?
    protected open fun description(value: SessionControlValue): String =
        listOfNotNull(title(value), detail(value)).joinToString(", ")
}

/** Label("Start Call", waveform) over the choice's name. */
class StartCallWidget : OpenCodeControlWidget() {
    override val kind = OpenCodeControlKind.START_CALL
    override fun symbol(value: SessionControlValue) = SFSymbol.Waveform
    override fun title(value: SessionControlValue) = localized(R.string.system_start_call)
    override fun detail(value: SessionControlValue): String = value.title
}

/** The choice's name (square.and.pencil for New Session) over its project. */
class OpenSessionWidget : OpenCodeControlWidget() {
    override val kind = OpenCodeControlKind.OPEN_SESSION
    override fun symbol(value: SessionControlValue) = if (value.isNewSession) SFSymbol.SquareAndPencil else SFSymbol.BubbleLeft
    override fun title(value: SessionControlValue) = value.title
    override fun detail(value: SessionControlValue) = value.detail
}

class NewSessionWidget : OpenCodeControlWidget() {
    override val kind = OpenCodeControlKind.NEW_SESSION
    override fun symbol(value: SessionControlValue) = SFSymbol.SquareAndPencil
    override fun title(value: SessionControlValue) = localized(R.string.system_new_session)
    override fun detail(value: SessionControlValue): String? = null
}

class StartCallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StartCallWidget()
}

class OpenSessionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OpenSessionWidget()
}

class NewSessionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NewSessionWidget()
}

/**
 * The app's theme for widgets: the chosen curated theme's colours (the OpenCode desktop palette by
 * default), or the wallpaper's dynamic colours for System. Read when the widget renders.
 */
@Composable
private fun widgetColors(): ColorProviders {
    val theme = shared(ThemeSharedKeys.appTheme).value
    if (theme == AppTheme.System) return GlanceTheme.colors
    return MaterialColorProviders(
        light = OpenCodeColors.resolve(theme, isDark = false).toColorScheme(),
        dark = OpenCodeColors.resolve(theme, isDark = true).toColorScheme(),
    )
}

@Composable
private fun ControlButton(symbol: SFSymbol, title: String, detail: String?, description: String, intent: Intent) {
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .background(GlanceTheme.colors.secondaryContainer)
            // The desktop dock's 12 dp corners.
            .cornerRadius(12.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(actionStartActivity(intent))
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(symbol.drawable),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
        )
        Spacer(GlanceModifier.width(12.dp))
        Column(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                // Without a detail line the title may wrap rather than cut off (large text).
                maxLines = if (detail.isNullOrEmpty()) 2 else 1,
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 13.sp),
                )
            }
        }
    }
}
