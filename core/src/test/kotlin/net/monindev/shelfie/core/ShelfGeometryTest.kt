package net.monindev.shelfie.core

import org.junit.Assert.*
import org.junit.Test

class ShelfGeometryTest {
    private fun book(id: String, x: Float, orientation: Orientation = Orientation.SPINE) =
        ShelfItem(id, listOf(id), row = 0, x = x, orientation = orientation)
    private fun shelf(vararg items: ShelfItem) = ShelfDocument(items = items.toList(), books = TestBooks.all)

    @Test fun starterHasOneRealSizeRowAndVariedUprightHeights() {
        val document = TestBooks.starter()
        assertEquals(4, document.schemaVersion)
        assertEquals(1, ShelfGeometry.rows(document))
        assertEquals(3.6f, ShelfGeometry.width(document), .0001f)
        assertEquals(2.7f, ShelfGeometry.depth(document), .0001f)
        assertEquals(.25f, ShelfGeometry.rowY(0, document), .0001f)
        assertEquals(2.7f, ShelfGeometry.clearance(0, document), .0001f)
        assertEquals(6, document.items.sumOf { it.bookIds.size })
        assertTrue(document.items.filter { it.orientation != Orientation.FLAT }
            .map { ShelfGeometry.dimensions(it, document).height }.toSet().size >= 2)
        assertTrue(ShelfGeometry.validationError(document), ShelfGeometry.isValid(document))
        assertTrue(ShelfEditor().document.items.isEmpty())
        assertEquals(document, ShelfCodec.decode(ShelfCodec.encode(document)))
        assertFalse(ShelfGeometry.isValid(document.copy(items = document.items.map { it.copy(row = 1) })))
        assertFalse(ShelfGeometry.isValid(document.copy(items = document.items.map { it.copy(x = Float.NaN) })))
    }

    @Test fun formatsAndLeafBasedThickness() {
        assertEquals(setOf(105 to 148, 128 to 188, 148 to 210, 182 to 257), TestBooks.all.map { it.widthMm to it.heightMm }.toSet())
        assertEquals(setOf("文庫", "四六判", "A5", "B5"), TestBooks.all.map { it.formatLabel }.toSet())
        assertEquals(6, TestBooks.all.map { it.pageCount }.toSet().size)
        assertEquals(9.08f, TestBooks.get("manual-b01").thicknessMm, .0001f)
        assertEquals(34.28f, TestBooks.get("manual-b06").thicknessMm, .0001f)
        val document = shelf()
        val cover = ShelfGeometry.dimensions(book("manual-b04", 0f, Orientation.COVER), document)
        assertEquals(1.82f, cover.width, .0001f)
        assertEquals(2.57f, cover.height, .0001f)
        assertEquals(.2132f, cover.depth, .0001f)
        val spine = ShelfGeometry.dimensions(book("manual-b04", 0f), document)
        assertEquals(cover.depth, spine.width, .0001f)
        assertEquals(cover.width, spine.depth, .0001f)
    }

    @Test fun fourBooksCanStackWithPhysicalHeightAndDeepestBookFootprint() {
        val editor = ShelfEditor(shelf(book("manual-b01", -.6f, Orientation.FLAT), book("manual-b02", .3f), book("manual-b03", .6f), book("manual-b04", 1f)))
        assertTrue(editor.canStack("manual-b02", "manual-b01"))
        assertFalse(editor.canUndo)
        assertTrue(editor.stack("manual-b02", "manual-b01"))
        assertTrue(editor.stack("manual-b03", "manual-b01"))
        assertTrue(editor.stack("manual-b04", "manual-b01"))
        val item = editor.document.items.single()
        assertEquals(listOf("manual-b01", "manual-b02", "manual-b03", "manual-b04"), item.bookIds)
        val size = ShelfGeometry.dimensions(item, editor.document)
        assertEquals(1.82f, size.width, .0001f)
        assertEquals(.6008f, size.height, .0001f)
        assertEquals(2.57f, size.depth, .0001f)
        assertTrue(editor.undo())
        assertEquals(3, editor.document.items.first { it.id == "manual-b01" }.bookIds.size)
    }

