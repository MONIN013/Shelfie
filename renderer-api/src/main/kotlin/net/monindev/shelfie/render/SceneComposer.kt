package net.monindev.shelfie.render

import net.monindev.shelfie.core.*
import kotlin.math.*

/** Camera and touch projection share the physical shelf model. */
object ShelfProjection {
    private val tilt = Math.toRadians(8.0)
    private val cosTilt = cos(tilt).toFloat()
    private val sinTilt = sin(tilt).toFloat()

    fun camera(viewport: ViewportState, document: ShelfDocument): CameraState {
        val aspect = viewport.width.coerceAtLeast(1).toFloat() / viewport.height.coerceAtLeast(1)
        val h = ShelfGeometry.clearance(0, document)
        val half = max((h + .7f + ShelfGeometry.depth(document)*sinTilt) / 2,
            (ShelfGeometry.width(document)+.8f) / (2*aspect)) / viewport.zoom.coerceIn(1f, 2.5f)
        val scale = viewport.height.coerceAtLeast(1) / (2f * half)
        val target = floatArrayOf(-viewport.panX / scale, ShelfGeometry.BOTTOM_Y + h/2 + viewport.panY / (scale * cosTilt), 0f)
        return CameraState(floatArrayOf(target[0], target[1] + 24f * sinTilt, 24f * cosTilt), target, half, aspect)
    }

    fun project(x: Float, y: Float, z: Float, viewport: ViewportState, document: ShelfDocument): Pair<Float, Float> {
        val camera = camera(viewport, document)
        val scale = viewport.height.coerceAtLeast(1) / (2f * camera.verticalHalfExtent)
        return Pair(viewport.width / 2f + (x - camera.target[0]) * scale,
            viewport.height / 2f - ((y - camera.target[1]) * cosTilt - z * sinTilt) * scale)
    }

    fun screenToShelf(xPx: Float, yPx: Float, row: Int, viewport: ViewportState, document: ShelfDocument): Float {
        val camera = camera(viewport, document)
        val scale = viewport.height.coerceAtLeast(1) / (2f * camera.verticalHalfExtent)
        return camera.target[0] + (xPx - viewport.width / 2f) / scale
    }

    fun nearestRow(yPx: Float, viewport: ViewportState, document: ShelfDocument): Int = (0 until ShelfGeometry.rows(document)).minBy { row ->
        abs(project(0f, ShelfGeometry.rowY(row, document) + min(0.8f, ShelfGeometry.clearance(row, document) / 2), 0.15f, viewport, document).second - yPx)
    }

    fun pick(xPx: Float, yPx: Float, document: ShelfDocument, viewport: ViewportState, minimumTouchTargetPx: Float = 48f): List<String> {
        val scale = viewport.height.coerceAtLeast(1) / (2f * camera(viewport, document).verticalHalfExtent)
        return document.items.mapNotNull { item ->
            val dimensions = ShelfGeometry.dimensions(item, document)
            val (cx, cy) = project(item.x, ShelfGeometry.rowY(item.row, document) + dimensions.height / 2f,
                ShelfCoordinates.centerZ(item, document), viewport, document)
            val halfW = max(minimumTouchTargetPx / 2f, dimensions.width * scale / 2f)
            val halfH = max(minimumTouchTargetPx / 2f, (dimensions.height * cosTilt + dimensions.depth * sinTilt) * scale / 2f)
            if (abs(cx - xPx) <= halfW && abs(cy - yPx) <= halfH) item.id to abs(cx - xPx) else null
        }.sortedBy { it.second }.map { it.first }
    }
}

/** New books share a front edge, so height changes never move a flat book off the shelf. */
private object ShelfCoordinates {
    const val FRONT_Z = 0.8f
    fun centerZ(item: ShelfItem, document: ShelfDocument): Float =
        FRONT_Z - ShelfGeometry.dimensions(item, document).depth / 2
}

object SceneComposer {
    /** Every book shares one model; its cover and spine images come from [RenderInstance.book]. */
    const val BOOK_MODEL = "models/book.glb"

