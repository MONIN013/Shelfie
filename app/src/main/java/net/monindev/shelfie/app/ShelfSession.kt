package net.monindev.shelfie.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import net.monindev.shelfie.core.*
import net.monindev.shelfie.render.ViewportState

internal enum class StorageProblem { UNREADABLE, UNWRITABLE }
internal enum class EditResult { APPLIED, REJECTED, BLOCKED }

/**
 * Owns the editing history and the device copy of the shelf. Only committed documents are
 * written; selection, previews and camera state stay in memory.
 */
internal class ShelfSession(private val store: ShelfStore, private val scope: CoroutineScope) {
    val editor = ShelfEditor()
    var document by mutableStateOf(editor.document); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var revision by mutableIntStateOf(0); private set
    var loaded by mutableStateOf(false); private set
    var problem by mutableStateOf<StorageProblem?>(null); private set
    private var queuedSaving by mutableStateOf(false)
    var recovering by mutableStateOf(false); private set
    private val saves = Channel<ShelfDocument>(Channel.CONFLATED)

    var viewport by mutableStateOf(ViewportState())
    var preview by mutableStateOf<PlacementPreview?>(null)
    var moving by mutableStateOf(false)
    /** Lets other screens open the shelf settings, e.g. when a book is taller than the shelf. */
    var settingsRequested by mutableStateOf(false)

    val saving: Boolean get() = queuedSaving || recovering
    val editable: Boolean get() = loaded && problem == null && !recovering
    val canUndo: Boolean get() { revision; document; return editor.canUndo }
    val canRedo: Boolean get() { revision; document; return editor.canRedo }
    val selected: ShelfItem? get() = document.items.firstOrNull { it.id == selectedId }

    suspend fun load() {
        try {
            val restored = store.read()
            if (restored != null) {
                editor.replace(restored)
                document = editor.document
            } else enqueue()
        } catch (_: Exception) {
            problem = StorageProblem.UNREADABLE
        }
        loaded = true
    }

    suspend fun writeQueuedSaves() {
        for (next in saves) {
            try { store.write(next) }
            catch (_: Exception) { problem = StorageProblem.UNWRITABLE }
            finally { queuedSaving = next != document }
        }
    }

    private fun enqueue() {
        queuedSaving = true
        saves.trySend(document)
    }

    fun select(id: String?) {
        editor.select(id)
        selectedId = editor.selectedId
        preview = null
        moving = false
    }

    fun edit(action: (ShelfEditor) -> Boolean): EditResult {
        if (!editable) return EditResult.BLOCKED
        if (!action(editor)) return EditResult.REJECTED
        document = editor.document
        selectedId = editor.selectedId
        revision++
        preview = null
        enqueue()
        return EditResult.APPLIED
    }

    fun resetCamera() { viewport = ViewportState(width = viewport.width, height = viewport.height) }

    /** Retries the failed operation. The edited shelf stays in memory until a write succeeds. */
    fun retry(onFailure: () -> Unit) {
        if (saving) return
        recovering = true
        scope.launch {
            try {
                if (problem == StorageProblem.UNREADABLE) {
                    val restored = requireNotNull(store.read())
                    editor.replace(restored)
                    document = editor.document
                    selectedId = null
                    revision++
                } else store.write(document)
                problem = null
            } catch (_: Exception) {
                onFailure()
            } finally {
                recovering = false
            }
        }
    }

    /** Replaces an unreadable file with an empty shelf. This cannot be undone. */
    fun reset(onFailure: () -> Unit) {
        if (saving) return
        recovering = true
        scope.launch {
            try {
                val fresh = ShelfDocument(items = emptyList())
                store.write(fresh)
                editor.replace(fresh)
                document = fresh
                selectedId = null
                problem = null
                revision++
            } catch (_: Exception) {
                onFailure()
            } finally {
                recovering = false
            }
        }
    }
}
