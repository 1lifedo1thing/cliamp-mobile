package stream.cliamp.mobile.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.core.app.NotificationCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import stream.cliamp.mobile.CliampApp
import stream.cliamp.mobile.MainActivity
import stream.cliamp.mobile.R
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationArtSource
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.data.wrapNext
import stream.cliamp.mobile.net.Http
import stream.cliamp.mobile.widget.WidgetRenderer

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
            .setDataSourceFactory(CliampDataSourceFactory(DefaultDataSource.Factory(this, http)))

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
            // Media3 1.11 per-stream media progression (issue #3122): renderers
            // for the subsequent playlist item are enabled/primed as the current
            // item finishes, instead of only at the transition. On the Samsung
            // A56 this is what collapses the per-track deep-buffer AudioTrack
            // re-prime that cost ~1.2-1.8s between local songs.
            .enablePerStreamMediaProgression(true)
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
            val conn = (application as CliampApp).player
            combine(prefs0.favorites, conn.shuffle) { favs, _ ->
                val url = PlaybackBus.station.value?.url
                session?.setMediaButtonPreferences(buttons(favs.any { it.url == url }))
            }.collect { }
        }

        setMediaNotificationProvider(
            OxideNotificationProvider(this).apply {
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
                    onSpectrum = ::handleSpectrum,
                    onLiveChanged = PlaybackBus::publishSpectrumLive,
                )
                PlaybackBus.publishEqBandLabels(fx.bandLabels)
                fx.setEqEnabled(eqOn)
                if (eqOn) fx.applyBands(bands)
            }
        }

        // Push whatever state exists to the widget host on cold start. Without
        // this, a service launch that never changes state (nothing playing, no
        // event) leaves the widget sitting on its bare "cliamp" placeholder,
        // because publishWidgetState is otherwise only driven by player events.
        scope.launch { publishWidgetState() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun buttons(
        isFavourite: Boolean,
    ): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
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
        CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setDisplayName("Shuffle")
            .setIconResId(R.drawable.ic_w_shuffle)
            .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
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
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
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
            val conn = (application as CliampApp).player
            when (customCommand.customAction) {
                CMD_PREV_STATION -> scope.launch { conn.prev() }
                CMD_NEXT_STATION -> scope.launch { conn.next() }
                // Shuffle must only flip the flag / reorder, never jump to a
                // random station - that is exactly what the old button did.
                CMD_SHUFFLE -> scope.launch { conn.toggleShuffle() }
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
     *
     * A known cover URL is preferred over discovery. Podcast episodes and
     * provider tracks arrive with real artwork already in [Station.cover], and
     * bitmapFor would never find it: that path scrapes the homepage for an
     * og:image and then falls back to the favicon, which is the right order for
     * radio and simply misses a field it does not read.
     */
    private fun loadArtwork(station: Station) {
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val art = station.cover.takeIf { it.startsWith("http") }
                ?.let { StationArtSource.bitmapForUrl(it) }
                ?: StationArtSource.bitmapFor(station)
                ?: return@launch
            // The bus may still name the previous track this early, so the
            // freshness check below reads the player, not the bus: only stamp
            // art onto the item it was decoded for.
            val item = player.currentMediaItem ?: return@launch
            if (item.mediaId != station.id) return@launch
            val bytes = StationArtwork.withArt(this@PlaybackService, station, art)
            val fresh = player.currentMediaItem
                ?.takeIf { it.mediaId == station.id } ?: return@launch
            if (fresh.mediaMetadata.artworkData?.contentEquals(bytes) == true) return@launch
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                fresh.buildUpon()
                    .setMediaMetadata(
                        fresh.mediaMetadata.buildUpon()
                            .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            .build()
                    )
                    .build(),
            )
        }
    }

    /**
     * Same walk the widget does: the list currently playing (local songs, the
     * source that was tapped - favourites, a provider album, a directory) -
     * falling back to favourites, then cliamp's channels, if nothing is loaded.
     */
    /**
     * Anything the widget or tile draws has to be written down, not held in
     * RAM. Icecast sends a metadata block roughly once a second, so this is
     * deduped: without the guard it wrote two preferences and woke every
     * widget several times a second for state that had not changed.
     */
    private var lastWidgetState: WidgetSig? = null

    /** Identity of the last list published, so next-up rewrites when the list or current song moves. */
    private var lastWidgetSourceKey: String? = null

    /**
     * The four stations that come after the current one in the list being
     * played, wrapping around the end. This is what the widget shows under the
     * meter as "up next", for local songs, radio directories and podcasts all
     * alike.
     */
    private fun widgetUpNext(source: List<Station>, station: Station?): List<Station> {
        if (source.isEmpty() || station == null) return emptyList()
        val i = source.indexOfFirst { it.url == station.url }
        if (i < 0) return emptyList()
        return source.wrapNext(i)
    }

    /**
     * Push widget state only when something actually changed. The widget renders
     * a static row (title, artist, controls), so continuous writes would just
     * be launcher churn; the metadata dedup guards a quiet session.
     */
    private fun publishWidgetState() {
        val station = PlaybackBus.station.value
        val source = PlaybackBus.source.value
        // Intent, not audibility: isPlaying is false while buffering /
        // reconnecting, so using it flips the widget to the play glyph every
        // stall and right after tune (before READY). playWhenReady flips on
        // the tap itself, which is what the toggle icon must mirror.
        val playing = player.playWhenReady && player.mediaItemCount > 0
        val seekable = player.isCurrentMediaItemSeekable
        val duration = player.duration.takeIf { it > 0 } ?: station?.durationMs ?: 0L
        // Seekability and duration are part of the identity: they arrive
        // later than the tap (duration is only known at READY), and without
        // them here the "same triple" early return would swallow the very
        // publish that flips the widget from the streaming rule to the seek
        // row. Position stays out - the one-second ticker owns it.
        val next = WidgetSig(
            playing = playing,
            track = PlaybackBus.streamTitle.value,
            url = station?.url.orEmpty(),
            seekable = seekable,
            durationMs = duration,
        )
        Log.d("cliamp/wid", "publishWidgetState playing=${next.playing} streamTitle=${next.track} url=${next.url} station=${station?.name} seekable=${next.seekable} duration=${next.durationMs}")
        // Both halves of the cache update synchronously. lastWidgetSourceKey
        // used to be assigned inside the launch below (and only when upNext
        // was non-empty), so after a cold start it stayed null forever, the
        // early return never fired, and every tap emitted 2-3 writes +
        // updateAlls - concurrent RemoteViews the launcher can apply out of
        // order, leaving the glyph stuck on a stale frame.
        val sourceKey = (source.map { it.url } + (station?.url.orEmpty())).joinToString("|")
        val upNext = widgetUpNext(source, station)
        if (next == lastWidgetState && sourceKey == lastWidgetSourceKey) return
        lastWidgetState = next
        val sourceChanged = sourceKey != lastWidgetSourceKey
        lastWidgetSourceKey = sourceKey
        // Pixels first: the widget renders this exact row with no disk read
        // on the path. The writes below are persistence for cold boot (and
        // the tile fallback) and never gate what is on screen.
        WidgetRenderer.push(
            this, station, next.track, next.playing,
            seekable = next.seekable,
            durationMs = next.durationMs,
            positionMs = player.currentPosition.coerceAtLeast(0),
        )
        scope.launch {
            val favs = prefs0.favorites.first()
            session?.setMediaButtonPreferences(
                buttons(
                    favs.any { it.url == station?.url },
                )
            )
            Log.d("cliamp/wid", "nextUp source.size=${source.size} count=${upNext.size} names=${upNext.map { it.name }}")
            // Only ever write a real, non-empty next-up. When the in-memory
            // source is empty (playback started/tuned through the widget's
            // MediaController, which never populates PlayerConnection's source)
            // an empty write here would clobber the correct list that
            // persistWidgetWindow / WidgetControl.tune already saved, sinking the
            // widget back to the built-in radio channels.
            if (sourceChanged && upNext.isNotEmpty()) {
                prefs0.setWidgetNext(upNext)
            }
            // One transaction for the whole widget row, so cold-boot readers
            // see a consistent snapshot.
            prefs0.writeWidgetSnapshot(
                playing = next.playing,
                track = next.track,
                seekable = next.seekable,
                durationMs = next.durationMs,
            )
        }
    }

    /** Identity of one widget publish; duration/seekability included because
     * they arrive after the tap that the other three fields describe. */
    private data class WidgetSig(
        val playing: Boolean,
        val track: String,
        val url: String,
        val seekable: Boolean,
        val durationMs: Long,
    )

    /**
     * Ticks the widget twice a second while anything plays: the seek row
     * (elapsed/bar, partial update, skips ticks where the clock second did
     * not move) and the slim scope flipbook (one small bitmap, skipped when
     * there is no spectrum). Paused, empty or failed playback stops the
     * ticker; the last frame already shows the resting state.
     */
    private var progressJob: Job? = null

    private fun syncProgressTicker() {
        val want = player.playWhenReady && player.mediaItemCount > 0
        if (want && progressJob?.isActive == true) return
        progressJob?.cancel()
        progressJob = if (want) scope.launch {
            while (true) {
                val dur = player.duration.takeIf { it > 0 } ?: 0L
                if (player.isCurrentMediaItemSeekable && dur > 0) {
                    WidgetRenderer.pushProgress(
                        this@PlaybackService,
                        player.currentPosition.coerceAtLeast(0),
                        dur,
                    )
                }
                if (prefs0.visualizer.first() != "off") {
                    WidgetRenderer.pushSpectrum(this@PlaybackService)
                }
                delay(500)
            }
        } else null
        if (!want) {
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            if (player.isCurrentMediaItemSeekable && duration > 0) {
                WidgetRenderer.pushProgress(
                    this,
                    player.currentPosition.coerceAtLeast(0),
                    duration,
                )
            }
            // Resting state reads as silence: bars and peaks drop to the
            // grid instead of freezing mid-air.
            WidgetRenderer.settleScope(this)
        }
    }

    /**
     * Shared spectrum sink used by every fx.attach (onCreate and the playing
     * re-attach). The in-app meter and the oscilloscope read it live; the
     * widget samples it twice a second for its slim scope.
     */
    private fun handleSpectrum(it: FloatArray) {
        PlaybackBus.publishSpectrum(it)
        Log.d("cliamp/wid", "handleSpectrum playing=${player.isPlaying} sz=${it.size} live=${fx.spectrumLive}")
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
            syncProgressTicker()
            // the session id only becomes valid once the audio renderer is up,
            // so this is the attach that usually wins - it must still respect
            // the user's setting rather than force the visualizer back on
            fx.attach(
                player.audioSessionId,
                spectrumWanted,
                onSpectrum = ::handleSpectrum,
                onLiveChanged = PlaybackBus::publishSpectrumLive,
            )
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // isPlaying only flips once audio actually flows (READY); intent
            // flips on the tap. Without this, resume sits on the play glyph
            // through the whole buffering stall until first audio.
            publishWidgetState()
            syncProgressTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            // Duration is only known once the source is ready; without this
            // the seek row would wait for the next tap to learn it.
            if (state == Player.STATE_READY) {
                publishWidgetState()
                syncProgressTicker()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek from the app lands here; mirror it now rather than at
            // the next one-second tick.
            if (player.isCurrentMediaItemSeekable && player.duration > 0) {
                WidgetRenderer.pushProgress(
                    this@PlaybackService,
                    player.currentPosition.coerceAtLeast(0),
                    player.duration,
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                // Resolve what is audible from the event's own item: on an
                // auto-advance the published station still names the finished
                // track here, and loading art for that would stamp the old
                // cover onto the new item (or be thrown away by the guard).
                // Unresolvable ids are skipped - the item keeps the plate it
                // was built with rather than risking a wrong cover.
                val station = mediaItem?.mediaId
                    ?.let { (application as CliampApp).player.stationForMediaId(it) }
                if (station != null) loadArtwork(station)
            }
            // replaceMediaItem (how the ICY title reaches the notification)
            // surfaces here too; clearing the title on that would fight the
            // very update that caused it.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                PlaybackBus.publishStreamTitle("")
            }
            publishWidgetState()
            syncProgressTicker()
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
        const val CMD_SHUFFLE = "stream.cliamp.mobile.SHUFFLE"
        const val CMD_FAVOURITE = "stream.cliamp.mobile.FAVOURITE"

        /** Metadata the notification and lockscreen read. */
        fun mediaItem(context: Context, station: Station, resolved: ResolvedStream): MediaItem =
            MediaItem.Builder()
                .setMediaId(station.id)
                .setUri(resolved.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(notificationArtist(station))
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

        /** The artist/subtitle line the notification reads, per source. */
        fun notificationArtist(station: Station): String = when (station.source) {
            StationSource.Local -> station.artistAlbum.ifBlank { "local audio" }
            StationSource.Podcast -> station.artist.ifBlank { "podcast" }
            else -> station.meta.ifBlank { "cliamp radio" }
        }
    }
}

/**
 * The media notification in cliamp's own red.
 *
 * [DefaultMediaNotificationProvider.createNotification] is final, so the colour
 * cannot be set by wrapping it - but [addNotificationActions] is protected and
 * is handed the NotificationCompat.Builder on the way through, which is the one
 * place the notification is still open for editing.
 *
 * The tint is the oxide accent rather than the #7F2117 the launcher tile uses.
 * That red sits at oklch lightness 0.40 and would tint an icon at about 3.3:1
 * against a dark notification shade; the accent is the same red lifted to the
 * lightness the app already uses for text and icons, and clears 5:1 on either
 * shade. Fixed rather than following the chosen theme, because this is the
 * app's identity outside the app - same argument as the launcher icon.
 */
private class OxideNotificationProvider(context: Context) :
    DefaultMediaNotificationProvider(context) {

    override fun addNotificationActions(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory,
    ): IntArray {
        builder.setColor(ACCENT).setColorized(true)
        return super.addNotificationActions(mediaSession, mediaButtons, builder, actionFactory)
    }

    private companion object {
        /** OxidePalette.accent. */
        const val ACCENT = 0xFFD15D4D.toInt()
    }
}
