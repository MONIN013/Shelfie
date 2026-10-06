package net.monindev.shelfie.render

import android.content.Context
import android.graphics.*
import java.io.File
import net.monindev.shelfie.core.PhotoRegion

/** Maps the four chosen corners of a stored photo onto a book face. The photo itself is never altered. */
object BookPhotos {
    private var cachedName: String? = null
    private var cached: Bitmap? = null

    /** Photos the person picked are kept here under their SHA-256 name. */
    fun directory(context: Context): File = File(context.filesDir, "photos")

    @Synchronized fun bitmap(context: Context, region: PhotoRegion?, spine: Boolean = false): Bitmap? {
        if (region == null || !region.isValid()) return null
        if (cachedName != region.image) {
            val file = File(directory(context), region.image)
            val source = if (file.isFile) BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 }) else null
            source ?: return null
            cached?.recycle(); cached = source; cachedName = region.image
        }
        val source = cached ?: return null
        val width = if (spine) 128 else 512
        val result = Bitmap.createBitmap(width, 768, Bitmap.Config.ARGB_8888)
        val from = region.corners.mapIndexed { i, v -> v * if (i % 2 == 0) source.width else source.height }.toFloatArray()
        val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), 768f, 0f, 768f)
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) { result.recycle(); return null }
        Canvas(result).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }
}