    fun compose(document: ShelfDocument, selectedId: String?, cameraViewport: ViewportState,
                preview: PlacementPreview? = null): RenderScene {
        val instances = mutableListOf<RenderInstance>()
        val w=ShelfGeometry.width(document);val h=ShelfGeometry.clearance(0,document);val d=ShelfGeometry.depth(document)
        val t=.18f;val y=ShelfGeometry.BOTTOM_Y;val z=ShelfCoordinates.FRONT_Z-d/2
        val panel="models/panel-${document.theme}.glb"
        fun panel(id:String,x:Float,py:Float,pz:Float,sx:Float,sy:Float,sz:Float) {instances+=RenderInstance(id,panel,transform(x,py,pz,sx=sx,sy=sy,sz=sz))}
        panel("shelf-floor",0f,y-t/2,z,w+2*t,t,d+.12f)
        panel("shelf-roof",0f,y+h+t/2,z,w+2*t,t,d+.12f)
        panel("shelf-left",-w/2-t/2,y+h/2,z,t,h,d+.12f)
        panel("shelf-right",w/2+t/2,y+h/2,z,t,h,d+.12f)
        panel("shelf-back",0f,y+h/2,ShelfCoordinates.FRONT_Z-d-.04f,w,h,.08f)
        for (stored in document.items) {
            val item = if (stored.id == selectedId && preview != null) stored.copy(row = preview.row, x = preview.x) else stored
            val y = ShelfGeometry.rowY(item.row, document)
            if (item.propId != null) {
                instances += RenderInstance(item.id, "models/prop-${item.propId}.glb", transform(item.x, y, ShelfCoordinates.centerZ(item, document)))
            } else {
                var stackHeight = 0f
                item.bookIds.forEachIndexed { index, id ->
                    val size = ShelfGeometry.dimensions(item.copy(bookIds = listOf(id), orientation = Orientation.COVER), document)
                    val scaleX = size.width / 1.2f
                    val scaleY = size.height / 1.8f
                    val scaleZ = size.depth / 0.24f
                    val centerZ = ShelfCoordinates.centerZ(item, document)
                    val matrix = when (item.orientation) {
                        Orientation.COVER -> transform(item.x, y, centerZ, sx = scaleX, sy = scaleY, sz = scaleZ)
                        Orientation.SPINE -> transform(item.x, y, centerZ, yaw = (PI / 2).toFloat(), sx = scaleX, sy = scaleY, sz = scaleZ)
                        Orientation.FLAT -> transform(item.x, y + stackHeight + size.depth / 2,
                            ShelfCoordinates.FRONT_Z,
                            pitch = (-PI / 2).toFloat(), sx = scaleX, sy = scaleY, sz = scaleZ)
                    }
                    stackHeight += size.depth
                    instances += RenderInstance(if (index == 0) item.id else "${item.id}::$id", BOOK_MODEL, matrix, document.book(id))
                }
            }
            if (item.id == selectedId) {
                instances += RenderInstance("selection", "models/marker-${if (preview?.valid != false) "valid" else "invalid"}.glb",
                    transform(item.x, y + 0.018f, ShelfCoordinates.centerZ(item, document),
                        sx = ShelfGeometry.dimensions(item, document).width + 0.08f,
                        sz = ShelfGeometry.dimensions(item, document).depth + 0.08f))
            }
        }
        return RenderScene(instances, ShelfProjection.camera(cameraViewport, document), selectedId)
    }

    private fun transform(x: Float = 0f, y: Float = 0f, z: Float = 0f, yaw: Float = 0f,
                          pitch: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): FloatArray {
        val cy = cos(yaw); val snY = sin(yaw); val cx = cos(pitch); val sn = sin(pitch)
        return floatArrayOf(cy*sx, 0f, -snY*sx, 0f, snY*sn*sy, cx*sy, cy*sn*sy, 0f,
            snY*cx*sz, -sn*sz, cy*cx*sz, 0f, x,y,z,1f)
    }
}
