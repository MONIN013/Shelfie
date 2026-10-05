package net.monindev.shelfie.filament

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import net.monindev.shelfie.core.BookInfo
import net.monindev.shelfie.render.SceneComposer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reuse the authored book geometry while replacing its cover and spine images. */
internal object GeneratedBookAsset {
    private val palette = intArrayOf(0xff544465.toInt(), 0xff315d51.toInt(), 0xff46687f.toInt(), 0xff805448.toInt())

    fun create(assets: AssetManager, book: BookInfo, cover: Bitmap? = null): ByteArray {
        val original = assets.open(SceneComposer.BOOK_MODEL).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buffer.getInt(12)
        val json = JSONObject(String(original,20,jsonLength,Charsets.UTF_8))
        val binaryOffset = 20 + jsonLength + 8
        val binary = ByteArrayOutputStream().apply { write(original,binaryOffset,original.size-binaryOffset) }
        // The binding takes the colour of the cover edge so that boards and spine read as one book.
        val binding = cover?.getPixel(2, cover.height / 2) ?: palette[Math.floorMod(book.id.hashCode(), palette.size)]
        fun linear(component: Int): Double { val c = component / 255.0; return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4) }
        val materials = json.getJSONArray("materials")
        for (m in 0 until materials.length()) {
            val material = materials.getJSONObject(m)
            if (material.optString("name").contains("matte binding")) material.getJSONObject("pbrMetallicRoughness").put("baseColorFactor",
                org.json.JSONArray(listOf(linear(Color.red(binding)), linear(Color.green(binding)), linear(Color.blue(binding)), 1.0)))
        }
        val views = json.getJSONArray("bufferViews")
        val images = json.getJSONArray("images")
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            val spine = image.optString("name").contains("spine", ignoreCase = true)
            val bytes = if (!spine && cover != null) ByteArrayOutputStream().also { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                else texture(book, spine, binding)
            while(binary.size()%4 != 0) binary.write(0)
            val view = views.getJSONObject(image.getInt("bufferView"))
            view.put("byteOffset",binary.size()); view.put("byteLength",bytes.size)
            image.put("mimeType","image/png")
            binary.write(bytes)
        }
        while(binary.size()%4 != 0) binary.write(0)
        json.getJSONArray("buffers").getJSONObject(0).put("byteLength",binary.size())
        // Android's JSON writer escapes '/', but gltfio uses the raw MIME token to select its decoder.
        val encoded = json.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)
        val padded = (encoded.size+3)/4*4
        val out = ByteBuffer.allocate(12+8+padded+8+binary.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546c67);out.putInt(2);out.putInt(out.capacity());out.putInt(padded);out.putInt(0x4e4f534a)
        out.put(encoded);repeat(padded-encoded.size){out.put(32.toByte())}
        out.putInt(binary.size());out.putInt(0x004e4942);out.put(binary.toByteArray())
        return out.array()
    }
    private fun texture(book: BookInfo, spine: Boolean, background: Int): ByteArray {
        val width = if(spine)128 else 512
        val bitmap = Bitmap.createBitmap(width,768,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        // A spine coloured from a pale cover needs dark lettering.
        val light = Color.luminance(background) > .45f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {color=if(light) Color.rgb(40,36,48) else Color.rgb(234,219,188);strokeWidth=2f}
        canvas.drawLine(24f,50f,width-24f,50f,paint)
        canvas.drawLine(24f,698f,width-24f,698f,paint)
        if(spine) {
            paint.textSize=42f;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("serif",Typeface.NORMAL)
            val title=book.title.take(11)+(if(book.title.length>11)"…" else "")
            title.forEachIndexed { index,c ->canvas.drawText(c.toString(),64f,110f+index*46f,paint) }
        } else {
            val titlePaint=TextPaint(paint).apply {textSize=48f;typeface=Typeface.create("serif",Typeface.NORMAL)}
            val layout=StaticLayout.Builder.obtain(book.title,0,book.title.length,titlePaint,416).setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(7).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save();canvas.translate(48f,110f);layout.draw(canvas);canvas.restore()
            val authorPaint=TextPaint(paint).apply {textSize=24f}
            val author=StaticLayout.Builder.obtain(book.author,0,book.author.length,authorPaint,416).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            canvas.save();canvas.translate(48f,564f);author.draw(canvas);canvas.restore()
        }
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
        return out.toByteArray()
    }
}