    @Test fun stackThatExceedsClearanceCannotCommitOrCreateHistory() {
        val heavy = TestBooks.all.filter { it.pageCount >= 416 }.take(10)
        val stack = ShelfItem("stack", heavy.take(9).map { it.id }, row = 0, x = -.75f, orientation = Orientation.FLAT)
        val source = book(heavy.last().id, 1f)
        val original = shelf(stack, source)
        assertEquals(2.502f, ShelfGeometry.dimensions(stack, original).height, .0001f)
        val editor = ShelfEditor(original)
        assertFalse(editor.canStack(source.id, stack.id))
        assertFalse(editor.stack(source.id, stack.id))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
        val overflow = original.copy(items = listOf(stack.copy(bookIds = heavy.map { it.id })))
        assertTrue(ShelfGeometry.validationError(overflow)!!.contains("clearance"))
    }

    @Test fun fullShelfRejectsAddWithoutSqueezingAndCanReplaceAtomically() {
        val original = shelf(book("manual-b01", -.8f), book("manual-b02", -.65f))
        val editor = ShelfEditor(original)
        assertFalse(editor.canReplaceItemWithBook("manual-b01", "manual-b06"))
        assertFalse(editor.replaceItemWithBook("manual-b01", "manual-b06"))
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
        assertTrue(editor.canReplaceItemWithBook("manual-b01", "manual-b07"))
        assertFalse(editor.canUndo)
        assertTrue(editor.replaceItemWithBook("manual-b01", "manual-b07"))
        assertEquals(listOf("manual-b07"), editor.document.items.first().bookIds)
        assertEquals(original.items.last(), editor.document.items.last())
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)

        // Two face-out books occupy 3.30 units; another thick spine cannot fit any gap.
        val full = shelf(book("manual-b03", -1.06f, Orientation.COVER), book("manual-b04", .69f, Orientation.COVER))
        val fullEditor = ShelfEditor(full)
        assertFalse(fullEditor.canAddBook("manual-b06"))
        assertFalse(fullEditor.addBook("manual-b06"))
        assertEquals(full, fullEditor.document)
        assertFalse(fullEditor.canUndo)
    }

    @Test fun replacingAStackSwapsItsWholeContentsInOneUndo() {
        val original = shelf(ShelfItem("stack", listOf("manual-b03", "manual-b02", "manual-b01"), row = 0, x = 0f, orientation = Orientation.FLAT))
        val editor = ShelfEditor(original)
        assertTrue(editor.replaceItemWithBook("stack", "manual-b04"))
        val exchanged = editor.document.items.single()
        assertEquals("stack", exchanged.id)
        assertEquals(listOf("manual-b04"), exchanged.bookIds)
        assertEquals(Orientation.FLAT, exchanged.orientation)
        assertEquals(0f, exchanged.x, .0001f)
        assertTrue(editor.undo())
        assertEquals(original, editor.document)
        assertFalse(editor.canUndo)
    }

    @Test fun onlyCurrentExplicitSchemaAndDimensionsAreAccepted() {
        val serialized = ShelfCodec.encode(TestBooks.starter())
        for (version in listOf(1, 2, 3, 99)) {
            assertThrows(IllegalArgumentException::class.java) {
                ShelfCodec.decode(serialized.replace("\"schemaVersion\": 4", "\"schemaVersion\": $version"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode("""{"items":[],"shelf":{"widthMm":360,"heightMm":270,"depthMm":270}}""") }
        assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode("""{"schemaVersion":4,"items":[],"shelf":null}""") }
        assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode("""{"schemaVersion":4,"items":[]}""") }
    }
}
