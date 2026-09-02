package stream.cliamp.mobile.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.CliampRadio
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.widget.CliampWidgetReceiver

/**
 * Owns the player, the session and the audio effects. Everything the phone
 * shows outside the app - lockscreen, notification, Bluetooth, Android Auto -
 * comes from the MediaSession this service publishes.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val fx = AudioFx(bands = SPECTRUM_BANDS)
    @Volatile private var spectrumWanted = true
    private var artworkJob: kotlinx.coroutines.Job? = null
    private var reconnector: Reconnector? = null
    private lateinit var prefs0: stream.cliamp.mobile.data.Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val http = OkHttpDataSource.Factory(Http.streamClient)
            .setUserAgent(Http.USER_AGENT)
            // without this header the server never interleaves song titles
            .setDefaultRequestProperties(mapOf(IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_NAME to "1"))

        val sources = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(DefaultDataSource.Factory(this, http))

        // Buffer tuning has to serve two very different cases in one player.
        // Live radio cannot re-buffer from the past, so it wants a deep ceiling
        // and a long recovery after a re-buffer. On-demand files (local songs,
        // provider tracks) can re-buffer from anywhere; waiting on a deep
        // pre-roll was what made every song take 1-2s to *start* - the old 30s
        // minimum and 5s play-start threshold forced several seconds of reading
        // plus decoding before a single sample reached the speaker. Lower the
        // floor and play-start bar so local tracks begin in under a second, and
        // keep the generous ceiling so radio can still hold a deep buffer.
        // Media3 requires bufferForPlaybackAfterRebufferMs <= minBufferMs, so
        // both sit at 2s and radio's blip-tolerance comes from the deep ceiling
        // (radio streams that truly die are re-armed by the Reconnector anyway).
        prefs0 = (application as CliampApp).prefs
        val targetBufferMs = runBlocking { prefs0.bufferSeconds.first() } * 1_000
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_000,                                          // sustain on ~2s
                (targetBufferMs * 4).coerceIn(60_000, 180_000), // radio depth ceiling
                500,                                            // start as soon as ~0.5s in
                2_000,                                          // must be <= minBufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        player.addListener(PlayerEvents())
        player.addAnalyticsListener(FormatEvents())

        reconnector = Reconnector(this, player, scope) { retrying, attempt ->
            PlaybackBus.publishReconnect(if (retrying) attempt else 0)
            if (retrying) PlaybackBus.publishError(null)
        }.also { it.attach() }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(SessionCallback())
            .build()

        // A single-item queue means Media3 offers no prev/next, so the default
        // notification is one lonely play button. These put station stepping and
        // favouriting on the lockscreen where they belong.
        scope.launch {
            prefs0.favorites.collect { favs ->
                val url = PlaybackBus.station.value?.url
                session?.setMediaButtonPreferences(buttons(favs.any { it.url == url }))
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        val prefs = prefs0
        scope.launch {
            combine(prefs.visualizer, prefs.eqEnabled, prefs.eqBands) { vis, eqOn, bands ->
                Triple(vis, eqOn, bands)
            }.collect { (vis, eqOn, bands) ->
                spectrumWanted = vis != "off"
                fx.attach(
                    player.audioSessionId,
                    spectrumWanted,
                    onSpectrum = PlaybackBus::publishSpectrum,
                    onLiveChanged = PlaybackBus::publishSpectrumLive,
                )
                PlaybackBus.publishEqBandLabels(fx.bandLabels)
                fx.setEqEnabled(eqOn)
                if (eqOn) fx.applyBands(bands)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun buttons(isFavourite: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous station")
            .setIconResId(R.drawable.ic_w_prev)
            .setSessionCommand(SessionCommand(CMD_PREV_STATION, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(
            if (isFavourite) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED
        )
            .setDisplayName(if (isFavourite) "Remove favourite" else "Favourite")
            .setIconResId(if (isFavourite) R.drawable.ic_w_star_filled else R.drawable.ic_w_star)
            .setSessionCommand(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next station")
            .setIconResId(R.drawable.ic_w_next)
            .setSessionCommand(SessionCommand(CMD_NEXT_STATION, Bundle.EMPTY))
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PREV_STATION, Bundle.EMPTY))
                .add(SessionCommand(CMD_NEXT_STATION, Bundle.EMPTY))
                .add(SessionCommand(CMD_FAVOURITE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons(false))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PREV_STATION -> scope.launch { stepStation(-1) }
                CMD_NEXT_STATION -> scope.launch { stepStation(+1) }
                CMD_FAVOURITE -> scope.launch {
                    PlaybackBus.station.value?.let { prefs0.toggleFavorite(it) }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Station branding arrives over the network, so it must not sit between the
     * tap and the audio. The item starts with the locally drawn plate and this
     * swaps in the real thing whenever it turns up, reusing the same
     * replaceMediaItem trick the ICY title uses.
     */
    private fun loadArtwork(station: Station) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val art = StationArtSource.bitmapFor(station) ?: return@launch
            if (PlaybackBus.station.value?.url != station.url) return@launch
            val item = player.currentMediaItem ?: return@launch
            val bytes = StationArtwork.withArt(this@PlaybackService, station, art)
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                    )
                    .build(),
            )
        }
    }

    /** Same walk the widget does: favourites if any, otherwise cliamp's channels. */
    private suspend fun stepStation(delta: Int) {
        val list = prefs0.favorites.first().ifEmpty { CliampRadio.builtin }
        if (list.isEmpty()) return
        val here = PlaybackBus.station.value?.url
        val i = list.indexOfFirst { it.url == here }
        val next = if (i < 0) list.first() else list[(i + delta + list.size) % list.size]

        PlaybackBus.publishStation(next)
        PlaybackBus.publishError(null)
        prefs0.setLastStation(next)
        prefs0.pushHistory(next)
        val resolved = StreamResolver.resolve(next.url)
        player.setMediaItem(mediaItem(this@PlaybackService, next, resolved))
        player.prepare()
        player.play()
    }

    /**
     * Anything the widget or tile draws has to be written down, not held in
     * RAM. Icecast sends a metadata block roughly once a second, so this is
     * deduped: without the guard it wrote two preferences and woke every
     * widget several times a second for state that had not changed.
     */
    private var lastWidgetState: Triple<Boolean, String, String>? = null

    private fun publishWidgetState() {
        val next = Triple(
            player.isPlaying,
            PlaybackBus.streamTitle.value,
            PlaybackBus.station.value?.url.orEmpty(),
        )
        if (next == lastWidgetState) return
        lastWidgetState = next
        scope.launch {
            val favs = prefs0.favorites.first()
            session?.setMediaButtonPreferences(
                buttons(favs.any { it.url == PlaybackBus.station.value?.url })
            )
            prefs0.setWidgetPlaying(next.first)
            prefs0.setWidgetTrack(next.second)
            // Collecting the flows inside the composition only updates the
            // widget while its Glance session is alive, and sessions are
            // short-lived. The nudge is what covers a dormant widget.
            CliampWidgetReceiver.refresh(this@PlaybackService)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A radio app that keeps playing after the task is swiped is a bug, not
        // a feature - unless it is actually playing, in which case leave it be.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        reconnector?.detach()
        fx.release()
        scope.cancel()
        // the session must go first; releasing the player under a live
        // session leaves the notification pointing at a dead controller
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    val title = entry.title?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        PlaybackBus.publishStreamTitle(title)
                        pushToSession(title)
                        publishWidgetState()
                    }
                }
            }
        }

        /**
         * The notification reads MediaItem.mediaMetadata, not the combined
         * in-stream metadata, so the ICY title never reaches the lockscreen on
         * its own. replaceMediaItem with an identical URI is the supported way
         * to change metadata without re-preparing the stream.
         */
        private fun pushToSession(songTitle: String) {
            val item = player.currentMediaItem ?: return
            val station = PlaybackBus.station.value ?: return
            val current = item.mediaMetadata
            if (current.artist?.toString() == songTitle) return
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon()
                    .setMediaMetadata(
                        current.buildUpon()
                            .setTitle(station.name)
                            .setArtist(songTitle)
                            .build()
                    )
                    .build(),
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // the reconnector decides whether this is fatal; only say so once
            // it has given up, otherwise the UI flashes red mid-retry
            if (reconnector?.isRetrying != true) {
                PlaybackBus.publishError(
                    error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) PlaybackBus.publishError(null)
            publishWidgetState()
            // the session id only becomes valid once the audio renderer is up,
            // so this is the attach that usually wins - it must still respect
            // the user's setting rather than force the visualizer back on
            fx.attach(
                player.audioSessionId,
                spectrumWanted,
                onSpectrum = PlaybackBus::publishSpectrum,
                onLiveChanged = PlaybackBus::publishSpectrumLive,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                PlaybackBus.station.value?.let(::loadArtwork)
            }
            // replaceMediaItem (how the ICY title reaches the notification)
            // surfaces here too; clearing the title on that would fight the
            // very update that caused it.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                PlaybackBus.publishStreamTitle("")
            }
            publishWidgetState()
        }
    }

    private inner class FormatEvents : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            PlaybackBus.publishFormat(
                StreamFormat(
                    bitrateKbps = if (format.bitrate != Format.NO_VALUE) format.bitrate / 1000 else 0,
                    sampleRateHz = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 0,
                    codec = format.sampleMimeType?.substringAfter('/')?.removePrefix("mpeg")?.ifBlank { "mp3" }.orEmpty(),
                )
            )
        }
    }

    companion object {
        const val SPECTRUM_BANDS = 64
        const val CMD_PREV_STATION = "stream.cliamp.mobile.PREV_STATION"
        const val CMD_NEXT_STATION = "stream.cliamp.mobile.NEXT_STATION"
        const val CMD_FAVOURITE = "stream.cliamp.mobile.FAVOURITE"

        /** Metadata the notification and lockscreen read. */
        fun mediaItem(context: Context, station: Station, resolved: ResolvedStream): MediaItem =
            MediaItem.Builder()
                .setMediaId(station.id)
                .setUri(resolved.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.meta.ifBlank { "cliamp radio" })
                        .setStation(station.name)
                        .setArtworkData(
                            StationArtwork.forStation(context, station),
                            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                        )
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .build()
                )
                .build()
    }
}
