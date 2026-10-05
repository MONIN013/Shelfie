package net.monindev.shelfie.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.monindev.shelfie.core.*
import net.monindev.shelfie.render.ShelfProjection
import net.monindev.shelfie.render.ViewportState
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Editing regressions. They replace the device shelf, so they run only in the validation app or an emulator. */
@RunWith(AndroidJUnit4::class)
class ShelfEditingFlowTest {
    companion object {
        @BeforeClass @JvmStatic fun requireTestApplication() {
            assumeTrue(BuildConfig.APPLICATION_ID == "net.monindev.shelfie.validation.filament" || android.os.Build.HARDWARE == "ranchu")
        }
    }

    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private fun launch(document: ShelfDocument? = TestShelves.row()) {
        TestShelves.install(document)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.awaitSaved()
    }
    private fun recreate() { scenario!!.recreate(); compose.awaitSaved() }
    @After fun close() { scenario?.close() }

    private fun viewport(): Pair<SemanticsNodeInteraction, ViewportState> {
        val canvas = compose.onNodeWithTag("shelf-viewport")
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        return canvas to ViewportState(width = bounds.width.toInt(), height = bounds.height.toInt())
    }
    private fun onShelf(x: Float, viewport: ViewportState, document: ShelfDocument = TestShelves.row()): Offset =
        ShelfProjection.project(x, ShelfGeometry.rowY(0, document) + .9f, .15f, viewport, document).let { Offset(it.first, it.second) }

    @Test fun freshInstallStartsWithAnEmptySavedShelf() {
        launch(null)
        compose.onNodeWithText("まだ本がありません").assertExists()
        compose.onNodeWithText("＋ から最初の一冊を追加しましょう").assertExists()
        assertTrue(TestShelves.read().items.isEmpty())
        compose.onNodeWithTag("open-item-list").assertIsNotEnabled()
    }

    @Test fun resizeUndoAndRedoSurviveRecreation() {
        launch()
        compose.onNodeWithTag("shelf-settings").performClick()
        compose.onNodeWithTag("shelf-width").performTextReplacement("120")
        compose.onNodeWithTag("apply-shelf-configuration").performClick(); compose.awaitSaved()
        assertEquals(1200, TestShelves.read().shelf.widthMm)
        compose.onNodeWithContentDescription("元に戻す").performClick(); compose.awaitSaved()
        assertEquals(900, TestShelves.read().shelf.widthMm)
        compose.onNodeWithContentDescription("やり直す").performClick(); compose.awaitSaved()
        recreate()
        assertEquals(1200, TestShelves.read().shelf.widthMm)
        assertEquals(TestShelves.row().items, TestShelves.read().items)
    }

    @Test fun shrinkingThroughBooksCannotBeApplied() {
        launch()
        compose.onNodeWithTag("shelf-settings").performClick()
        compose.onNodeWithTag("shelf-width").performTextReplacement("24")
        compose.onNodeWithTag("apply-shelf-configuration").assertIsNotEnabled()
        compose.onNodeWithText("今の本が収まりません。大きくするか、先に本を動かしてください。").assertExists()
        compose.closeSheet()
        assertEquals(TestShelves.row(), TestShelves.read())
    }

    @Test fun orientationAndArrowMoveSurviveRecreation() {
        launch()
        compose.selectFromList("夜の図書室 1")
        compose.onNodeWithTag("orientation-COVER").performClick(); compose.awaitSaved()
        assertEquals(Orientation.COVER, TestShelves.read().items.first().orientation)
        val before = TestShelves.read().items.first().x
        compose.onNodeWithTag("start-move").performClick()
        repeat(3) { compose.onNodeWithContentDescription("右へ動かす").performClick() }
        compose.onNodeWithTag("confirm-move").assertIsEnabled().performClick(); compose.awaitSaved()
        assertEquals(before + .3f, TestShelves.read().items.first().x, .0001f)
        recreate()
        assertEquals(Orientation.COVER, TestShelves.read().items.first().orientation)
        assertEquals(before + .3f, TestShelves.read().items.first().x, .0001f)
    }

