package net.monindev.shelfie.app

import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.monindev.shelfie.core.*

/** Registered books in four formats. The device shelf is replaced, so run only on the validation app or an emulator. */
internal object TestShelves {
    private val titles = listOf("夜の図書室 1", "雨と珈琲 1", "小さな庭 1", "星の手紙 1", "旅の余白 1", "青い時間 1")
    private val formats = listOf(105 to 148, 128 to 188, 148 to 210, 182 to 257)
    private val pageCounts = listOf(144, 224, 320, 416, 560, 704)
    val books: List<BookInfo> = titles.mapIndexed { index, title ->
        val format = formats[index % formats.size]
        BookInfo("manual-b0${index + 1}", title, "著者", format.first, format.second, pageCounts[index])
    }
    fun id(number: Int) = "manual-b0$number"

    /** Six separate books in a row; the third lies flat. */
    fun row() = ShelfDocument(shelf = ShelfSpec(), books = books, items = listOf(-3.5f, -1.5f, .2f, 2f, 2.3f, 3.3f).mapIndexed { index, x ->
        ShelfItem("book-${index + 1}", listOf(id(index + 1)), row = 0, x = x, orientation = if (index == 2) Orientation.FLAT else Orientation.SPINE)
    })

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    fun file(name: String = "shelf.json") = File(context.filesDir, name)
    fun read(): ShelfDocument = ShelfCodec.decode(file().readText())

    /** Replaces the device shelf and the device reading list. */
    fun install(document: ShelfDocument?) {
        AtomicFile(file()).delete()
        AtomicFile(file("library.json")).delete()
        if (document != null) file().writeText(ShelfCodec.encode(document))
    }
}

internal val saved = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "保存済み")

internal fun ComposeTestRule.awaitSaved() {
    waitUntil(30_000) { onAllNodes(hasTestTag("shelf-status") and saved).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

internal fun ComposeTestRule.selectFromList(title: String) {
    onNodeWithTag("open-item-list").performClick()
    waitUntil(10_000) { onAllNodesWithTag("shelf-item-list").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("shelf-item-list").performScrollToNode(hasText(title))
    onNode(hasText(title) and hasAnyAncestor(hasTestTag("shelf-item-list"))).performClick()
    waitUntil(10_000) { onAllNodesWithTag("selection-panel").fetchSemanticsNodes().isNotEmpty() }
}

internal fun ComposeTestRule.openDetail(title: String) {
    selectFromList(title)
    onNodeWithTag("open-detail").performClick()
    waitUntil(10_000) { onAllNodesWithTag("item-detail").fetchSemanticsNodes().isNotEmpty() }
}

internal fun ComposeTestRule.closeSheet() {
    androidx.test.espresso.Espresso.pressBack()
    waitForIdle()
}
