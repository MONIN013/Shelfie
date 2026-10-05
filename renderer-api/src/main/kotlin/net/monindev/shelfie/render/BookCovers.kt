package net.monindev.shelfie.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.monindev.shelfie.core.BookInfo

/** Bounded public cover cache. No authentication is ever forwarded to the image host. */
object BookCovers {
    private val cacheWrite = Mutex()
    fun file(context: Context, book: BookInfo): File? {
        val url=book.coverUrl?:return null
        val id=Regex("https://covers\\.openlibrary\\.org/b/id/([0-9]+)-M\\.jpg").matchEntire(url)?.groupValues?.get(1)
            ?: Regex("https://cover\\.openbd\\.jp/([0-9]{13})\\.jpg").matchEntire(url)?.groupValues?.get(1)?.let { "openbd-$it" } ?: return null
        return File(context.cacheDir,"book-covers/$id.jpg")
    }
    fun bitmap(context: Context, book: BookInfo): Bitmap? = file(context,book)?.takeIf {it.isFile}?.let { BitmapFactory.decodeFile(it.path) }
    suspend fun fetch(context: Context, book: BookInfo): Boolean = withContext(Dispatchers.IO) {
        val file=file(context,book)?:return@withContext false
        if(file.isFile) return@withContext true
        val connection=URL(book.coverUrl+if(book.coverUrl!!.contains("openlibrary.org")) "?default=false" else "").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=7000;connection.readTimeout=7000;connection.instanceFollowRedirects=false
            if(connection.responseCode!=200) return@withContext false
            val data=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val bytes=ByteArray(8192)
                while(true){val n=input.read(bytes);if(n<0)break;if(output.size()+n>2_097_152)return@withContext false;output.write(bytes,0,n)}
                output.toByteArray()
            }
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            if(bounds.outWidth !in 1..2048||bounds.outHeight !in 1..2048) return@withContext false
            // The shelf and list can request the same image concurrently. AtomicFile alone has no locking.
            cacheWrite.withLock {
                file.parentFile?.mkdirs()
                val atomic=android.util.AtomicFile(file);val out=atomic.startWrite()
                try{out.write(data);atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}
                var size=0L
                file.parentFile?.listFiles()?.sortedByDescending{it.lastModified()}?.forEach{cached->size+=cached.length();if(size>33_554_432&&cached!=file)cached.delete()}
            }
            true
        } catch (_: java.io.IOException) { false }
        finally {connection.disconnect()}
    }
}
