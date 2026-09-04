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
import androidx.glance.layout.fillMaxHeight
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
            CliampWidgetContent(context, prefs)
        }
    }

    companion object {
        val SMALL = DpSize(150.dp, 60.dp)
        val WIDE = DpSize(250.dp, 60.dp)
        val TALL = DpSize(250.dp, 140.dp)
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
    val spectrum by prefs.widgetSpectrum.collectAsState(initial = emptyList())
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

WidgetBody(station, track, playing, spectrum, palette)
}

@androidx.compose.runtime.Composable
private fun WidgetBody(
    station: Station?,
    track: String,
    playing: Boolean,
    spectrum: List<Float>,
    p: CliampPalette,
) {
    val size = LocalSize.current
    val wide = size.width >= 220.dp
    // Big enough to show the visualizer: content pins to the top. Small: the
    // player block just centres itself in the full widget height instead.
    val big = size.height >= 120.dp

    Column(
        GlanceModifier
            .fillMaxSize()
            .cornerRadius(18.dp)
            .background(ColorProvider(p.ground))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = if (big) Alignment.Top else Alignment.Vertical.CenterVertically,
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
                        track.ifBlank {
                            when (station?.source) {
                                StationSource.Local, StationSource.Provider -> station.meta
                                else -> station?.meta?.ifBlank { "live stream" }
                            }.orEmpty().ifBlank { "pick a station" }
                        },
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

        if (wide && big) {
            Spacer(GlanceModifier.height(10.dp))
            if (playing && spectrum.isNotEmpty()) {
                // Brick meter, mirroring the in-app visualizer: a bottom-up
                // stack of lit bricks over an unlit grid, with a bright peak
                // cap floating one brick above the current level.
                BrickMeter(spectrum, p, GlanceModifier.defaultWeight())
            } else {
                StreamingRule(if (playing) "streaming" else "stopped", p, dim = !playing)
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
 * The widget's static brick meter, matching the in-app visualizer's look.
 * Widgets cannot animate or draw, so the service's downsampled snapshot
 * becomes a fixed arrangement of bricks: an unlit grid at full height, lit
 * accent bricks climbing from the baseline, and a bright peak cap one brick
 * above the level. The brick pitch, not a flat bar, is what sells the match.
 */
@androidx.compose.runtime.Composable
private fun BrickMeter(bars: List<Float>, p: stream.cliamp.mobile.ui.theme.CliampPalette, modifier: GlanceModifier) {
    // Mirrors the expanded player's NowPlaying meter exactly: 24 columns to a
    // row, small bricks stacked from the bottom edge (so the phase never
    // shifts) with a bright peak cap riding the lit edge and a column gap
    // between each bar. The widget width is shared out across the columns the
    // same way, so the meter just fills whatever width it's given.
    val size = LocalSize.current
    val brick = 4.dp
    val gap = 3.dp
    val colGap = 3.dp
    val step = brick + gap
    // The meter sits below the header, which eats roughly 54dp of the widget
    // height. More height means more rows of the same small, fixed bricks -
    // never bigger ones - and the count is rounded UP so the grid tiles all
    // the way to the top (any tiny overshoot clips at the widget edge instead
    // of leaving a gap), filling the full height exactly like in-app.
    val rows = ((size.height - 54.dp + step - 1.dp) / step).toInt().coerceIn(3, 30)
    val columns = 24
    val n = bars.size.coerceAtLeast(1)

    Row(
        modifier.fillMaxWidth().fillMaxHeight(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        for (c in 0 until columns) {
            if (c > 0) Spacer(GlanceModifier.width(colGap))
            val level = bars[((c * n) / columns).coerceIn(0, n - 1)].coerceIn(0f, 1f)
            val litRows = (level * rows).toInt().coerceIn(0, rows)
            val peakRow = if (litRows < rows) litRows else -1
            Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
                // Flex spacer absorbs any slack so the fixed-size brick stack
                // always anchors to the bottom of the widget - the meter runs
                // from the bottom of the player down to the bottom edge, no
                // matter how tall the widget is.
                Spacer(GlanceModifier.defaultWeight())
                for (r in rows - 1 downTo 0) {
                    val color = when {
                        r == peakRow -> p.peak
                        r < litRows -> p.accent
                        else -> p.unlit
                    }
                    Box(
                        GlanceModifier
                            .fillMaxWidth()
                            .height(brick)
                            .background(ColorProvider(color)),
                    ) {}
                    if (r > 0) Spacer(GlanceModifier.height(gap))
                }
            }
        }
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
