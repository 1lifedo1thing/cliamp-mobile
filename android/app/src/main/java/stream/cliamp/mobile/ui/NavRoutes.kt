package stream.cliamp.mobile.ui

import kotlinx.serialization.Serializable

@Serializable data object StationsTab
@Serializable data object PodcastsTab
@Serializable data object LibraryTab

@Serializable data object StationsRoot
@Serializable data object PodcastsRoot
@Serializable data object LibraryRoot

@Serializable data object Player
@Serializable data object Queue
@Serializable data object Scope
@Serializable data object Settings
@Serializable data object Search

@Serializable data class ProviderBrowse(val accountId: String)
@Serializable data class ProviderWizardRoute(val providerKey: String, val accountId: String = "")

@Serializable data class PodcastShowRoute(val podcastId: String)

@Serializable data object LibraryProviders
@Serializable data class LibrarySmartPlaylist(val kind: String)
@Serializable data class LibraryPlaylist(val slug: String)
@Serializable data class LibrarySongInfo(val stationUrl: String)
