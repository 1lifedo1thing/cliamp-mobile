package stream.cliamp.mobile.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.glance.GlanceId
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.theme.CliampPalette
import stream.cliamp.mobile.ui.theme.paletteFor

/**
 * A deliberately simple now-playing widget: one row of title, artist and the
 * transport controls. Nothing moves, so there is nothing to keep fed - no
 * meter, no clock, no scrubber. That is the whole point: the launcher never
 * gets a stream of near-continuous updates, so redraws stay cheap and smooth.
 *
 * Two platform constraints shape it. Widgets cannot load a custom font, so the
 * monospace is the system's rather than JetBrains Mono. And widgets cannot
 * animate, so the row is a static snapshot the launcher can cache.
 */
class CliampWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    /**
     * Every value the widget draws is collected INSIDE provideContent.
     *
     * This is the whole trick with Glance: provideContent hosts a long-lived
     * composition, and collecting the flows there makes the widget react to
     * DataStore writes on its own. The service writes only on real state
     * changes (tune, play/pause, stream title), so the widget recomposes only
     * when something worth showing actually changed.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = (context.applicationContext as CliampApp).prefs

        provideContent {
            CliampWidgetContent(context, prefs)
        }
    }
}

@androidx.compose.runtime.Composable
internal fun CliampWidgetContent(
    context: Context,
    prefs: stream.cliamp.mobile.data.Prefs,
) {
    val station by prefs.lastStation.collectAsState(initial = null)
    val playing by prefs.widgetPlaying.collectAsState(initial = false)
    val track by prefs.widgetTrack.collectAsState(initial = "")
    val paletteName by prefs.palette.collectAsState(initial = "system")

    val systemDark = (context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    // Resolved through paletteFor rather than mapped to dark-or-light
    // here, so the widget actually wears the chosen theme. The old
    // version only knew "light" and "system" and fell through to the
    // dark green pair for everything else, which now means every
    // default install - and which was already wrong for the light
    // Omarchy themes.
    val palette = paletteFor(paletteName, systemDark)

    WidgetBody(station, track, playing, palette)
}

@androidx.compose.runtime.Composable
private fun WidgetBody(
    station: Station?,
    track: String,
    playing: Boolean,
    p: CliampPalette,
) {
    Row(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(18.dp)
            .background(ColorProvider(p.ground))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .clickable(actionRunCallback<OpenAppAction>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                station?.name ?: "nothing tuned",
                style = mono(15, FontWeight.Bold, p.ink),
                maxLines = 1,
            )
            Text(
                widgetSubtitle(track, station),
                style = mono(11, FontWeight.Normal, p.inkTertiary),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(10.dp))
        Key(R.drawable.ic_w_prev, 19.dp, 15.dp, p, filled = false, action = ACT_PREV)
        Spacer(GlanceModifier.width(6.dp))
        Key(
            if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play,
            if (playing) 14.dp else 16.dp,
            16.dp,
            p, filled = true, action = ACT_TOGGLE,
        )
        Spacer(GlanceModifier.width(6.dp))
        Key(R.drawable.ic_w_next, 19.dp, 15.dp, p, filled = false, action = ACT_NEXT)
    }
}

/**
 * Drawn from vector drawables rather than characters. The obvious shortcut is
 * to put a play triangle in a Text, but Android's emoji font claims U+23F8 and
 * friends, so the key comes out as an orange emoji instead of tinted ink.
 */
@androidx.compose.runtime.Composable
private fun Key(
    @DrawableRes icon: Int,
    iconWidth: androidx.compose.ui.unit.Dp,
    iconHeight: androidx.compose.ui.unit.Dp,
    p: CliampPalette,
    filled: Boolean,
    action: String,
) {
    val bg = when {
        filled && p.dark -> p.accent
        filled -> p.ink
        else -> p.keyFace
    }
    val fg = when {
        filled && p.dark -> p.onAccent
        filled -> p.ground
        else -> p.ink
    }
    Box(
        GlanceModifier
            .size(width = if (filled) 52.dp else 38.dp, height = 38.dp)
            .cornerRadius(9.dp)
            .background(ColorProvider(bg))
            .clickable(actionRunCallback<TransportAction>(transportParams(action))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            modifier = GlanceModifier.size(width = iconWidth, height = iconHeight),
            colorFilter = ColorFilter.tint(ColorProvider(fg)),
        )
    }
}

/**
 * The artist line beneath the song name, mirroring the mini/expanded player's
 * second line. The live stream title (ICY metadata, the currently-served track)
 * wins when present; otherwise it falls back to the station's own artist/album
 * metadata per source, exactly as the in-app player does.
 */
private fun widgetSubtitle(track: String, station: Station?): String {
    if (track.isNotBlank()) return track
    return when (station?.source) {
        StationSource.Cliamp -> "cliamp radio"
        StationSource.Local -> station.artistAlbum.ifBlank { "local audio" }
        StationSource.Podcast -> station.artist.ifBlank { "podcast" }
        StationSource.Provider -> station.meta.ifBlank { "live stream" }
        else -> station?.meta?.ifBlank { "live stream" }.orEmpty().ifBlank { "pick a station" }
    }
}

/**
 * Widgets cannot load res/font, so this is the platform monospace rather than
 * JetBrains Mono. Everything else about the type scale carries over.
 */
private fun mono(size: Int, weight: FontWeight, color: Color) = TextStyle(
    color = ColorProvider(color),
    fontSize = androidx.compose.ui.unit.TextUnit(size.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = weight,
    fontFamily = FontFamily.Monospace,
)