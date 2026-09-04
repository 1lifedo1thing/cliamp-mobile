package stream.cliamp.mobile.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CliampWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CliampWidget()

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Called by the service whenever what the widget shows has changed. */
        fun refresh(context: Context) {
            scope.launch {
                runCatching { CliampWidget().updateAll(context.applicationContext) }
                    .onFailure { android.util.Log.e("cliamp/wid", "widget update failed", it) }
            }
        }
    }
}