    @Test fun stackingAndTakingOneBookOutKeepEveryBook() {
        launch()
        compose.openDetail("夜の図書室 1")
        compose.onNodeWithTag("stack-onto-book-2").performScrollTo().performClick(); compose.awaitSaved()
        assertEquals(listOf(TestShelves.id(2), TestShelves.id(1)), TestShelves.read().items.first { it.id == "book-2" }.bookIds)
        compose.onNodeWithTag("orientation-FLAT").assertExists()
        compose.onNodeWithTag("unstack-${TestShelves.id(1)}").performScrollTo().performClick(); compose.awaitSaved()
        assertEquals(6, TestShelves.read().items.sumOf { it.bookIds.size })
        compose.closeSheet()
        compose.onNodeWithContentDescription("元に戻す").performClick(); compose.awaitSaved()
        assertEquals(listOf(TestShelves.id(2), TestShelves.id(1)), TestShelves.read().items.first { it.id == "book-2" }.bookIds)
    }

    @Test fun removalCanBeUndoneFromTheMessage() {
        launch()
        compose.selectFromList("星の手紙 1")
        compose.onNodeWithTag("remove-item").performClick(); compose.awaitSaved()
        assertEquals(5, TestShelves.read().items.size)
        assertEquals(6, TestShelves.read().books.size)
        compose.onNodeWithText("元に戻す", substring = false).performClick(); compose.awaitSaved()
        assertEquals(TestShelves.row(), TestShelves.read())
    }

    @Test fun cancelledDragDoesNotChangeOrSaveThePlacement() {
        launch()
        compose.selectFromList("夜の図書室 1")
        val (canvas, viewport) = viewport()
        canvas.performTouchInput {
            down(onShelf(-3.5f, viewport))
            moveTo(onShelf(-2.7f, viewport), delayMillis = 200)
        }
        compose.onNodeWithText("ここに置けます").assertExists()
        canvas.performTouchInput { cancel() }
        compose.onNodeWithTag("placement-guide").assertDoesNotExist()
        recreate()
        assertEquals(TestShelves.row(), TestShelves.read())
    }

    @Test fun secondFingerCancelsDragWithoutSaving() {
        launch()
        compose.selectFromList("夜の図書室 1")
        val (canvas, viewport) = viewport()
        canvas.performTouchInput {
            down(0, onShelf(-3.5f, viewport))
            moveTo(0, onShelf(-2.7f, viewport), delayMillis = 200)
        }
        compose.onNodeWithTag("placement-guide").assertIsDisplayed()
        canvas.performTouchInput { down(1, Offset(viewport.width * .7f, viewport.height * .7f)) }
        compose.onNodeWithTag("placement-guide").assertDoesNotExist()
        canvas.performTouchInput { up(1); up(0) }
        compose.awaitSaved()
        assertEquals(TestShelves.row(), TestShelves.read())
    }

    @Test fun tapPlacementShowsTheDestinationBeforeConfirming() {
        launch()
        compose.selectFromList("夜の図書室 1")
        compose.onNodeWithTag("start-move").performClick()
        val (canvas, viewport) = viewport()
        canvas.performTouchInput { click(onShelf(-.7f, viewport)) }
        compose.onNodeWithTag("placement-guide").assertIsDisplayed()
        compose.onNodeWithTag("confirm-move").assertIsEnabled().performClick(); compose.awaitSaved()
        compose.onNodeWithTag("placement-guide").assertDoesNotExist()
        assertEquals(-.7f, TestShelves.read().items.first().x, .15f)
    }

    @Test fun denseSpinesOfferCandidatesAndSelectTheIntendedBook() {
        launch()
        val (canvas, viewport) = viewport()
        canvas.performTouchInput { click(onShelf((2f + 2.3f) / 2f, viewport)) }
        compose.onNodeWithText("どれを選びますか？").assertIsDisplayed()
        compose.onNodeWithText("星の手紙 1").assertExists()
        compose.onNodeWithText("旅の余白 1").performClick()
        compose.onNodeWithText("どれを選びますか？").assertDoesNotExist()
        compose.onNode(hasText("旅の余白 1") and hasAnyAncestor(hasTestTag("selection-panel"))).assertIsDisplayed()
    }

