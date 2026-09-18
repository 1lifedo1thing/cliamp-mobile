package stream.kleeamp.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactUrlTest {
    @Test fun queryTokensAreStripped() {
        assertEquals(
            "https://jelly.example.com/Audio/abc/stream.mp3",
            redactUrl("https://jelly.example.com/Audio/abc/stream.mp3?ApiKey=secret123&foo=bar"),
        )
    }

    @Test fun userinfoIsStripped() {
        assertEquals(
            "https://lyrion.example.com:9000/stream.mp3",
            redactUrl("https://user:s3cret@lyrion.example.com:9000/stream.mp3"),
        )
    }

    @Test fun plainUrlsPassThrough() {
        assertEquals(
            "https://radio.cliamp.stream/lofi/stream",
            redactUrl("https://radio.cliamp.stream/lofi/stream"),
        )
    }

    @Test fun garbageFallsBackWithoutQuery() {
        val redacted = redactUrl("not a url?token=abc#frag")
        assertFalse(redacted.contains("token=abc"))
        assertFalse(redacted.contains("#frag"))
    }
}
