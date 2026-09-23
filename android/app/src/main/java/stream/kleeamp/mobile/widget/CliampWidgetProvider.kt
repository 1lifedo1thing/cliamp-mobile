package stream.kleeamp.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Classic home-screen widget: one RemoteViews row pushed straight over
 * binder. Taps arrive here as explicit broadcasts and run through
 * [WidgetControl] (MediaController, ~15ms a round-trip); pixels go out
 * through [WidgetRenderer], which conflates bursts into one ordered push.
 */
class CliampWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetRenderer.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        opts: android.os.Bundle,
    ) {
        WidgetRenderer.refresh(context)
    }

    @UnstableApi
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WIDGET_ACTION_TOGGLE,
            WIDGET_ACTION_NEXT,
            WIDGET_ACTION_PREV -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        when (intent.action) {
                            WIDGET_ACTION_TOGGLE -> WidgetControl.toggle(context)
                            WIDGET_ACTION_NEXT -> WidgetControl.step(context, +1)
                            WIDGET_ACTION_PREV -> WidgetControl.step(context, -1)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> super.onReceive(context, intent)
        }
    }
}
