package net.monindev.shelfie.render

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View

interface ShelfRenderer {
    fun createView(activity: Activity): View
    fun update(scene: RenderScene)
    fun setActive(active: Boolean)
    fun release()
}

/** Renders scenes off screen, one at a time, for lists that must not hold a GPU surface per row. */
interface ShelfSnapshotter {
    /** [onResult] runs on the main thread; it receives null when rendering fails or the snapshotter was released. */
    fun capture(context: Context, scene: RenderScene, width: Int, height: Int, onResult: (Bitmap?) -> Unit)
    fun release()
}

data class RenderInstance(val id: String, val assetPath: String, val transform: FloatArray,
    val book: net.monindev.shelfie.core.BookInfo? = null)
data class CameraState(val eye: FloatArray, val target: FloatArray, val verticalHalfExtent: Float, val aspect: Float)
data class PreviewMarker(val x: Float, val y: Float, val width: Float, val valid: Boolean)
data class RenderScene(
    val instances: List<RenderInstance>,
    val camera: CameraState,
    val selectedId: String? = null,
    val preview: PreviewMarker? = null,
)
data class ViewportState(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val width: Int = 1,
    val height: Int = 1,
)
