package net.monindev.shelfie.core

import kotlin.math.abs

/** A mutation is atomic: rejected placement never pushes neighboring objects. */
class ShelfEditor(initial: ShelfDocument = ShelfDocument(items = emptyList())) {
    var document: ShelfDocument = initial
        private set
    var selectedId: String? = null
        private set
    private val past = mutableListOf<ShelfDocument>()
    private val future = mutableListOf<ShelfDocument>()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    init { require(ShelfGeometry.isValid(initial)) { ShelfGeometry.validationError(initial)!! } }

    fun select(id: String?) { selectedId = id?.takeIf { candidate -> document.items.any { it.id == candidate } } }

    private fun commit(next: ShelfDocument): Boolean {
        if (next == document || !ShelfGeometry.isValid(next)) return false
        past += document
        if (past.size > 100) past.removeAt(0)
        future.clear()
        document = next
        select(selectedId)
        return true
    }

    fun move(id: String, row: Int, x: Float): Boolean = commit(document.copy(items = document.items.map {
        if (it.id == id) it.copy(row = row, x = x) else it
    }))

    fun orient(id: String, orientation: Orientation): Boolean = commit(document.copy(items = document.items.map {
        if (it.id == id && it.propId == null) it.copy(orientation = orientation) else it
    }))

    fun remove(id: String): Boolean = commit(document.copy(items = document.items.filterNot { it.id == id }))

    /** Replacing the shelf is one undoable edit. */
    fun replaceWithUndo(next: ShelfDocument): Boolean = commit(next)

    private fun nextId(prefix: String): String = generateSequence(1) { it + 1 }
        .map { "$prefix-$it" }.first { candidate -> document.items.none { it.id == candidate } }

    private fun placementFor(item: ShelfItem, base: List<ShelfItem> = document.items): ShelfDocument? {
        val half = ShelfGeometry.dimensions(item, document).width / 2
        for (row in 0 until ShelfGeometry.rows(document)) {
            val edges = listOf(-ShelfGeometry.width(document) / 2 + half) + base.filter { it.row == row }
                .sortedBy { it.x }.map { it.x + ShelfGeometry.dimensions(it, document).width / 2 + half }
            // Prefer a small gap, but allow an exact fit.
            val candidates = edges.map { it + 0.05f } + edges
            for (x in candidates) {
                val next = document.copy(items = base + item.copy(row = row, x = x))
                if (ShelfGeometry.isValid(next)) return next
            }
        }
        return null
    }

    private fun placeNew(item: ShelfItem, base: List<ShelfItem> = document.items): Boolean {
        val next = placementFor(item, base) ?: return false
        return commit(next).also { if (it) select(item.id) }
    }

    private fun newBook(bookId: String): ShelfItem? {
        if (document.books.none { it.id == bookId } || document.items.any { bookId in it.bookIds }) return null
        return ShelfItem(nextId("book-$bookId"), listOf(bookId), row = 0, x = 0f)
    }

    fun canAddBook(bookId: String): Boolean {
        val item = newBook(bookId) ?: return false
        return placementFor(item) != null
    }

    /** Registration is retained even if shelf capacity requires a later exchange. */
    fun registerBook(book: BookInfo): Boolean {
        if (document.books.any { it.id == book.id }) return false
        return commit(document.copy(books = document.books + book))
    }

    /** Physical edits use the same collision checks as placement; neighbors never move implicitly. */
    fun updateBook(book: BookInfo): Boolean {
        if(document.books.none { it.id == book.id }) return false
        return commit(document.copy(books = document.books.map { if(it.id == book.id) book else it }))
    }

    fun configure(spec: ShelfSpec, theme: String): Boolean = commit(document.copy(shelf = spec, theme = theme))

    fun addBook(bookId: String): Boolean {
        val item = newBook(bookId) ?: return false
        return placeNew(item)
    }

    fun addProp(propId: String): Boolean {
        if (propId !in PropCatalog.ids || document.items.count { it.propId != null } >= 6) return false
        return placeNew(ShelfItem(nextId("prop-$propId"), propId = propId, row = 0, x = 0f))
    }

    private fun stackedDocument(sourceId: String, targetId: String): ShelfDocument? {
        if (sourceId == targetId) return null
        val source = document.items.find { it.id == sourceId } ?: return null
        val target = document.items.find { it.id == targetId } ?: return null
        if (source.propId != null || target.propId != null) return null
        return document.copy(items = document.items.filterNot { it.id == sourceId }.map {
            if (it.id == targetId) it.copy(bookIds = target.bookIds + source.bookIds, orientation = Orientation.FLAT) else it
        }).takeIf(ShelfGeometry::isValid)
    }

