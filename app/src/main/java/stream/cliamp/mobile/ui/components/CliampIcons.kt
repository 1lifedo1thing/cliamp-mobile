package stream.cliamp.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn geometry only: rectangles, triangles, straight paths. No rounded
 * caps, no gradients, no emoji. Every path below is copied out of the concept
 * SVGs so the shapes stay identical; colour comes from the Icon tint.
 */
private fun solid(w: Float, h: Float, vararg d: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = w.dp, defaultHeight = h.dp,
        viewportWidth = w, viewportHeight = h,
    ).apply {
        d.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

private fun stroked(w: Float, h: Float, sw: Float, vararg d: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = w.dp, defaultHeight = h.dp,
        viewportWidth = w, viewportHeight = h,
    ).apply {
        d.forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = sw,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

private fun rect(x: Float, y: Float, w: Float, h: Float) = "M$x ${y}h${w}v${h}h${-w}z"

private fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float): String = buildString {
    append("M${x + r} ${y}")
    append("h${w - 2 * r}a$r $r 0 0 1 $r ${r}")
    append("v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}")
    append("h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}")
    append("v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z")
}

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} $cy" +
        "a$r $r 0 1 1 ${2 * r} 0" +
        "a$r $r 0 1 1 ${-2 * r} 0z"

object CliampIcons {

    /**
     * The cliamp mark: three descending bars, from Cliamp Icon 02. Tinted with
     * the accent in app chrome; [MarkColour] carries the green tile for places
     * where the brand mark stands on its own.
     */
    val Mark = solid(
        48f, 48f,
        rect(6f, 8f, 36f, 7f),
        rect(6f, 20.5f, 22f, 7f),
        rect(6f, 33f, 30f, 7f),
    )

