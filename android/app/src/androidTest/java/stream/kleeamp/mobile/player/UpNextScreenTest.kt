package stream.kleeamp.mobile.player

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import stream.kleeamp.mobile.model.Station
import stream.kleeamp.mobile.model.StationSource
import stream.kleeamp.mobile.playback.upNextIndexAfterMove
import stream.kleeamp.mobile.theme.KleeampTheme

@RunWith(AndroidJUnit4::class)
class UpNextScreenTest {
    @get:Rule val compose = createComposeRule()
    private var upNext by mutableStateOf(emptyList<Station>())
    private var currentIndex by mutableStateOf(0)
    private val moves = mutableListOf<Pair<Int, Int>>()
    private val removed = mutableListOf<Int>()
    private var played: Station? = null

    private fun show(count: Int = 5, active: Int = 0, duplicate: Boolean = false) {
        upNext = List(count) { index ->
            Station("$index", "Track $index", "https://example.test/$index.mp3", StationSource.Local)
        }.let { if (duplicate) it + it[1] else it }
        currentIndex = active
        compose.setContent {
            KleeampTheme(haptics = false) {
                UpNextContent(
                    upNext, currentIndex, upNext.getOrNull(currentIndex), false,
                    onPlay = { played = upNext[it] },
                    onMove = { from, to ->
                        moves += from to to
                        currentIndex = upNextIndexAfterMove(currentIndex, from, to)
                        upNext = upNext.toMutableList().apply { add(to, removeAt(from)) }
                    },
                    onRemove = { index ->
                        removed += index
                        if (index < currentIndex) currentIndex--
                        upNext = upNext.filterIndexed { i, _ -> i != index }
                    },
                    onBack = {},
                    onClear = { upNext = upNext.take(currentIndex + 1) },
                )
            }
        }
    }

    @Test fun clearRemovesUpcomingRowsAndKeepsCurrentVisible() {
        show()
        compose.onNodeWithText("CLEAR").performClick()
        compose.onNodeWithText("Track 0").assertIsDisplayed()
        compose.onNodeWithText("Track 1").assertDoesNotExist()
        compose.onNodeWithText("CLEAR").assertDoesNotExist()
    }

