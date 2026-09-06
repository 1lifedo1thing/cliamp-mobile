package stream.cliamp.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import stream.cliamp.mobile.ui.CliampRoot
import stream.cliamp.mobile.ui.theme.CliampTheme
import stream.cliamp.mobile.ui.theme.paletteFor

@UnstableApi
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    /**
     * Drops the system navigation bar and leaves it swipeable.
     *
     * The app is already edge-to-edge, but the tab bar reserves the navigation
     * bar's inset, so on three-button navigation the OS strip sat below the
     * tabs as a permanent band of somebody else's chrome. Drawing under it
     * instead is not an option there: the back, home and recents buttons would
     * land on top of the tab labels.
     *
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE means a swipe from the edge still
     * brings it back, and it leaves again by itself, so nothing is unreachable.
     * The status bar stays - the clock and battery are worth their strip.
     */
    private fun hideNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Coming back from another app, or from a transient reveal, can leave the
     * bar showing, so the request is re-made rather than assumed to stick.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideNavigationBar()

        val app = application as CliampApp
        app.player.connect()

        val wanted = buildList {
            // the Visualizer taps the output mix, which the platform treats as
            // a recording capability whether or not a mic is involved
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissions.launch(wanted.toTypedArray())

        setContent {
            val preference by app.prefs.palette.collectAsState(initial = "system")
            val systemDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val palette = paletteFor(preference, systemDark)
            val haptics by app.prefs.haptics.collectAsState(initial = true)
            val dark = palette.dark

            CliampTheme(palette = palette, haptics = haptics) {
                CliampRoot(
                    repository = app.repository,
                    prefs = app.prefs,
                    player = app.player,
                    localLibrary = app.localLibrary,
                    playlists = app.playlists,
                    providers = app.providers,
                    podcasts = app.podcasts,
                    dark = dark,
                )
            }
        }
    }
}
