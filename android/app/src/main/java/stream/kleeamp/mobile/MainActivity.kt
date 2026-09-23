package stream.kleeamp.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import stream.kleeamp.mobile.KleeampRoot
import stream.kleeamp.mobile.theme.KleeampTheme
import stream.kleeamp.mobile.theme.decodeCustomThemeOrNull
import stream.kleeamp.mobile.theme.paletteFor

@UnstableApi
class MainActivity : ComponentActivity() {

    companion object {
        /** Action + extra the search widget sends to open the Search page. */
        const val ACTION_OPEN_SEARCH = "stream.kleeamp.mobile.OPEN_SEARCH"
        const val EXTRA_OPEN_SEARCH = "open_search"
    }

    /** Bumped every time an intent asks for Search (cold start + taps while
     * running - the activity is singleTask so the latter arrives via
     * onNewIntent). Observed by KleeampRoot, which navigates to Search. */
    private var openSearchTick by mutableIntStateOf(0)

    private fun wantsSearch(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_SEARCH, false) == true ||
            intent?.action == ACTION_OPEN_SEARCH

    /**
     * A shared URL (or text holding one) becomes a custom station: saved to
     * the custom list and played straight away, so "share → kleeamp" just
     * tunes in.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val url = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')') ?: return
        val station = stream.kleeamp.mobile.ui.screens.customStation("", url) ?: return
        val app = application as KleeampApp
        lifecycleScope.launch { app.prefs.addCustom(station) }
        app.player.doWhenReady { app.player.play(station, listOf(station)) }
    }

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

        if (wantsSearch(intent)) openSearchTick++

        val app = application as KleeampApp
        handleShare(intent)

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
            val preference by app.prefs.palette.collectAsState(initial = app.prefs.initialPalette)
            val systemDark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val customJson by app.prefs.customTheme.collectAsState(initial = "")
            val palette = paletteFor(preference, systemDark, decodeCustomThemeOrNull(customJson))
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

            KleeampTheme(palette = palette, haptics = haptics) {
                KleeampRoot(
                    repository = app.repository,
                    prefs = app.prefs,
                    player = app.player,
                    providers = app.providers,
                    podcasts = app.podcasts,
                    dark = dark,
                    openSearchTick = openSearchTick,
                )
            }
        }

        // The controller bind creates the playback service - ExoPlayer build
        // plus a prefs read - on the main thread. Binding here would contend
        // with inflation and the first composition, so it waits past the
        // first frame: one-shot pre-draw, then posted so the draw itself goes
        // first. Nothing audible can happen before then anyway, and share /
        // auto-resume intents queue on onReady regardless of when it fires.
        window.decorView.post {
            window.decorView.viewTreeObserver.addOnPreDrawListener(
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                        window.decorView.post { app.player.connect() }
                        return true
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsSearch(intent)) openSearchTick++
        handleShare(intent)
    }
}
