package net.monindev.shelfie.core

import org.junit.Test
import org.junit.Assert.*

class AutomaticDimensionsTest {
    private val book=BookInfo("manual-auto","自動の本","著者",128,188,400)
    @Test fun automaticThicknessHasProvenanceAndManualOverrideCanBeCleared() {
        assertEquals(20.6f,book.thicknessMm,.0001f)
        val automatic=book.copy(automaticThicknessMm=30f,automaticThicknessSource="bibliography")
        assertEquals(30f,automatic.thicknessMm,0f)
        assertEquals("書誌情報",automatic.thicknessBasis)
        assertEquals(12f,automatic.copy(measuredThicknessMm=12f).thicknessMm,0f)
        assertEquals(30f,automatic.copy(measuredThicknessMm=12f).copy(measuredThicknessMm=null).thicknessMm,0f)
    }
    @Test fun autoRegistrationRetainsDimensionsAndCannotExpandThroughNeighbors() {
        val editor=ShelfEditor(ShelfDocument(items=emptyList()))
        assertFalse(editor.registerBook(book.copy(automaticThicknessMm=20f,automaticThicknessSource="photo")))
        assertTrue(editor.registerBook(book.copy(automaticThicknessMm=20f,automaticThicknessSource="bibliography")))
        assertEquals(4,editor.document.schemaVersion)
        assertEquals(ShelfSpec(360,270,270),editor.document.shelf)
        assertTrue(editor.addBook(book.id))
        assertTrue(editor.registerBook(book.copy(id="manual-second",measuredThicknessMm=20f)))
        assertTrue(editor.addBook("manual-second"))
        assertFalse(editor.updateBook(editor.document.book(book.id).copy(measuredThicknessMm=100f)))
        assertEquals(20f,editor.document.book(book.id).thicknessMm,0f)
    }
    @Test fun automaticFieldsRoundTripWithCurrentFormat() {
        val document=ShelfDocument(items=emptyList(),books=listOf(book.copy(automaticThicknessMm=20f,automaticThicknessSource="bibliography")))
        assertTrue(ShelfCodec.encode(document).contains("automaticThickness"))
        assertEquals(document,ShelfCodec.decode(ShelfCodec.encode(document)))
        assertThrows(IllegalArgumentException::class.java) { ShelfCodec.decode(ShelfCodec.encode(document).replace("\"pageCount\"","\"assetPath\": \"models/b01.glb\",\n\"pageCount\"")) }
    }
}
