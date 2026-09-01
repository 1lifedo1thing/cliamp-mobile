package stream.cliamp.mobile.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.annotation.DrawableRes
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.LocalSize
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
import stream.cliamp.mobile.data.CliampRadio
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.ui.theme.DarkPalette
import stream.cliamp.mobile.ui.theme.LightPalette

/**
 * Radio's one real advantage over a music library on a home screen: you
 * almost always want the same handful of stations, so the widget can skip
 * browsing entirely and put them one tap away.
 *
 * Three platform constraints shape this. Widgets cannot load a custom font,
 * so the monospace is the system's rather than JetBrains Mono. They cannot
 * animate, so the brick meter is out and the `--- STREAMING ---` rule takes
 * its place, which is honest about being static. And they cannot draw, so
 * every rule here is a coloured Box.
 */
class CliampWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(SMALL, WIDE, TALL),
    )

    /**
     * Every value the widget draws is collected INSIDE provideContent.
     *
     * This is the whole trick with Glance: provideGlance runs once and
     * provideContent then hosts a long-lived composition. Anything read before
     * provideContent is captured at first composition and frozen there, so the
     * widget cheerfully shows the station you played an hour ago while the
     * DataStore underneath it is perfectly up to date. Collecting the flows in
     * the composition makes the widget react to writes on its own, with no
     * update broadcast needed at all.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = (context.applicationContext as CliampApp).prefs

        provideContent {
            val station by prefs.lastStation.collectAsState(initial = null)
            val playing by prefs.widgetPlaying.collectAsState(initial = false)
            val track by prefs.widgetTrack.collectAsState(initial = "")
            val favourites by prefs.favorites.collectAsState(initial = emptyList())
            val paletteName by prefs.palette.collectAsState(initial = "dark")

            val dark = when (paletteName) {
                "light" -> false
                "system" -> (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                else -> true
            }
            val tune = favourites.ifEmpty { CliampRadio.builtin }

            WidgetBody(station, track, playing, tune, dark)
        }
    }

    companion object {
        val SMALL = DpSize(150.dp, 60.dp)
        val WIDE = DpSize(250.dp, 60.dp)
        val TALL = DpSize(250.dp, 140.dp)
    }
}

@androidx.compose.runtime.Composable
private fun WidgetBody(
    station: Station?,
    track: String,
    playing: Boolean,
    tune: List<Station>,
    dark: Boolean,
) {
    val p = if (dark) DarkPalette else LightPalette
    val size = LocalSize.current
    val wide = size.width >= 220.dp
    val tall = size.height >= 120.dp

    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(18.dp)
            .background(ColorProvider(p.ground))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Row(
            GlanceModifier.fillMaxWidth().clickable(actionRunCallback<OpenAppAction>()),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                Text(
                    station?.name ?: "nothing tuned",
                    style = mono(16, FontWeight.Bold, p.ink),
                    maxLines = 1,
                )
                if (wide) {
                    Text(
                        track.ifBlank { station?.meta?.ifBlank { "live stream" } ?: "pick a station" },
                        style = mono(11, FontWeight.Normal, p.inkTertiary),
                        maxLines = 1,
                    )
                }
            }
            Spacer(GlanceModifier.width(10.dp))
            if (wide) {
                Key(R.drawable.ic_w_prev, 19.dp, 15.dp, p, filled = false, action = ACT_PREV)
                Spacer(GlanceModifier.width(6.dp))
            }
            Key(
                if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play,
                if (playing) 14.dp else 16.dp,
                if (playing) 16.dp else 16.dp,
                p, filled = true, action = ACT_TOGGLE,
            )
            if (wide) {
                Spacer(GlanceModifier.width(6.dp))
                Key(R.drawable.ic_w_next, 19.dp, 15.dp, p, filled = false, action = ACT_NEXT)
            }
        }

        if (wide) {
            Spacer(GlanceModifier.height(10.dp))
            StreamingRule(if (playing) "streaming" else "stopped", p, dim = !playing)
        }

        if (tall) {
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                tune.take(4).forEachIndexed { i, s ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    TuneChip(s, active = s.url == station?.url, palette = p, modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StreamingRule(label: String, p: stream.cliamp.mobile.ui.theme.CliampPalette, dim: Boolean) {
    val rule = if (dim) p.track else p.accent
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Box(GlanceModifier.defaultWeight().height(3.dp).background(ColorProvider(rule))) {}
        Spacer(GlanceModifier.width(8.dp))
        Text(label.uppercase(), style = mono(10, FontWeight.Normal, if (dim) p.inkFaint else p.accent))
        Spacer(GlanceModifier.width(8.dp))
        Box(GlanceModifier.defaultWeight().height(3.dp).background(ColorProvider(rule))) {}
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
    p: stream.cliamp.mobile.ui.theme.CliampPalette,
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

@androidx.compose.runtime.Composable
private fun TuneChip(
    station: Station,
    active: Boolean,
    palette: stream.cliamp.mobile.ui.theme.CliampPalette,
    modifier: GlanceModifier,
) {
    val bg = if (active) palette.accent else palette.panel
    val fg = if (active) palette.onAccent else palette.inkSecondary
    Box(
        modifier
            .height(32.dp)
            .cornerRadius(6.dp)
            .background(ColorProvider(bg))
            .clickable(actionRunCallback<TuneAction>(tuneParams(station))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            station.name.lowercase().take(9),
            style = mono(10, FontWeight.Normal, fg),
            maxLines = 1,
        )
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
