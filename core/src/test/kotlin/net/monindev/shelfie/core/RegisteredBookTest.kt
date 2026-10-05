package net.monindev.shelfie.core

import org.junit.Assert.*
import org.junit.Test

class RegisteredBookTest {
    private val book=BookInfo("manual-hello","自分の一冊","著者",128,188,240)
    @Test fun registeredBookSurvivesSaveAndRemovalWithoutLosingBibliography() {
        val editor=ShelfEditor(ShelfDocument(items=emptyList()))
        assertTrue(editor.registerBook(book));assertTrue(editor.addBook(book.id))
        val restored=ShelfCodec.decode(ShelfCodec.encode(editor.document))
        assertEquals(book,restored.book(book.id));assertEquals(.134f,ShelfGeometry.dimensions(restored.items.single(),restored).width,.0001f)
        assertTrue(editor.remove(editor.document.items.single().id))
        assertEquals(book,editor.document.books.single())
    }
    @Test fun importedBooksObeyDimensionsAndUndo() {
        val editor=ShelfEditor(ShelfDocument(items=emptyList()))
        assertTrue(editor.registerBook(book.copy(heightMm=350)))
        assertFalse(editor.addBook(book.id));assertTrue(editor.undo());assertTrue(editor.document.books.isEmpty())
    }
    @Test fun untrustedMetadataCannotSelectArbitraryIdsOrCoverHosts() {
        val editor=ShelfEditor(ShelfDocument(items=emptyList()))
        assertFalse(editor.registerBook(book.copy(coverUrl="http://127.0.0.1/secret")))
        assertFalse(editor.registerBook(book.copy(id="b01")))
        assertTrue(editor.document.books.isEmpty())
    }
    @Test fun customBooksPreserveExistingPlacement() {
        val editor=ShelfEditor(TestBooks.starter().copy(shelf=ShelfSpec()));val original=editor.document
        assertTrue(editor.registerBook(book));assertTrue(editor.addBook(book.id))
        assertEquals(.134f,ShelfGeometry.dimensions(editor.document.items.last(),editor.document).width,.0001f)
        assertEquals(original.items,editor.document.items.dropLast(1))
        assertEquals(editor.document,ShelfCodec.decode(ShelfCodec.encode(editor.document)))
    }
}