    /** The mark on its tile, in the icon's own colours. */
    val MarkColour: ImageVector = ImageVector.Builder(
        defaultWidth = 48f.dp, defaultHeight = 48f.dp,
        viewportWidth = 48f, viewportHeight = 48f,
    ).apply {
        addPath(
            addPathNodes(rrect(0f, 0f, 48f, 48f, 7.2f)),
            fill = SolidColor(Color(0xFF5EE08A)),
        )
        listOf(
            rect(6f, 8f, 36f, 7f),
            rect(6f, 20.5f, 22f, 7f),
            rect(6f, 33f, 30f, 7f),
        ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color(0xFF061308))) }
    }.build()

    val PlayTiny = solid(9f, 10f, "M0 0l9 5-9 5z")
    val PlayRow = solid(14f, 14f, "M1 1l12 6-12 6z")
    val PlayTab = solid(18f, 18f, "M2 1l14 8-14 8z")
    val PlayWide = solid(12f, 13f, "M0 0l12 6.5L0 13z")
    val MusicNote = solid(
        16f, 16f,
        circle(4f, 12f, 2.1f),
        circle(11.5f, 12f, 2.1f),
        rect(4.4f, 3f, 1.5f, 9f),
        rect(11.4f, 3f, 1.5f, 9f),
        rect(4.4f, 3f, 8.5f, 2.4f),
    )

    val Prev = solid(22f, 18f, "M12 9L22 1v16z", "M2 9L12 1v16z", rect(0f, 1f, 2.4f, 16f))
    val Next = solid(22f, 18f, "M10 9L0 17V1z", "M20 9L10 17V1z", rect(19.6f, 1f, 2.4f, 16f))
    val Pause = solid(20f, 22f, rrect(1f, 0f, 6.5f, 22f, 1f), rrect(12.5f, 0f, 6.5f, 22f, 1f))
    val Stop = solid(20f, 20f, rect(1f, 1f, 18f, 18f))

    val Shuffle = stroked(18f, 14f, 1.8f, "M1 3h4l8 8h4", "M1 11h4l8-8h4")
    val Repeat = stroked(18f, 14f, 1.8f, "M2 5V3h14v8H4", "M6 8l-3 3 3 3")
    val Star = stroked(16f, 16f, 1.8f, "M8 1.5l1.9 4.2 4.6.5-3.4 3.1.9 4.5L8 11.6 4 13.8l.9-4.5L1.5 6.2l4.6-.5z")
    val StarFilled = solid(16f, 16f, "M8 1.5l1.9 4.2 4.6.5-3.4 3.1.9 4.5L8 11.6 4 13.8l.9-4.5L1.5 6.2l4.6-.5z")

    /**
     * Radio waves: a symmetric stack of three domed waves rising from a
     * transmitter dot set on a grounding baseline. Balanced left and right, the
     * glyph reads as a complete, planted radio mark at tab size. Each dome is a
     * straight-line polyline, so it stays inside the straight-paths-only rule,
     * and it sits cleanly beside the playlists vinyl disc and the queue
     * line-list.
     */
    val StationsTab = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f,
    ).apply {
        // grounding baseline
        addPath(
            addPathNodes("M1.5 16.6h15"),
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.3f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
        // transmitter
        addPath(addPathNodes(circle(9f, 14.9f, 1.3f)), fill = SolidColor(Color.White))
        // three domed waves (left -> over the top -> right)
        listOf(
            "M5.5 14.2L5.97 12.45L7.25 11.17L9 10.7L10.75 11.17L12.03 12.45L12.5 14.2",
            "M3 14.2L3.8 11.2L6 9L9 8.2L12 9L14.2 11.2L15 14.2",
            "M1 14.2L2.07 10.2L5 7.27L9 6.2L13 7.27L15.93 10.2L17 14.2",
        ).forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
    }.build()

    /**
     * A book-stack: two upright stroked books with a slanted volume on top.
     * Reads as "library" at tab size and stays distinct from the queue's
     * line-list and STATIONS' broadcast-signal.
     */
    val LibTab = stroked(18f, 18f, 1.7f, rect(1f, 1f, 5f, 16f), rect(8f, 1f, 5f, 16f), "M15 2l2 15")
    val QueueTabLines = stroked(18f, 18f, 1.7f, "M1 4h16M1 9h11M1 14h11")
    val CmdSmall = stroked(18f, 18f, 1.7f, rrect(0.9f, 1.9f, 16.2f, 14.2f, 2f), "M4.5 7l2.2 2.2L4.5 11.4")
    val Search = stroked(16f, 16f, 1.8f, circle(11.7f, 6.6f, 5.1f), "M10.4 10.4L15 15")
    /** The magnifier used as the app-wide finder's tab glyph. */
    val SearchTab = stroked(18f, 18f, 1.6f, circle(8f, 8f, 4.4f), "M11.2 11.4L15.8 16")

    /**
     * A microphone: capsule, cradle, stand. Stroked like [LibTab] because the
     * tab bar reads as a row of outlines and a solid mic would sit heavier than
     * the three it stands beside.
     */
    val PodsTab = stroked(
        18f, 18f, 1.6f,
        rrect(6.2f, 1f, 5.6f, 9.4f, 2.8f),
        "M3.4 8.4a5.6 5.6 0 0 0 11.2 0",
        "M9 14.1v2.4",
        "M5.8 16.5h6.4",
    )

    /** The same mic at row size, for a show that has no artwork yet. */
    val PodRow = stroked(
        14f, 14f, 1.5f,
        rrect(4.6f, 0.8f, 4.8f, 7.6f, 2.4f),
        "M2.4 6.6a4.6 4.6 0 0 0 9.2 0",
        "M7 11.2v1.9",
    )
    val Plus = solid(14f, 14f, rect(6f, 0f, 2f, 14f), rect(0f, 6f, 14f, 2f))
    val Minus = solid(12f, 12f, rect(0f, 5f, 12f, 2f))
    val Check = stroked(13f, 13f, 2.2f, "M1.5 7l3.2 3.2L11.5 3")
    val Xmark = stroked(12f, 12f, 1.8f, "M2 2l8 8M10 2l-8 8")
    val CaretDown = solid(10f, 10f, "M0 2h10L5 8z")
    val CaretRight = solid(10f, 10f, "M2 0v10l6-5z")
    /**
     * A downward chevron used to collapse / dismiss the expanded player; reads
     * as the inverse of "expanded" (pull down to close).
     */
    val Down = stroked(16f, 10f, 1.8f, "M1 1l7 8 7-8")
    val Download = stroked(16f, 16f, 1.6f, "M8 1v9", "M4.5 6.5L8 10l3.5-3.5", "M1.5 13.5h13")
    /** Vertical ellipsis: row overflow menu. */
    val More = solid(16f, 16f, circle(8f, 3f, 2.2f), circle(8f, 8f, 2.2f), circle(8f, 13f, 2.2f))
    val Lines = solid(16f, 14f, rect(0f, 0f, 16f, 2f), rect(0f, 6f, 16f, 2f), rect(0f, 12f, 16f, 2f))
    val ListShort = stroked(16f, 16f, 1.7f, "M1 3h14M1 8h9M1 13h9")
    /**
     * Sliders: three horizontal rails, each with a knob positioned at a
     * different setting offset. A well-known "settings / controls" glyph built
     * from straight paths only, so it stays inside the straight-paths-only rule.
     */
    val Settings = stroked(
        16f, 16f, 1.7f,
        "M2 3.5h12", "M6 1.5v4",
        "M2 8h12", "M11 6v4",
        "M2 12.5h12", "M8 10.5v4",
    )
    val MeterSmall = solid(
        14f, 14f,
        rect(0f, 9f, 2.4f, 5f), rect(3.9f, 5f, 2.4f, 9f),
        rect(7.8f, 1f, 2.4f, 13f), rect(11.6f, 6f, 2.4f, 8f),
    )
    val Speaker = stroked(20f, 20f, 1.7f, "M2 7l5-4v14l-5-4z", "M11 6.5a4 4 0 010 7")
    val SpeakerSolid = solid(20f, 20f, "M2 7l5-4v14l-5-4z")
    /**
     * An hourglass: two triangles meeting at a waist. Replaces a circle with
     * hands, which broke the no-curves rule and read as a generic clock; two
     * triangles say "time passing" with nothing but straight edges.
     */
    val Clock = ImageVector.Builder(
        defaultWidth = 20.dp, defaultHeight = 20.dp, viewportWidth = 20f, viewportHeight = 20f,
    ).apply {
        listOf(
            "M4.2 2.8L15.8 2.8L10 9.3z",
            "M10 10.7L15.8 17.2L4.2 17.2z",
        ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()
    /**
     * Two stacked rack units with a status light and a vent line each.
     *
     * This replaces a globe built from a circle and two ellipse arcs, which
     * rendered as a spiky asterisk at 14dp and broke the no-curves rule
     * anyway. A rack is rectangles only, and it says "a machine you own"
     * rather than "the internet", which is the actual distinction: these are
     * self-hosted servers, not web services.
     */
    val Server = ImageVector.Builder(
        defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f,
    ).apply {
        listOf(
            rrect(1.2f, 2.4f, 15.6f, 5.2f, 1.2f),
            rrect(1.2f, 10.0f, 15.6f, 5.2f, 1.2f),
            "M11.8 5.0h3.2",
            "M11.8 12.6h3.2",
        ).forEach {
            addPath(
                addPathNodes(it),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
            )
        }
        listOf(rect(3.6f, 4.2f, 1.7f, 1.7f), rect(3.6f, 11.8f, 1.7f, 1.7f))
            .forEach { addPath(addPathNodes(it), fill = SolidColor(Color.White)) }
    }.build()

    val SignalBars = solid(
        17f, 12f,
        rect(0f, 8f, 3f, 4f), rect(4.6f, 5.5f, 3f, 6.5f),
        rect(9.2f, 3f, 3f, 9f), rect(13.8f, 0f, 3f, 12f),
    )
    val Battery = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 12.dp, viewportWidth = 24f, viewportHeight = 12f,
    ).apply {
        addPath(
            addPathNodes(rrect(0.5f, 0.5f, 20f, 11f, 3f)),
            stroke = SolidColor(Color.White), strokeLineWidth = 1f,
        )
        addPath(addPathNodes(rrect(2.5f, 2.5f, 15f, 7f, 1.5f)), fill = SolidColor(Color.White))
        addPath(addPathNodes(rrect(22f, 4f, 2f, 4f, 1f)), fill = SolidColor(Color.White))
    }.build()
}
