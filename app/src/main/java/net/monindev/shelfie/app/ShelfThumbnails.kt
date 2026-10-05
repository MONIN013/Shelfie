package net.monindev.shelfie.app

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.monindev.shelfie.core.ShelfDocument
import net.monindev.shelfie.render.BookCovers
import net.monindev.shelfie.render.SceneComposer
import net.monindev.shelfie.render.ViewportState

/**
 * Renders shelves to bitmaps with one off-screen Filament engine, so scrolling lists show the
 * real shelf without a GPU surface per row.
 */
internal class ShelfThumbnails(private val context: Context) {
    private val snapshotter by lazy { createShelfSnapshotter() }
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    suspend fun render(key: String, document: ShelfDocument): Bitmap? {
        cache.get(key)?.let { return it }
        // Downloaded covers must be on disk before the scene is built, or the snapshot shows title covers.
        document.books.filter { book -> document.items.any { book.id in it.bookIds } }.forEach { BookCovers.fetch(context, it) }
        val scene = SceneComposer.compose(document, null, ViewportState(width = WIDTH, height = HEIGHT))
        // Filament objects belong to the main thread, whichever dispatcher resumed after the download.
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                snapshotter.capture(context, scene, WIDTH, HEIGHT) { bitmap ->
                    bitmap?.let { cache.put(key, it) }
                    if (continuation.isActive) continuation.resume(bitmap)
                }
            }
        }
    }

    fun release() = snapshotter.release()

    companion object {
        const val WIDTH = 800
        const val HEIGHT = 500
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShelfImage(thumbnails: ShelfThumbnails, key: String, document: ShelfDocument, description: String, modifier: Modifier = Modifier) {
    val bitmap by produceState(thumbnails.cached(key), key) { if (value == null) value = thumbnails.render(key, document) }
    Box(modifier.fillMaxWidth().aspectRatio(ShelfThumbnails.WIDTH.toFloat() / ShelfThumbnails.HEIGHT).clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: LoadingIndicator()
    }
}