    fun canStack(sourceId: String, targetId: String): Boolean = stackedDocument(sourceId, targetId) != null

    fun stack(sourceId: String, targetId: String): Boolean {
        val next = stackedDocument(sourceId, targetId) ?: return false
        val result = commit(next)
        if (result) select(targetId)
        return result
    }

    private fun exchangedDocument(targetId: String, bookId: String): ShelfDocument? {
        if (newBook(bookId) == null) return null
        val target = document.items.find { it.id == targetId } ?: return null
        return document.copy(items = document.items.map {
            if (it.id == targetId) target.copy(bookIds = listOf(bookId), propId = null) else it
        }).takeIf(ShelfGeometry::isValid)
    }

    fun canReplaceItemWithBook(targetId: String, bookId: String): Boolean = exchangedDocument(targetId, bookId) != null

    /** Replace the entire target, preserving its position/orientation and every unrelated item. */
    fun replaceItemWithBook(targetId: String, bookId: String): Boolean {
        val next = exchangedDocument(targetId, bookId) ?: return false
        return commit(next).also { if (it) select(targetId) }
    }

    fun unstack(itemId: String, bookId: String): Boolean {
        val item = document.items.find { it.id == itemId } ?: return false
        if (item.bookIds.size < 2 || bookId !in item.bookIds) return false
        val base = document.items.map { if (it.id == itemId) it.copy(bookIds = it.bookIds - bookId) else it }
        return placeNew(ShelfItem(nextId("book-$bookId"), listOf(bookId), row = 0, x = 0f, orientation = Orientation.FLAT), base)
    }

    /** Entry radius is smaller than exit radius, keeping a held snap stable under pointer jitter. */
    fun preview(id: String, row: Int, x: Float, previousSnap: Float? = null): PlacementPreview {
        val item = document.items.find { it.id == id }
            ?: return PlacementPreview(row, x, false, false)
        if (row !in 0 until ShelfGeometry.rows(document) || !x.isFinite()) return PlacementPreview(row, x, false, false)
        val half = ShelfGeometry.dimensions(item, document).width / 2
        val neighbors = document.items.filter { it.id != id && it.row == row }.sortedBy { it.x }
        val candidates = mutableListOf(-ShelfGeometry.width(document) / 2 + half, ShelfGeometry.width(document) / 2 - half)
        for (neighbor in neighbors) {
            val offset = ShelfGeometry.dimensions(neighbor, document).width / 2 + half
            candidates += neighbor.x - offset
            candidates += neighbor.x + offset
        }
        // Equal free space on either side, measured from visible object edges.
        val boundaries = listOf(-ShelfGeometry.width(document) / 2 to -ShelfGeometry.width(document) / 2) +
            neighbors.map { it.x - ShelfGeometry.dimensions(it, document).width / 2 to it.x + ShelfGeometry.dimensions(it, document).width / 2 } +
            listOf(ShelfGeometry.width(document) / 2 to ShelfGeometry.width(document) / 2)
        for ((left, right) in boundaries.zipWithNext()) {
            if (right.first - left.second >= 2 * half) candidates += (left.second + right.first) / 2
        }
        val held = previousSnap?.takeIf { it.isFinite() && abs(x - it) <= 0.24f && candidates.any { candidate -> abs(candidate - it) < 0.001f } }
        val snap = held ?: candidates.minByOrNull { abs(it - x) }?.takeIf { abs(it - x) <= 0.14f }
        val resultX = snap ?: x
        val valid = ShelfGeometry.isValid(document.copy(items = document.items.map {
            if (it.id == id) it.copy(row = row, x = resultX) else it
        }))
        return PlacementPreview(row, resultX, valid, snap != null)
    }

    fun undo(): Boolean {
        if (past.isEmpty()) return false
        future += document
        document = past.removeAt(past.lastIndex)
        select(selectedId)
        return true
    }

    fun redo(): Boolean {
        if (future.isEmpty()) return false
        past += document
        document = future.removeAt(future.lastIndex)
        select(selectedId)
        return true
    }

    /** Loading starts a new history; invalid input leaves both document and history untouched. */
    fun replace(next: ShelfDocument): Boolean {
        if (!ShelfGeometry.isValid(next)) return false
        document = next
        past.clear()
        future.clear()
        selectedId = null
        return true
    }
}
