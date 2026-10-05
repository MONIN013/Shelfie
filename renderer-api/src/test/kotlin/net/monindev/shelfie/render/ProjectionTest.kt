package net.monindev.shelfie.render

import net.monindev.shelfie.core.*
import org.junit.Assert.*
import org.junit.Test

class ProjectionTest {
    @Test fun projectedCoordinatesRoundTripAcrossZoomPanAndOrientation() {
        val document = TestBooks.document(
            ShelfItem("stack", listOf("manual-b03", "manual-b02", "manual-b01"), row = 0, x = -0.96f, orientation = Orientation.FLAT),
            ShelfItem("cover", listOf("manual-b05"), row = 0, x = 0.405f, orientation = Orientation.COVER),
            ShelfItem("spine", listOf("manual-b04"), row = 0, x = 1.14f))
        for (viewport in listOf(ViewportState(width=1080,height=900), ViewportState(2.5f,100f,-45f,900,1080))) {
            for (row in 0 until ShelfGeometry.rows(document)) {
                val (x,y)=ShelfProjection.project(2.3f,ShelfGeometry.rowY(row, document)+0.8f,0.15f,viewport,document)
                assertEquals(2.3f,ShelfProjection.screenToShelf(x,y,row,viewport,document),0.0001f)
                assertEquals(row,ShelfProjection.nearestRow(y,viewport,document))
            }
        }
    }
    @Test fun scenePreservesDocumentAndFlatStackSpacing() {
        val doc=TestBooks.document(ShelfItem("stack", listOf("manual-b01","manual-b02","manual-b03"),row=0,x=0f,orientation=Orientation.FLAT))
        val scene=SceneComposer.compose(doc,"stack",ViewportState(width=1080,height=900))
        val books=scene.instances.filter { it.book != null }
        assertEquals(3,books.size)
        assertEquals((TestBooks.get("manual-b01").thicknessMm + TestBooks.get("manual-b02").thicknessMm) / 200, books[1].transform[13]-books[0].transform[13],0.0001f)
        assertEquals(doc, ShelfCodec.decode(ShelfCodec.encode(doc)))
    }

    @Test fun physicalBookBoundsMatchPlacementAndShareTheFrontEdge() {
        for (orientation in Orientation.entries) {
            for (bookId in listOf("manual-b01", "manual-b04", "manual-b06")) {
                val item = ShelfItem("book", listOf(bookId), row=0, x=.15f, orientation=orientation)
                val document = TestBooks.document(item)
                val scene = SceneComposer.compose(document, null, ViewportState())
                assertEquals("models/panel-lavender.glb", scene.instances.first().assetPath)
                val bounds = bounds(scene.instances.single { it.id == "book" }.transform)
                val size = ShelfGeometry.dimensions(item, document)
                assertEquals(size.width, bounds[1][0] - bounds[0][0], .0001f)
                assertEquals(size.height, bounds[1][1] - bounds[0][1], .0001f)
                assertEquals(size.depth, bounds[1][2] - bounds[0][2], .0001f)
                assertEquals(.25f, bounds[0][1], .0001f)
                assertEquals(.8f, bounds[1][2], .0001f)
            }
        }
    }

    @Test fun unequalFlatBooksTouchVerticallyAndRemainPickableAcrossZoom() {
        val item = ShelfItem("stack", listOf("manual-b03", "manual-b02", "manual-b01", "manual-b06"), row=0, x=0f, orientation=Orientation.FLAT)
        val document = TestBooks.document(item)
        for (viewport in listOf(ViewportState(width=1080,height=900), ViewportState(2.5f,100f,-45f,900,1080))) {
            val books = SceneComposer.compose(document, null, viewport).instances.filter { it.id.startsWith("stack") }
            var top = .25f
            for (book in books) {
                val bounds = bounds(book.transform)
                assertEquals(top, bounds[0][1], .0001f)
                top = bounds[1][1]
            }
            assertEquals(.25f + ShelfGeometry.dimensions(item, document).height, top, .0001f)
            val (x,y) = ShelfProjection.project(0f, top, -.2f, viewport, document)
            assertEquals(listOf("stack"), ShelfProjection.pick(x,y,document,viewport))
            assertEquals(0f, ShelfProjection.screenToShelf(x,y,0,viewport,document), .0001f)
            assertEquals(0, ShelfProjection.nearestRow(y,viewport,document))
        }
    }

    /** Normalized GLB book envelope, transformed with the same column-major matrix as Filament. */
    private fun bounds(matrix: FloatArray): List<FloatArray> {
        val points = listOf(-.6f,.6f).flatMap { x -> listOf(0f,1.8f).flatMap { y ->
            listOf(-.12f,.12f).map { z -> FloatArray(3) { i -> matrix[i]*x + matrix[4+i]*y + matrix[8+i]*z + matrix[12+i] } }
        } }
        return listOf(FloatArray(3) { i -> points.minOf { it[i] } }, FloatArray(3) { i -> points.maxOf { it[i] } })
    }
}