    @Test fun propLimitIsVisibleAndUndoFreesOnePlace() {
        launch(ShelfDocument(shelf = ShelfSpec(1800, 400, 300), items = emptyList()))
        repeat(MAX_PROPS) {
            compose.onNodeWithTag("open-props").performClick()
            compose.onNodeWithTag("prop-pebble").performClick(); compose.awaitSaved()
            compose.onNodeWithContentDescription("選択を解除").performClick()
        }
        compose.onNodeWithTag("open-props").performClick()
        compose.onNodeWithText("小物は${MAX_PROPS}個までです。どれかを外すと追加できます。").assertExists()
        compose.onNodeWithTag("prop-vase").assertIsNotEnabled()
        compose.closeSheet()
        compose.onNodeWithContentDescription("元に戻す").performClick(); compose.awaitSaved()
        compose.onNodeWithTag("open-props").performClick()
        compose.onNodeWithTag("prop-vase").assertIsEnabled()
        assertEquals(MAX_PROPS - 1, TestShelves.read().items.size)
    }

    @Test fun fullShelfSwapsTheChosenBookAndOneUndoRestoresBoth() {
        val original = ShelfDocument(books = TestShelves.books, items = listOf(
            ShelfItem("left", listOf(TestShelves.id(3)), row = 0, x = -1.06f, orientation = Orientation.COVER),
            ShelfItem("right", listOf(TestShelves.id(4)), row = 0, x = .69f, orientation = Orientation.COVER)))
        launch(original)
        compose.onNodeWithTag("add-book").performClick()
        compose.onNodeWithText("棚から外した本").assertExists()
        compose.onNodeWithTag("book-${TestShelves.id(6)}").performScrollTo().performClick()
        compose.onNodeWithTag("swap-sheet").assertExists()
        compose.onNodeWithTag("exchange-right").performClick(); compose.awaitSaved()
        assertEquals(original.items.first(), TestShelves.read().items.first())
        assertEquals(listOf(TestShelves.id(6)), TestShelves.read().items.last().bookIds)
        compose.onNodeWithContentDescription("元に戻す").performClick(); compose.awaitSaved()
        assertEquals(original, TestShelves.read())
        recreate()
        assertEquals(original, TestShelves.read())
    }

    @Test fun measuredThicknessThatOverlapsIsRejectedWithoutClosingTheDialog() {
        val book = BookInfo("manual-width", "厚みを測る本", "著者", 128, 188, 240, measuredThicknessMm = 24f)
        val next = book.copy(id = "manual-neighbor", title = "隣の本")
        val document = ShelfDocument(shelf = ShelfSpec(), books = listOf(book, next),
            items = listOf(ShelfItem("first", listOf(book.id), row = 0, x = 0f), ShelfItem("next", listOf(next.id), row = 0, x = .25f)))
        launch(document)
        compose.openDetail("厚みを測る本")
        compose.onNodeWithText("四六判", substring = true).assertExists()
        compose.onNodeWithTag("measure-${book.id}").performScrollTo().performClick()
        compose.onNodeWithTag("measured-thickness").performTextReplacement("40")
        compose.onNodeWithTag("apply-book-size").performClick()
        compose.onNodeWithText("このサイズでは棚や隣の本と重なります。本を動かしてから変更してください。").assertExists()
        assertEquals(document, TestShelves.read())
        compose.onNodeWithTag("measured-thickness").performTextReplacement("12")
        compose.onNodeWithTag("dimension-pages").performTextReplacement("400")
        compose.onNodeWithText("空欄なら自動計算: 20.6 mm", substring = true).assertExists()
        compose.onNodeWithTag("apply-book-size").performClick(); compose.awaitSaved()
        assertEquals(12f, TestShelves.read().books.first { it.id == book.id }.thicknessMm, .0001f)
        assertEquals(400, TestShelves.read().books.first { it.id == book.id }.pageCount)
    }
}
