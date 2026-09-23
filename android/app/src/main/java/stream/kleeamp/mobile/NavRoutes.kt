package stream.kleeamp.mobile

import kotlinx.serialization.Serializable

/**
 * The three tabs are pages of one pager, not three graphs: Home is the single
 * destination that holds them. Swiping between Stations, Podcasts and Library
 * never touches the back stack, so back from any tab exits natively the way
 * the start tab always did.
 */
@Serializable data object Home

@Serializable data object Player
@Serializable data object UpNext
@Serializable data object Scope
@Serializable data object Settings
@Serializable data object Search

@Serializable data class ProviderWizardRoute(val providerKey: String, val accountId: String = "")

@Serializable data object ScrobbleWizard

@Serializable data class PodcastShowRoute(val podcastId: String)

@Serializable data object LibraryProviders
/** Blank [accountId] shows every account with the picker; set locks to one. */
@Serializable data class LibraryProviderSongs(val accountId: String = "")
@Serializable data class LibrarySmartPlaylist(val kind: String)
@Serializable data class LibraryPlaylist(val slug: String, val pickSongs: Boolean = false)
@Serializable data class LibrarySongInfo(val stationUrl: String)
@Serializable data class LibraryAddToPlaylist(val stationUrl: String)
