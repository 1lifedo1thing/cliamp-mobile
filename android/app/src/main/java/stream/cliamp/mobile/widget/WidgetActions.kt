package stream.cliamp.mobile.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.data.Station
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.net.Http

const val ACT_TOGGLE = "toggle"
const val ACT_NEXT = "next"
const val ACT_PREV = "prev"

private val ACTION_KEY = ActionParameters.Key<String>("action")
private val STATION_KEY = ActionParameters.Key<String>("station")

fun transportParams(action: String) = actionParametersOf(ACTION_KEY to action)

fun tuneParams(station: Station) =
    actionParametersOf(STATION_KEY to Http.json.encodeToString(station))

@UnstableApi
class TransportAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        when (parameters[ACTION_KEY]) {
            ACT_TOGGLE -> WidgetControl.toggle(context)
            ACT_NEXT -> WidgetControl.step(context, +1)
            ACT_PREV -> WidgetControl.step(context, -1)
        }
    }
}

@UnstableApi
class TuneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val raw = parameters[STATION_KEY] ?: return
        val station = runCatching { Http.json.decodeFromString<Station>(raw) }.getOrNull() ?: return
        WidgetControl.tune(context, station)
    }
}

class OpenAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
