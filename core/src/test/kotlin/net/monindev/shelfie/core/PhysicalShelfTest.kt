package net.monindev.shelfie.core

import org.junit.Assert.*
import org.junit.Test

class PhysicalShelfTest {
    private val book=BookInfo("manual-measured","実寸の本","著者",128,188,240,measuredThicknessMm=24f)
    private fun shelf()=ShelfDocument(schemaVersion=4,theme="oak",shelf=ShelfSpec(),books=listOf(book),items=listOf(ShelfItem("book",listOf(book.id),row=0,x=0f)))
    @Test fun dimensionsAndMeasuredThicknessSurviveRoundTrip() {
        val document=ShelfCodec.decode(ShelfCodec.encode(shelf()))
        assertEquals(shelf(),document)
        assertEquals(9f,ShelfGeometry.width(document),.0001f)
        assertEquals(.24f,ShelfGeometry.dimensions(document.items.single(),document).width,.0001f)
    }
    @Test fun resizingPreservesBooksAndLocationsAndRejectsAnOverflow() {
        val initial=shelf().copy(items=shelf().items.map{it.copy(x=4f)})
        val editor=ShelfEditor(initial)
        assertTrue(editor.configure(ShelfSpec(1200,400,300),"lavender"))
        assertEquals(initial.items,editor.document.items)
        assertEquals(initial.books,editor.document.books)
        assertFalse(editor.configure(ShelfSpec(360,400,300),"oak"))
        assertEquals(1200,editor.document.shelf.widthMm)
        assertTrue(editor.undo());assertEquals(initial,editor.document)
    }
    @Test fun changingThicknessChecksNeighborsAndCanBeUndone() {
        val other=book.copy(id="manual-neighbor")
        val document=shelf().copy(books=listOf(book,other),items=shelf().items+ShelfItem("next",listOf(other.id),row=0,x=.25f))
        val editor=ShelfEditor(document)
        assertFalse(editor.updateBook(book.copy(measuredThicknessMm=40f)))
        assertTrue(editor.updateBook(book.copy(measuredThicknessMm=12f)))
        assertTrue(editor.undo());assertEquals(document,editor.document)
    }
    @Test fun invalidSizesAndUnsupportedSchemasAreRejected() {
        assertFalse(ShelfGeometry.isValid(shelf().copy(shelf=ShelfSpec(2000,400,300))))
        assertFalse(ShelfGeometry.isValid(shelf().copy(shelf=ShelfSpec(900,120,300))))
        assertFalse(ShelfGeometry.isValid(shelf().copy(schemaVersion=2)))
        assertFalse(ShelfGeometry.isValid(shelf().copy(books=listOf(book.copy(measuredThicknessMm=Float.NaN)))))
        assertEquals(TestBooks.starter(),ShelfCodec.decode(ShelfCodec.encode(TestBooks.starter())))
    }
}
