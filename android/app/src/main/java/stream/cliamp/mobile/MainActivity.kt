package stream.cliamp.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Both bars stay, both are transparent, and the app draws through
        // them.
        //
        // The navigation bar used to be hidden outright, because on
        // three-button navigation it sat below the tabs as a band of somebody
        // else's chrome. The cost of that was the whole point of the bar: back
        // was reachable only by swiping the bar out first, which left the
        // "back" line at the top of every overlay as the one dependable way
        // out - the far corner of a big phone, and the wrong end of it for a
        // thumb. A transparent bar over the app's own ground is not somebody
        // else's chrome; it is three glyphs on our background.
        //
        // `dark` on both styles rather than `auto` is what keeps them
        // transparent: auto paints a scrim behind the navigation bar in light
        // mode. The icon colour is not fixed here - it follows the palette
        // below, which the system's own light/dark mode cannot know.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

            // A light palette on a device in dark mode was getting white
            // status icons on a cream ground. The palette is the only thing
            // that knows what is actually behind them.
            val view = LocalView.current
            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

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
