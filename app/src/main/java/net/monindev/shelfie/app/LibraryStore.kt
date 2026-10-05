package net.monindev.shelfie.app

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.monindev.shelfie.core.BookInfo
import net.monindev.shelfie.core.ShelfGeometry

/** A book kept for later. [sourceId] is the public shelf it was found on, if any. */
@Serializable
internal data class SavedBook(val book: BookInfo, val sourceId: String? = null, val addedAt: Long)

/** Device-only lists: the reading pile and favorite public shelves. Neither needs an account. */
@Serializable
internal data class Library(val reading: List<SavedBook> = emptyList(), val favorites: List<String> = emptyList()) {
    fun validationError(): String? {
        if (reading.size > MAX_ENTRIES || favorites.size > MAX_ENTRIES) return "Too many entries"
        if (reading.map { it.book.id }.toSet().size != reading.size || favorites.toSet().size != favorites.size) return "Duplicate entry"
        reading.firstNotNullOfOrNull { ShelfGeometry.bookError(it.book) }?.let { return it }
        if ((reading.mapNotNull { it.sourceId } + favorites).any { !it.matches(POST_ID) }) return "Invalid shelf ID"
        return null
    }

    companion object {
        const val MAX_ENTRIES = 500
        val POST_ID = Regex("[a-f0-9-]{36}")
    }
}

internal class LibraryStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library.json"))
    private val lock = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun read(): Library = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) Library()
            else file.openRead().use { stream ->
                require(stream.channel.size() <= 4_194_304) { "Library file exceeds the supported size" }
                val library = try { json.decodeFromString<Library>(stream.bufferedReader().readText()) }
                    catch (error: SerializationException) { throw IllegalArgumentException("Library file is corrupt", error) }
                require(library.validationError() == null) { library.validationError()!! }
                library
            }
        }
    }

    suspend fun write(library: Library) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(library.validationError() == null) { library.validationError()!! }
            val stream = file.startWrite()
            try {
                stream.write(json.encodeToString(Library.serializer(), library).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
    }
}

/**
 * Edits apply in memory first and are then written atomically. A failed write restores the
 * last saved lists; an unreadable file is never overwritten.
 */
internal class LibrarySession(private val store: LibraryStore, private val scope: CoroutineScope) {
    var library by mutableStateOf(Library()); private set
    var loaded by mutableStateOf(false); private set
    var unreadable by mutableStateOf(false); private set
    private var saved = Library()
    private val writing = Mutex()

    suspend fun load() {
        try { library = store.read(); saved = library } catch (_: Exception) { unreadable = true }
        loaded = true
    }

    fun has(bookId: String) = library.reading.any { it.book.id == bookId }
    fun isFavorite(postId: String) = postId in library.favorites

    fun addReading(book: BookInfo, sourceId: String?, onResult: (Boolean) -> Unit) =
        change(onResult) { it.copy(reading = listOf(SavedBook(book, sourceId, System.currentTimeMillis())) + it.reading.filterNot { entry -> entry.book.id == book.id }) }

    fun removeReading(bookId: String, onResult: (Boolean) -> Unit) =
        change(onResult) { it.copy(reading = it.reading.filterNot { entry -> entry.book.id == bookId }) }

    fun restoreReading(entry: SavedBook, onResult: (Boolean) -> Unit) =
        change(onResult) { library -> library.copy(reading = (library.reading + entry).sortedByDescending { it.addedAt }) }

    /** Forgets a source shelf that is no longer public, without losing the book. */
    fun forgetSource(postId: String) = change({}) { library ->
        library.copy(reading = library.reading.map { if (it.sourceId == postId) it.copy(sourceId = null) else it },
            favorites = library.favorites - postId)
    }

    fun toggleFavorite(postId: String, onResult: (Boolean) -> Unit) =
        change(onResult) { if (postId in it.favorites) it.copy(favorites = it.favorites - postId) else it.copy(favorites = listOf(postId) + it.favorites) }

    private fun change(onResult: (Boolean) -> Unit, transform: (Library) -> Library) {
        if (!loaded || unreadable) { onResult(false); return }
        val next = transform(library)
        if (next.validationError() != null) { onResult(false); return }
        library = next
        scope.launch {
            // Always write the newest lists, so overlapping edits cannot land out of order.
            val ok = writing.withLock {
                val current = library
                current == saved || try { store.write(current); saved = current; true } catch (_: Exception) { false }
            }
            if (!ok) library = saved
            onResult(ok)
        }
    }
}
