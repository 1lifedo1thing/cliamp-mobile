package stream.kleeamp.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalLibraryTest {

    @Test fun albumArtUriNullWithoutAlbum() {
        assertNull(albumArtUri(0))
        assertNull(albumArtUri(-3))
    }

    @Test fun albumArtUriShape() {
        assertEquals(
            "content://media/external/audio/albumart/42",
            albumArtUri(42),
        )
    }
}
