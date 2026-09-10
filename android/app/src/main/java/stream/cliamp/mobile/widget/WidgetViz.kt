package stream.cliamp.mobile.widget

/**
 * Which visualizer the home-screen widget draws, resolved from the same
 * `visualizer` setting the app uses (`spectrum` | `scope` | `off`).
 *
 * This is the dispatch point for future visualizers: adding one means adding
 * an entry here and a painter branch in [WidgetRenderer] (returning its
 * bitmap, or null when there is nothing to show). Callers never branch on
 * setting strings - [showsScope] is the only question the layout asks, and
 * [WidgetRenderer.pushVisualizer] is the only tick the service fires, so a
 * new family lands without touching the plumbing.
 *
 * `scope` has no dedicated widget painter yet and mirrors the brick scope
 * until one exists; `off` hides the strip everywhere.
 */
enum class WidgetViz(val settingId: String) {
    SPECTRUM("spectrum"),
    SCOPE("scope"),
    OFF("off"),
    ;

    /** Whether this family draws anything in the widget's scope strip. */
    val showsScope: Boolean get() = this != OFF

    companion object {
        fun of(setting: String?): WidgetViz =
            entries.firstOrNull { it.settingId == setting } ?: SPECTRUM
    }
}
