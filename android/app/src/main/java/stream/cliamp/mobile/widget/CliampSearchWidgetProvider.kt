package stream.cliamp.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.R
import stream.cliamp.mobile.ui.theme.paletteFor
import stream.cliamp.mobile.ui.theme.decodeCustomThemeOrNull

/**
 * Static home-screen search bar: one tappable row that opens the app's
 * Search page. No playback state, no collection - just a themed hint with a
 * PendingIntent into [MainActivity] carrying [MainActivity.EXTRA_OPEN_SEARCH].
 */
@UnstableApi
class CliampSearchWidgetProvider : android.appwidget.AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        SearchWidgetRenderer.refresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        mgr: AppWidgetManager,
        id: Int,
        opts: android.os.Bundle,
    ) {
        SearchWidgetRenderer.refresh(context)
    }
}

@UnstableApi
object SearchWidgetRenderer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Re-read the palette and push every search instance. */
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            runCatching { render(ctx) }
                .onFailure { Log.e("cliamp/search-wid", "search widget push failed", it) }
        }
    }

    /** Below this width the hint has no room: the centered icon takes over. */
    private const val COMPACT_MAX_WIDTH_DP = 110

    private suspend fun render(ctx: Context) {
        val app = ctx.applicationContext as CliampApp
        val paletteName = app.prefs.palette.first()
        val systemDark = (ctx.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val p = paletteFor(paletteName, systemDark, decodeCustomThemeOrNull(app.prefs.customTheme.first()))

        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, CliampSearchWidgetProvider::class.java))
        for (id in ids) {
            val compact = cellWidth(mgr, id) < COMPACT_MAX_WIDTH_DP
            mgr.updateAppWidget(id, buildViews(ctx, p, compact))
        }
    }

    private fun cellWidth(mgr: AppWidgetManager, id: Int): Int {
        val o = mgr.getAppWidgetOptions(id)
        return minOf(
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 999),
            o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 999),
        )
    }

    internal fun buildViews(
        ctx: Context,
        p: stream.cliamp.mobile.ui.theme.CliampPalette,
        compact: Boolean = false,
    ): RemoteViews {
        val rv = RemoteViews(
            ctx.packageName,
            if (compact) R.layout.widget_search_compact else R.layout.widget_search,
        )
        if (!compact) {
            rv.setTextViewText(R.id.s_hint, ctx.getString(R.string.search_widget_hint))
            rv.setTextColor(R.id.s_hint, p.inkTertiary.toArgb())
        }
        rv.setInt(R.id.s_bg, "setColorFilter", p.ground.toArgb())
        rv.setInt(R.id.s_icon, "setColorFilter", p.accent.toArgb())

        val openSearch = PendingIntent.getActivity(
            ctx, 100,
            Intent(ctx, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_SEARCH)
                .putExtra(MainActivity.EXTRA_OPEN_SEARCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.s_content, openSearch)
        return rv
    }
}
