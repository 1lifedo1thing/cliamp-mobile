package stream.cliamp.mobile.widget

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.R

/**
 * One tap from the shade. For radio this is arguably better than the widget:
 * you reach it without leaving whatever you were doing.
 */
@UnstableApi
class CliampTileService : TileService() {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        if (!scope.isActiveSafe()) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch { render() }
    }

    override fun onStopListening() {
        scope.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            WidgetControl.toggle(this@CliampTileService)
            render()
        }
    }

    private suspend fun render() {
        val app = application as CliampApp
        val playing = app.prefs.widgetPlaying.first()
        val station = app.prefs.readLastStation()
        qsTile?.apply {
            state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = station?.name ?: "cliamp"
            subtitle = if (playing) "streaming" else "stopped"
            icon = Icon.createWithResource(this@CliampTileService, R.drawable.ic_notification)
            updateTile()
        }
    }

    private fun CoroutineScope.isActiveSafe() =
        runCatching { coroutineContext[kotlinx.coroutines.Job]?.isActive == true }.getOrDefault(false)
}
