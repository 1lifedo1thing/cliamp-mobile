package stream.kleeamp.mobile.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectorTest {

    @Test
    fun permissionDeniedFailsFast() {
        // Android 10 scoped-storage denial looped "reconnecting" forever.
        assertFalse(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    @Test
    fun missingFileFailsFast() {
        assertFalse(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
    }

    @Test
    fun unplayableContainerFailsFast() {
        assertFalse(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertFalse(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }

    @Test
    fun transientErrorsRetry() {
        assertTrue(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
        assertTrue(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_TIMEOUT))
        assertTrue(isRecoverablePlaybackError(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK))
    }
}
