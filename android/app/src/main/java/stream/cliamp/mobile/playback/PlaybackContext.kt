package stream.cliamp.mobile.playback

import stream.cliamp.mobile.data.DirectoryQuery

/** Stable identity of the list a user played from, independent of its current contents or sort. */
sealed interface PlaybackContext {
    data class Playlist(val slug: String) : PlaybackContext
    data class Library(val kind: String, val folder: String? = null, val category: String? = null) : PlaybackContext
    data class ProviderSongs(val accountId: String) : PlaybackContext
    data class ProviderAlbum(val accountId: String, val albumId: String) : PlaybackContext
    data class Podcast(val feedUrl: String) : PlaybackContext
    data class Search(val query: String, val category: String) : PlaybackContext
    data class RadioDirectory(val query: DirectoryQuery) : PlaybackContext
    data object CliampRadio : PlaybackContext
    data object CustomRadio : PlaybackContext
}
