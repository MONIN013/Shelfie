@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.monindev.shelfie.core.PhotoRegion
import net.monindev.shelfie.render.BookPhotos

internal enum class BookFace { SPINE, COVER }

/** A face being cut from a stored photo. Corners are normalized, top-left first, clockwise. */
internal data class CropRequest(val bookId: String, val face: BookFace, val image: String, val corners: List<Float>)

/** Stores picked photos on the device under their SHA-256 name; the book data only refers to that name. */
internal object PhotoLibrary {
    private const val MAX_EDGE = 4096

    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val decoded = requireNotNull(resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        })
        // Camera photos are often stored sideways with an EXIF rotation.
        val rotation = resolver.openInputStream(uri).use { stream ->
            when (stream?.let { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        val upright = if (rotation == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true).also { decoded.recycle() }
        val bytes = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        upright.recycle()
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
        val file = File(BookPhotos.directory(context).apply { mkdirs() }, name)
        if (!file.isFile) {
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(bytes); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
        }
        name
    }

    /** A centered starting frame with the face's proportions, which the person then fits to the book. */
    fun initialCorners(context: Context, image: String, face: BookFace, widthMm: Int, heightMm: Int, thicknessMm: Float): List<Float> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(BookPhotos.directory(context), image).path, bounds)
        val aspect = (if (face == BookFace.SPINE) thicknessMm else widthMm.toFloat()) / heightMm
        val photoAspect = bounds.outWidth.toFloat() / bounds.outHeight.coerceAtLeast(1)
        val h = .6f
        val w = (h * aspect / photoAspect).coerceIn(.04f, .9f)
        val l = .5f - w / 2; val t = .5f - h / 2
        return listOf(l, t, l + w, t, l + w, t + h, l, t + h)
    }
}

private fun loadForDisplay(context: Context, image: String): Bitmap? {
    val file = File(BookPhotos.directory(context), image)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun warp(source: Bitmap, corners: List<Float>, face: BookFace): Bitmap? {
    val width = if (face == BookFace.SPINE) 64 else 256
    val result = Bitmap.createBitmap(width, 384, Bitmap.Config.ARGB_8888)
    val from = corners.mapIndexed { i, v -> v * if (i % 2 == 0) source.width else source.height }.toFloatArray()
    val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), 384f, 0f, 384f)
    val matrix = Matrix()
    if (!matrix.setPolyToPoly(from, 0, to, 0, 4)) { result.recycle(); return null }
    AndroidCanvas(result).drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return result
}

/**
 * Fits four corners to a book face in a photo. Pinch to zoom and drag the background to pan;
 * drag a corner to move it. The preview shows the face as it will appear on the shelf.
 */
@Composable
internal fun PhotoCropScreen(request: CropRequest, title: String, onPickAnother: () -> Unit, onSave: (PhotoRegion) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val source by produceState<Bitmap?>(null, request.image) { value = withContext(Dispatchers.IO) { loadForDisplay(context, request.image) } }
    var corners by remember(request) { mutableStateOf(request.corners) }
    var scale by remember(request.image) { mutableFloatStateOf(1f) }
    var pan by remember(request.image) { mutableStateOf(Offset.Zero) }
    val region = PhotoRegion(request.image, corners)
    val preview by produceState<ImageBitmap?>(null, source, corners) {
        val bitmap = source ?: return@produceState
        value = withContext(Dispatchers.Default) { warp(bitmap, corners, request.face)?.asImageBitmap() }
    }
    val accent = MaterialTheme.colorScheme.primary
    BackHandler(onBack = onCancel)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (request.face == BookFace.SPINE) "背表紙を切り出す" else "表紙を切り出す") },
                subtitle = { Text(title, maxLines = 1) },
                navigationIcon = { TipIconButton(R.drawable.ic_close, "やめる", onClick = onCancel) },
                actions = { TextButton(onClick = onPickAnother, modifier = Modifier.testTag("pick-another-photo")) { Text("別の写真") } })
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.height(96.dp).aspectRatio(if (request.face == BookFace.SPINE) 1f / 6f else 2f / 3f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        preview?.let { Image(it, "切り出した画像のプレビュー", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    }
                    Text(if (region.isValid()) "4つの角を本の角に合わせてください。二本指で拡大できます。"
                        else "角が交差しています。左上・右上・右下・左下の順になるよう合わせてください。",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = if (region.isValid()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    Button(onClick = { onSave(region) }, enabled = region.isValid(), shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.testTag("save-crop")) { Text("保存") }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().clipToBounds().background(Color.Black)) {
            val bitmap = source
            if (bitmap == null) { PageLoading(Modifier.align(Alignment.Center)); return@BoxWithConstraints }
            val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
            val fit = min(boxW / bitmap.width, boxH / bitmap.height)
            val w = bitmap.width * fit; val h = bitmap.height * fit
            val left = (boxW - w) / 2; val top = (boxH - h) / 2
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            fun point(i: Int) = Offset(left + corners[i * 2] * w, top + corners[i * 2 + 1] * h)
            Box(Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y
            }) {
                Image(image, "選んだ写真", Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { h.toDp() }))
                Canvas(Modifier.fillMaxSize()) {
                    val path = Path().apply { moveTo(point(0).x, point(0).y); (1..3).forEach { lineTo(point(it).x, point(it).y) }; close() }
                    // A dark edge under a white line stays visible on both pale and dark spines.
                    drawPath(path, accent.copy(alpha = .22f))
                    drawPath(path, Color.Black.copy(alpha = .55f), style = Stroke(4.dp.toPx() / scale))
                    drawPath(path, Color.White, style = Stroke(1.5.dp.toPx() / scale))
                }
                val handle = 44.dp / scale
                val labels = listOf("左上", "右上", "右下", "左下")
                repeat(4) { i ->
                    val p = point(i)
                    val handlePx = with(density) { handle.toPx() }
                    Box(Modifier.offset { IntOffset((p.x - handlePx / 2).roundToInt(), (p.y - handlePx / 2).roundToInt()) }.size(handle)
                        .semantics { contentDescription = "${labels[i]}の角" }.testTag("crop-corner-$i"), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(handle * .45f).border(2.dp / scale, Color.White, CircleShape).background(accent.copy(alpha = .85f), CircleShape))
                    }
                }
            }
            // One gesture layer: a touch near a corner moves the nearest corner, since corners of a thin
            // spine overlap; elsewhere one finger pans and two fingers zoom around their centre.
            // Screen position = photo position × scale + pan.
            Box(Modifier.fillMaxSize().pointerInput(w, h, left, top) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val local = (down.position - pan) / scale
                    val nearest = (0..3).minBy { (point(it) - local).getDistance() }
                    val grabbed = nearest.takeIf { (point(it) - local).getDistance() <= 36.dp.toPx() / scale }
                    do {
                        val event = awaitPointerEvent()
                        if (grabbed != null) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = change.positionChange() / scale
                            corners = corners.toMutableList().also {
                                it[grabbed * 2] = (it[grabbed * 2] + delta.x / w).coerceIn(0f, 1f)
                                it[grabbed * 2 + 1] = (it[grabbed * 2 + 1] + delta.y / h).coerceIn(0f, 1f)
                            }
                        } else {
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()
                            val next = (scale * zoom).coerceIn(1f, 10f)
                            if (centroid.isSpecified) pan = centroid - (centroid - pan) * (next / scale) + event.calculatePan()
                            scale = next
                            if (scale <= 1f) pan = Offset.Zero
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
        }
    }
}
