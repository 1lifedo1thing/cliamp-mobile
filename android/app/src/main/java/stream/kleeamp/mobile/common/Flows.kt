package stream.kleeamp.mobile.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The one screen-state sharing policy: keep flowing while subscribed, plus a
 * grace window so a rotate does not restart the upstream. Every ViewModel's
 * UiState uses this instead of repeating the stateIn call.
 */
fun <T> Flow<T>.stateInUi(scope: CoroutineScope, initial: T): StateFlow<T> =
    stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
