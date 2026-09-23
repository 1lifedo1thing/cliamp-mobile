package stream.kleeamp.mobile.net

import kotlinx.coroutines.delay

/**
 * Runs [block], trying up to [attempts] times in all. A fetch is worth a few
 * attempts because radio-browser and Apple drop connections under load; only
 * after the last try does the error surface so the screen can offer a manual
 * "try again" instead of retrying forever. The gap grows between tries so a
 * dead service is not hammered while it clears.
 */
suspend fun <T> retryFetch(attempts: Int = 3, block: suspend () -> T): T {
    var tryNo = 0
    while (true) {
        tryNo += 1
        try {
            return block()
        } catch (e: Exception) {
            if (tryNo >= attempts) throw e
            delay(300L * tryNo)
        }
    }
}