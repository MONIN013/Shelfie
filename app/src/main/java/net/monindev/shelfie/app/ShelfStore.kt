package net.monindev.shelfie.app

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.monindev.shelfie.core.ShelfCodec
import net.monindev.shelfie.core.ShelfDocument

/** Only committed documents enter this store; selection and drag previews are ephemeral. */
internal class ShelfStore(context: Context, filename: String = "shelf.json") {
    private val file = AtomicFile(File(context.filesDir, filename))
    private val lock = Mutex()

    suspend fun read(): ShelfDocument? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
            else file.openRead().use { stream ->
                require(stream.channel.size() <= 1_048_576) { "Shelf file exceeds the supported size" }
                stream.bufferedReader().use { ShelfCodec.decode(it.readText()) }
            }
        }
    }

    suspend fun write(document: ShelfDocument) = withContext(Dispatchers.IO) {
        lock.withLock {
            val bytes = ShelfCodec.encode(document).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}