    @Test fun leftSwipeRemovesOnlyTheSwipedRow() {
        show()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "upnext-gestures.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Track 2").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Track 2").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(2), removed)
            assertEquals("Track 0", upNext[currentIndex].name)
        }
    }

    @Test fun upNextStartsAfterCurrentTrackAndShrinksAsPlaybackAdvances() {
        show(active = 2)
        compose.onNodeWithText("Track 0").assertDoesNotExist()
        compose.onNodeWithText("Track 1").assertDoesNotExist()
        compose.onNodeWithText("Track 3").assertIsDisplayed()
        compose.onNodeWithText("UP NEXT — 2").assertIsDisplayed()
        compose.runOnIdle { currentIndex = 3 }
        compose.onNodeWithText("Track 2").assertDoesNotExist()
        compose.onNodeWithText("Track 4").assertIsDisplayed()
        compose.onNodeWithText("UP NEXT — 1").assertIsDisplayed()
    }

    @Test fun currentTrackStaysPinnedWhileUpNextScrolls() {
        show(count = 60)
        compose.onNode(hasScrollAction()).performScrollToIndex(30)
        compose.onNodeWithText("Track 0").assertIsDisplayed()
        compose.onNodeWithText("PAUSED").assertIsDisplayed()
        compose.onNodeWithText("Up Next").assertIsDisplayed()
    }

    @Test fun rightSwipeDoesNotRemoveAndTapStillPlays() {
        show()
        compose.onNodeWithText("Track 2").performTouchInput { swipeRight() }
        compose.onNodeWithText("Track 2").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(removed.isEmpty())
            assertEquals("Track 2", played?.name)
        }
    }

    private fun assertDiagonalScroll(direction: Float, sidewaysStart: Boolean) {
        show(count = 60)
        val list = compose.onNode(hasScrollAction())
        list.performScrollToIndex(20)
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val drift = with(compose.density) { 18.dp.toPx() }
        list.performTouchInput {
            val start = center
            val end = start + Offset(-width * 0.2f, height * 0.25f * direction)
            down(start)
            if (sidewaysStart) moveTo(start - Offset(drift, 0f), 24)
            for (step in 1..12) moveTo(start + (end - start) * (step / 12f), 32)
            up()
        }
        val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The list should scroll instead of swiping a row ($before -> $after)",
            (before - after) * direction > 0f)
        compose.runOnIdle {
            assertTrue(removed.isEmpty())
            assertTrue(moves.isEmpty())
            assertEquals(60, upNext.size)
            assertEquals(null, played)
        }
    }

    @Test fun diagonalDownGestureScrollsInsteadOfSwiping() {
        assertDiagonalScroll(direction = 1f, sidewaysStart = false)
    }

    @Test fun diagonalUpGestureScrollsInsteadOfSwiping() {
        assertDiagonalScroll(direction = -1f, sidewaysStart = false)
    }

    @Test fun smallSidewaysStartDoesNotStealDownwardScroll() {
        assertDiagonalScroll(direction = 1f, sidewaysStart = true)
    }

    @Test fun shortFastLeftFlickDoesNotRemove() {
        show()
        compose.onNodeWithText("Track 2").performTouchInput {
            val start = Offset(width * 0.8f, centerY)
            swipe(start, start - Offset(width * 0.2f, 0f), durationMillis = 50)
        }
        compose.onNodeWithText("Track 2").assertIsDisplayed()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
    }

    private fun dragRow(from: String, to: String, cancelDrag: Boolean = false) {
        val list = compose.onNode(hasScrollAction())
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithText(from).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val end = compose.onNodeWithText(to).fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        list.performTouchInput {
            down(start)
            advanceEventTime(700)
            moveTo(start)
        }
        compose.mainClock.advanceTimeBy(750)
        // Separate input batches let the preview order remeasure between crossings.
        for (step in 1..8) {
            list.performTouchInput { moveTo(start + (end - start) * (step / 8f), 40) }
            compose.mainClock.advanceTimeBy(48)
        }
        list.performTouchInput { if (cancelDrag) cancel() else up() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun longPressDragCommitsOneMoveOnDrop() {
        show()
        dragRow("Track 1", "Track 3")
        compose.runOnIdle {
            assertEquals(listOf(1 to 3), moves)
            assertEquals(listOf("0", "2", "3", "1", "4"), upNext.map { it.id })
            assertEquals(null, played)
        }
    }

    @Test fun reorderingUpcomingTracksKeepsCurrentTrack() {
        show(active = 2)
        dragRow("Track 4", "Track 3")
        compose.runOnIdle {
            assertEquals(listOf(4 to 3), moves)
            assertEquals("Track 2", upNext[currentIndex].name)
        }
    }

    @Test fun cancelledDragDoesNotEditPlaybackUpNext() {
        show()
        dragRow("Track 1", "Track 3", cancelDrag = true)
        compose.runOnIdle { assertTrue(moves.isEmpty()) }
    }

    @Test fun holdingAtBottomScrollsToOffscreenDropTargets() {
        show(count = 60)
        val list = compose.onNode(hasScrollAction())
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithText("Track 1").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        val end = Offset(start.x, bounds.height - 12f)
        compose.mainClock.autoAdvance = false
        list.performTouchInput { down(start); advanceEventTime(700); moveTo(start) }
        compose.mainClock.advanceTimeBy(750)
        for (step in 1..12) {
            list.performTouchInput { moveTo(start + (end - start) * (step / 12f), 40) }
            compose.mainClock.advanceTimeBy(48)
        }
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        list.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertEquals(1, moves.size)
            assertTrue("Drop should pass the initial viewport: $moves", moves.single().second > 10)
        }
    }

    @Test fun accessibilityOffersMoveAndRemoveWithoutIcons() {
        show()
        val row = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and
                hasText("Track 2"),
        ).fetchSemanticsNode()
        compose.runOnIdle {
            val actions = row.config[SemanticsActions.CustomActions]
            assertEquals(listOf("Move up", "Move down", "Remove from Up Next"), actions.map { it.label })
            actions.first().action()
            assertEquals(listOf(2 to 1), moves)
        }
    }

    @Test fun duplicateEpisodesHaveDistinctKeys() {
        show(duplicate = true)
        compose.onNodeWithText("Track 2").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(2, upNext.count { it.name == "Track 1" })
        }
    }
}
