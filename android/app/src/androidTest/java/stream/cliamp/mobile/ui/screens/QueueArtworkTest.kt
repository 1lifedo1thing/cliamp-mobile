package stream.cliamp.mobile.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import stream.cliamp.mobile.data.Station
import stream.cliamp.mobile.data.StationSource
import stream.cliamp.mobile.ui.theme.CliampTheme

@RunWith(AndroidJUnit4::class)
class QueueArtworkTest {
    @get:Rule val compose = createComposeRule()
    private var queue by mutableStateOf(emptyList<Station>())
    private val covers = mutableListOf<File>()
    private lateinit var server: ArtworkServer

    @Before fun startServer() {
        server = ArtworkServer()
        server.start()
    }

    @After fun cleanUp() {
        server.close()
        covers.forEach { it.delete() }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun localCover(color: Int): String {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File.createTempFile("queue-art-", ".png", cache).also {
            it.writeBytes(png(color))
            covers += it
        }.toURI().toString()
    }

    private fun station(name: String, source: StationSource, cover: String = ""): Station {
        val id = UUID.randomUUID().toString()
        return Station(id, name, "file:///$id.mp3", source, cover = cover)
    }

    private fun show(vararg stations: Station) {
        queue = stations.toList()
        compose.setContent {
            CliampTheme(haptics = false) {
                QueueContent(queue, 0, queue.first(), false, {}, { _, _ -> }, {}, {})
            }
        }
    }

    private fun assertCover(name: String, color: Int) {
        val description = "Cover art for $name"
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val bitmap = compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .assertIsDisplayed().captureToImage().asAndroidBitmap()
        assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    @Test fun localCoversAppearInCurrentAndUpcomingRowsAndRefreshWhenChanged() {
        show(
            station("Current song", StationSource.Local, localCover(Color.RED)),
            station("Next song", StationSource.Local, localCover(Color.BLUE)),
            station("No cover", StationSource.Local),
        )
        assertCover("Current song", Color.RED)
        assertCover("Next song", Color.BLUE)
        compose.onNodeWithText("No cover").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cover art for No cover", useUnmergedTree = true).assertDoesNotExist()
        val updated = localCover(Color.GREEN)
        compose.runOnIdle { queue = queue.mapIndexed { i, s -> if (i == 1) s.copy(cover = updated) else s } }
        compose.waitUntil(5_000) {
            val nodes = compose.onAllNodesWithContentDescription("Cover art for Next song", useUnmergedTree = true)
            if (nodes.fetchSemanticsNodes().isEmpty()) false
            else {
                val image = nodes[0].captureToImage().asAndroidBitmap()
                image.getPixel(image.width / 2, image.height / 2) == Color.GREEN
            }
        }
    }

    @Test fun podcastUsesTheEpisodeCoverUrl() {
        server.responses["/podcast.png"] = "image/png" to png(Color.BLUE)
        show(
            station("Current song", StationSource.Local),
            station("Podcast episode", StationSource.Podcast, server.url("/podcast.png")),
        )
        assertCover("Podcast episode", Color.BLUE)
    }

    @Test fun radioUsesHomepageArtworkAndFallsBackToFavicon() {
        server.responses["/home"] = "text/html" to "<meta property=\"og:image\" content=\"/radio.png\">".toByteArray()
        server.responses["/radio.png"] = "image/png" to png(Color.GREEN)
        server.responses["/broken-home"] = "text/html" to "<meta property=\"og:image\" content=\"/missing.png\">".toByteArray()
        server.responses["/favicon.png"] = "image/png" to png(Color.RED)
        show(
            station("Radio artwork", StationSource.Directory).copy(homepage = server.url("/home")),
            station("Radio favicon", StationSource.Directory).copy(
                homepage = server.url("/broken-home"), favicon = server.url("/favicon.png")),
            station("Radio without artwork", StationSource.Directory),
        )
        assertCover("Radio artwork", Color.GREEN)
        assertCover("Radio favicon", Color.RED)
        compose.onNodeWithText("Radio without artwork").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cover art for Radio without artwork", useUnmergedTree = true).assertDoesNotExist()
    }
}

/** A loopback-only fixture; each test starts it and closes/joins its worker. */
private class ArtworkServer : AutoCloseable {
    val responses = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var worker: Future<*>
    fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

    fun start() {
        worker = executor.submit {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: SocketException) {
                    if (socket.isClosed) break else throw e
                }
                client.use {
                    it.soTimeout = 2_000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val response = responses[path]
                    val body = response?.second ?: ByteArray(0)
                    val status = if (response == null) "404 Not Found" else "200 OK"
                    val header = "HTTP/1.1 $status\r\nContent-Type: ${response?.first ?: "text/plain"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(header.toByteArray()); write(body); flush() }
                }
            }
        }
    }

    override fun close() {
        socket.close()
        executor.shutdown()
        worker.get(3, TimeUnit.SECONDS)
    }
}
