package stream.kleeamp.mobile.art

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalArtTest {

    @get:Rule val dirs = TemporaryFolder()

    @Test fun folderCoverNullWithoutDir() {
        assertNull(LocalArt.findFolderCover(null))
        assertNull(LocalArt.findFolderCover(""))
        assertNull(LocalArt.findFolderCover("  "))
    }

    @Test fun folderCoverNullWithoutImages() {
        val dir = dirs.newFolder("bare")
        File(dir, "track.mp3").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(LocalArt.findFolderCover(dir.absolutePath))
    }

    @Test fun folderCoverPrefersNamedCompanion() {
        val dir = dirs.newFolder("album")
        File(dir, "zzz.png").writeBytes(byteArrayOf(1))
        val cover = File(dir, "cover.jpg").also { it.writeBytes(byteArrayOf(2)) }
        assertEquals("file://${cover.absolutePath}", LocalArt.findFolderCover(dir.absolutePath))
    }

    @Test fun folderCoverFallsBackToAnyImage() {
        val dir = dirs.newFolder("loose")
        File(dir, "track.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val art = File(dir, "scan001.png").also { it.writeBytes(byteArrayOf(4)) }
        assertEquals("file://${art.absolutePath}", LocalArt.findFolderCover(dir.absolutePath))
    }

    @Test fun folderCoverIgnoresNonImages() {
        val dir = dirs.newFolder("docs")
        File(dir, "notes.txt").writeText("hi")
        assertNull(LocalArt.findFolderCover(dir.absolutePath))
    }
}
