package stream.kleeamp.mobile.library

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import stream.kleeamp.mobile.art.LocalArt
import stream.kleeamp.mobile.art.StationArtSource
import stream.kleeamp.mobile.podcasts.PodcastShow
import stream.kleeamp.mobile.radio.RadioRepository
import stream.kleeamp.mobile.podcasts.ShowState
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.radio.DirectoryState
import stream.kleeamp.mobile.podcasts.EpisodeProgress
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.podcasts.toStation
import stream.kleeamp.mobile.podcasts.downloadSizeLabel
import stream.kleeamp.mobile.servers.ProviderAccount
import stream.kleeamp.mobile.servers.ProviderCatalog
import stream.kleeamp.mobile.servers.displayName
import stream.kleeamp.mobile.servers.ProviderSpec
import stream.kleeamp.mobile.servers.SftpLibrary
import stream.kleeamp.mobile.chrome.rememberStationThumbnail
import stream.kleeamp.mobile.chrome.BackChevron
import stream.kleeamp.mobile.chrome.Chip
import stream.kleeamp.mobile.chrome.ChipDropdown
import stream.kleeamp.mobile.chrome.ChipOption
import stream.kleeamp.mobile.chrome.FilterRow
import stream.kleeamp.mobile.chrome.KleeampIcons
import stream.kleeamp.mobile.chrome.KleeampTextField
import stream.kleeamp.mobile.chrome.GlyphPlate
import stream.kleeamp.mobile.chrome.Gutter
import stream.kleeamp.mobile.chrome.HairlineDivider
import stream.kleeamp.mobile.chrome.ListRow
import stream.kleeamp.mobile.chrome.OverflowButton
import stream.kleeamp.mobile.chrome.OverflowItem
import stream.kleeamp.mobile.chrome.OverflowMenu
import stream.kleeamp.mobile.chrome.ScreenHeader
import stream.kleeamp.mobile.chrome.SectionLabel
import stream.kleeamp.mobile.chrome.scrollToTop
import stream.kleeamp.mobile.chrome.ArtGlow
import stream.kleeamp.mobile.chrome.MainLayout
import stream.kleeamp.mobile.chrome.microPress
import stream.kleeamp.mobile.theme.KleeampShape
import stream.kleeamp.mobile.theme.KleeampType
import stream.kleeamp.mobile.theme.LocalPalette
import stream.kleeamp.mobile.theme.Mono
import stream.kleeamp.mobile.prefs.PlaylistSort
import stream.kleeamp.mobile.prefs.sortedStations

/**
 * A playlist row's leading thumbnail: the item's exact cover when it has one,
 * with a small play/pause badge overlaid when it is the current track. The
 * cover is resolved the way each source's home screen resolves it - embedded
 * art for local files, the known artwork URL for episodes and provider
 * tracks, branding discovery for radio - so a row never shows a
 * generic note where its home shows real art. Coverless rows wear their
 * home placeholder: the show's PodRow, the themed plate (note for local
 * files and provider tracks, broadcast mark for live stations).
 *
 * Shared with folder rows so they wear exactly what list rows wear.
 */

@Composable
internal fun SongCover(s: Station, current: Station?, playing: Boolean) {
    val p = LocalPalette.current
    val art = rememberStationThumbnail(s)
    val active = current?.url == s.url
    // Coverless local files, provider tracks and live stations wear the
    // themed plate - the same accent glyph plate the playlist rows wear -
    // instead of a faint outline box, so the fallback follows the theme
    // like everything else.
    if (art == null && (s.source == StationSource.Local || s.source == StationSource.Provider || !s.isTrack)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            GlyphPlate(
                if (s.isTrack) KleeampIcons.MusicNote else KleeampIcons.StationsTab,
                s.name,
                Modifier.size(40.dp),
            )
            if (active) CoverBadge(playing)
        }
        return
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(KleeampShape.small))
            .then(
                if (art != null) Modifier.background(p.panel)
                else Modifier.border(1.dp, p.chipBorder, RoundedCornerShape(KleeampShape.small))
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Coverless episodes keep their home mark in the row box; local
            // files, provider tracks and stations take the music mark -
            // never a play glyph. Play state keeps its badge below.
            Icon(
                when (s.source) {
                    StationSource.Podcast -> KleeampIcons.PodRow
                    else -> KleeampIcons.MusicNote
                },
                null,
                Modifier.size(if (s.source == StationSource.Podcast) 18.dp else 15.dp),
                tint = if (s.source == StationSource.Podcast) p.inkFaint else p.inkTertiary,
            )
        }
        if (active) CoverBadge(playing)
    }
}

/** The small accent play/pause badge worn over a current track's cover. */
@Composable
private fun CoverBadge(playing: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(KleeampShape.tiny))
            .background(p.accent.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) KleeampIcons.Pause else KleeampIcons.PlayRow,
            null,
            Modifier.size(9.dp),
            tint = p.onAccent,
        )
    }
}
