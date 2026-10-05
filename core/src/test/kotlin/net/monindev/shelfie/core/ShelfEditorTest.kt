package net.monindev.shelfie.core

import org.junit.Assert.*
import org.junit.Test

class ShelfEditorTest {
    private fun book(id: String, x: Float, row: Int = 0, orientation: Orientation = Orientation.SPINE) =
        ShelfItem(id, listOf(id), row = row, x = x, orientation = orientation)
    private fun document(vararg items: ShelfItem) = ShelfDocument(shelf = ShelfSpec(1000, 400, 300), items = items.toList(), books = TestBooks.all)

    @Test fun invalidMovesDoNotCreateHistoryOrDisplaceNeighbors() {
        val original = document(book("manual-b01", -2f), book("manual-b02", 0f))
        val editor = ShelfEditor(original)
        assertFalse(editor.move("manual-b01", -1, -2f))
        assertFalse(editor.move("manual-b01", 3, -2f))
        assertFalse(editor.move("manual-b01", 0, Float.NaN))
        assertFalse(editor.move("manual-b01", 0, Float.POSITIVE_INFINITY))
        assertFalse(editor.move("manual-b01", 0, 5f))
        assertFalse(editor.move("manual-b01", 0, 0f))
        assertFalse(editor.move("missing", 0, 1f))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test fun orientationGrowthMustFitWithoutMovingNeighbors() {
        val original = document(book("manual-b01", 0f), book("manual-b02", 0.3f))
        val editor = ShelfEditor(original)
        assertFalse(editor.orient("manual-b01", Orientation.COVER))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test fun undoRedoAndBranchingAreAtomic() {
        val original = document(book("manual-b01", -2f))
        val editor = ShelfEditor(original)
        editor.select("manual-b01")
        assertTrue(editor.move("manual-b01", 0, 1f))
        val moved = editor.document
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertTrue(editor.redo())
        assertEquals(moved, editor.document)
        assertTrue(editor.undo())
        assertFalse(editor.move("manual-b01", 9, 0f))
        assertTrue(editor.canRedo)
        assertTrue(editor.remove("manual-b01"))
        assertNull(editor.selectedId)
        assertFalse(editor.canRedo)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
    }

    @Test fun stackingAndUnstackPreserveBooks() {
        val editor = ShelfEditor(document(book("manual-b01", -3f), book("manual-b02", -1f), book("manual-b03", 1f), book("manual-b04", 3f)))
        assertTrue(editor.stack("manual-b01", "manual-b02"))
        assertTrue(editor.stack("manual-b03", "manual-b02"))
        val stacked = editor.document
        assertEquals(listOf("manual-b02", "manual-b01", "manual-b03"), stacked.items.first { it.id == "manual-b02" }.bookIds)
        assertFalse(editor.orient("manual-b02", Orientation.SPINE))
        assertEquals(stacked, editor.document)
        assertTrue(editor.unstack("manual-b02", "manual-b01"))
        assertEquals(setOf("manual-b01", "manual-b02", "manual-b03", "manual-b04"), editor.document.items.flatMap { it.bookIds }.toSet())
        assertTrue(ShelfGeometry.isValid(editor.document))
        assertTrue(editor.undo())
        assertEquals(stacked, editor.document)
    }

    @Test fun addRejectsDuplicatesUnknownAssetsAndExcessProps() {
        val editor = ShelfEditor(document())
        assertTrue(editor.addBook("manual-b01"))
        assertFalse(editor.addBook("manual-b01"))
        assertFalse(editor.addBook("no-book"))
        assertFalse(editor.addProp("no-prop"))
        repeat(6) { assertTrue(editor.addProp("pebble")) }
        assertFalse(editor.addProp("vase"))
    }

    @Test fun shelfReplacementCanBeUndoneWithoutLosingAnEditedLayout() {
        val original = document(book("manual-b01", 2f))
        val editor = ShelfEditor(original)
        assertTrue(editor.replaceWithUndo(TestBooks.starter()))
        assertEquals(TestBooks.starter(), editor.document)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertTrue(editor.redo())
        assertEquals(TestBooks.starter(), editor.document)
    }

    @Test fun snappingUsesEntryExitHysteresisAndEqualSpacing() {
        val editor = ShelfEditor(document(book("manual-b01", 0f), book("manual-b02", -2f), book("manual-b03", 2f)))
        val edge = editor.preview("manual-b01", 0, -4.9f)
        assertTrue(edge.valid)
        assertTrue(edge.snapped)
        assertEquals(-4.9546f, edge.x, 0.001f)
        val held = editor.preview("manual-b01", 0, -4.75f, edge.x)
        assertTrue(held.snapped)
        assertEquals(edge.x, held.x, 0.001f)
        assertFalse(editor.preview("manual-b01", 0, -4.6f, edge.x).snapped)
        val middle = editor.preview("manual-b01", 0, 0.1f)
        assertTrue(middle.valid)
        assertTrue(middle.snapped)
        assertEquals(-.0108f, middle.x, 0.001f)
        // Thin spines can snap out of a collision; a wide cover must still reject it.
        assertTrue(editor.preview("manual-b01", 0, 2f).valid)
        val wideNeighbor = ShelfEditor(document(book("manual-b01", 0f), book("manual-b03", 2f, orientation = Orientation.COVER)))
        assertFalse(wideNeighbor.preview("manual-b01", 0, 2f).valid)
        assertFalse(editor.preview("manual-b01", 0, Float.NaN).valid)
        assertFalse(editor.canUndo)
    }

    @Test fun codecRoundTripsAndRejectsCorruptUnknownSchemaAndDuplicates() {
        val original = TestBooks.starter()
        assertEquals(original, ShelfCodec.decode(ShelfCodec.encode(original)))
        assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode("{broken") }
        val future = ShelfCodec.encode(original).replace("\"schemaVersion\": 4", "\"schemaVersion\": 99")
        val error = assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode(future) }
        assertTrue(error.message!!.contains("schema version"))
        assertFalse(ShelfGeometry.isValid(document(book("manual-b01", 0f), book("manual-b01", 2f))))
        assertFalse(ShelfGeometry.isValid(document(book("manual-b01", 0f), book("manual-b01", 2f).copy(id = "different"))))
        val editor = ShelfEditor(document(book("manual-b01", 0f)))
        editor.move("manual-b01", 0, 1f)
        val current = editor.document
        assertFalse(editor.replace(current.copy(schemaVersion = 9)))
        assertTrue(editor.canUndo)
        assertEquals(current, editor.document)
        assertTrue(editor.replace(original))
        assertFalse(editor.canUndo)
    }
}
