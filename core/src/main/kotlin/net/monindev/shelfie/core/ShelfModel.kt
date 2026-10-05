package net.monindev.shelfie.core

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class Orientation { COVER, SPINE, FLAT }

@Serializable
data class ShelfSpec(val widthMm: Int = 900, val heightMm: Int = 400, val depthMm: Int = 300)

@Serializable
data class ShelfItem(
    val id: String,
    val bookIds: List<String> = emptyList(),
    val propId: String? = null,
    val row: Int,
    val x: Float,
    val orientation: Orientation = Orientation.SPINE,
)

@Serializable
data class ShelfDocument(
    @Required val schemaVersion: Int = 4,
    val theme: String = "lavender",
    val items: List<ShelfItem>,
    val books: List<BookInfo> = emptyList(),
    @Required val shelf: ShelfSpec = ShelfSpec(360, 270, 270),
)

@Serializable
data class BookInfo(
    val id: String,
    val title: String,
    val author: String,
    val widthMm: Int,
    val heightMm: Int,
    val pageCount: Int,
    val sourceUrl: String? = null,
    val coverUrl: String? = null,
    val measuredThicknessMm: Float? = null,
    val automaticThicknessMm: Float? = null,
    val automaticThicknessSource: String? = null,
    val isbn: String? = null,
    val pageCountEstimated: Boolean = false,
) {
    // A page is one side of a leaf. Two cover boards add a fixed 2.6 mm.
    val thicknessMm: Float get() = measuredThicknessMm ?: automaticThicknessMm ?: (pageCount / 2f * PAPER_THICKNESS_MM + COVER_THICKNESS_MM)
    val thicknessBasis: String get() = when {
        measuredThicknessMm != null -> "実測"
        automaticThicknessMm != null -> "書誌情報"
        else -> "ページ数から推定"
    }
    val formatLabel: String get() = when (widthMm to heightMm) {
        105 to 148 -> "文庫"
        128 to 188 -> "四六判"
        148 to 210 -> "A5"
        182 to 257 -> "B5"
        else -> "${widthMm}×${heightMm}mm"
    }

    companion object {
        const val PAPER_THICKNESS_MM = 0.09f
        const val COVER_THICKNESS_MM = 2.6f
    }
}

fun ShelfDocument.book(id: String): BookInfo = books.firstOrNull { it.id == id }
    ?: throw IllegalArgumentException("Unknown book: $id")

object PropCatalog {
    val ids: Set<String> = setOf("pebble", "vase", "arch")
}

data class ItemDimensions(val width: Float, val height: Float, val depth: Float)

object ShelfGeometry {
    const val ROWS = 1
    const val BOTTOM_Y = 0.25f
    const val MILLIMETERS_PER_UNIT = 100f
    private val defaultGeometry = ShelfDocument(items = emptyList())

    fun width(document: ShelfDocument): Float = document.shelf.widthMm / MILLIMETERS_PER_UNIT
    fun rows(document: ShelfDocument): Int = ROWS
    fun depth(document: ShelfDocument): Float = document.shelf.depthMm / MILLIMETERS_PER_UNIT
    fun clearance(row: Int, document: ShelfDocument): Float {
        require(row in 0 until rows(document)) { "Invalid shelf row: $row" }
        return document.shelf.heightMm / MILLIMETERS_PER_UNIT
    }
    fun rowY(row: Int, document: ShelfDocument = defaultGeometry): Float {
        require(row in 0 until rows(document)) { "Invalid shelf row: $row" }
        return BOTTOM_Y
    }

    fun dimensions(item: ShelfItem, document: ShelfDocument = defaultGeometry): ItemDimensions {
        if (item.propId != null) return ItemDimensions(0.7f, if (item.propId == "vase") 1.1f else 0.7f, 0.7f)
        val books = item.bookIds.map(document::book)
        require(books.isNotEmpty()) { "A book item must contain a book" }
        val book = books.first()
        return when (item.orientation) {
            Orientation.COVER -> ItemDimensions(book.widthMm / MILLIMETERS_PER_UNIT, book.heightMm / MILLIMETERS_PER_UNIT, book.thicknessMm / MILLIMETERS_PER_UNIT)
            Orientation.SPINE -> ItemDimensions(book.thicknessMm / MILLIMETERS_PER_UNIT, book.heightMm / MILLIMETERS_PER_UNIT, book.widthMm / MILLIMETERS_PER_UNIT)
            Orientation.FLAT -> ItemDimensions(books.maxOf { it.widthMm } / MILLIMETERS_PER_UNIT,
                books.sumOf { it.thicknessMm.toDouble() }.toFloat() / MILLIMETERS_PER_UNIT,
                books.maxOf { it.heightMm } / MILLIMETERS_PER_UNIT)
        }
    }

    fun validationError(document: ShelfDocument): String? {
        if (document.schemaVersion != 4) return "Unsupported schema version: ${document.schemaVersion}"
        if (document.theme !in setOf("lavender", "oak")) return "Unknown shelf theme: ${document.theme}"
        val spec = document.shelf
        if(spec.widthMm !in 240..1800 || spec.heightMm !in 120..800 || spec.depthMm !in 100..600) return "Invalid shelf dimensions"
        if (document.books.size > 500 || document.books.map { it.id }.toSet().size != document.books.size) return "Invalid library size or duplicate book"
        document.books.firstNotNullOfOrNull(::bookError)?.let { return it }
        if (document.items.count { it.propId != null } > 6) return "A shelf supports at most six props"
        val ids = mutableSetOf<String>()
        val books = mutableSetOf<String>()
        for (item in document.items) {
            if (item.id.isBlank() || !ids.add(item.id)) return "Empty or duplicate item ID: ${item.id}"
            if (item.row !in 0 until rows(document) || !item.x.isFinite()) return "Invalid placement: ${item.id}"
            if (item.propId != null) {
                if (item.propId !in PropCatalog.ids || item.bookIds.isNotEmpty()) return "Invalid prop: ${item.id}"
            } else {
                if (item.bookIds.isEmpty()) return "Invalid book count: ${item.id}"
                if (item.bookIds.size > 1 && item.orientation != Orientation.FLAT) return "Stacks must lie flat: ${item.id}"
                for (book in item.bookIds) {
                    if (document.books.none { it.id == book } || !books.add(book)) return "Unknown or duplicate book: $book"
                }
            }
            val size = dimensions(item, document)
            if (size.height > clearance(item.row, document) + 0.0001f) return "Item exceeds shelf clearance: ${item.id}"
            if (size.depth > depth(document) + 0.0001f) return "Item exceeds shelf depth: ${item.id}"
            val half = size.width / 2
            if (item.x - half < -width(document) / 2 - 0.0001f || item.x + half > width(document) / 2 + 0.0001f) return "Item extends beyond shelf: ${item.id}"
        }
        for (row in 0 until rows(document)) {
            val ordered = document.items.filter { it.row == row }.sortedBy { it.x }
            for ((left, right) in ordered.zipWithNext()) {
                if (left.x + dimensions(left, document).width / 2 > right.x - dimensions(right, document).width / 2 + 0.0001f) return "Items overlap: ${left.id}, ${right.id}"
            }
        }
        return null
    }

    fun isValid(document: ShelfDocument): Boolean = validationError(document) == null

    /** Registered books are validated on their own, e.g. for the device reading list. */
    fun bookError(book: BookInfo): String? {
        if (!book.id.matches(Regex("(?:ol-|manual-)[A-Za-z0-9-]{1,80}"))) return "Invalid book ID"
        if (book.title.isBlank() || book.title.length > 200 || book.author.length > 200) return "Invalid book text"
        if (book.widthMm !in 50..400 || book.heightMm !in 50..500 || book.pageCount !in 1..3000) return "Invalid book dimensions"
        if (book.measuredThicknessMm?.let { !it.isFinite() || it !in 1f..150f } == true) return "Invalid measured thickness"
        if (book.automaticThicknessMm?.let { !it.isFinite() || it !in 1f..150f } == true) return "Invalid automatic thickness"
        if ((book.automaticThicknessMm == null) != (book.automaticThicknessSource == null) || book.automaticThicknessSource?.let { it != "bibliography" } == true) return "Invalid thickness source"
        if (book.isbn?.matches(Regex("[0-9]{13}|[0-9]{9}[0-9X]")) == false) return "Invalid ISBN"
        if (book.sourceUrl != null && !book.sourceUrl.matches(Regex("https://openlibrary\\.org/(works/OL[0-9]+W|books/OL[0-9]+M)|https://www\\.hanmoto\\.com/bd/isbn/[0-9]{13}"))) return "Invalid book source"
        if (book.coverUrl != null && !book.coverUrl.matches(Regex("https://covers\\.openlibrary\\.org/b/id/[0-9]+-M\\.jpg|https://cover\\.openbd\\.jp/[0-9]{13}\\.jpg"))) return "Invalid book cover"
        return null
    }
}

object ShelfCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(document: ShelfDocument): String {
        require(ShelfGeometry.isValid(document)) { ShelfGeometry.validationError(document)!! }
        return json.encodeToString(document)
    }
    fun decode(source: String): ShelfDocument {
        val document = try {
            json.decodeFromString<ShelfDocument>(source)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Shelf file is corrupt or has an unsupported structure", error)
        }
        require(ShelfGeometry.isValid(document)) { ShelfGeometry.validationError(document)!! }
        return document
    }
}

data class PlacementPreview(val row: Int, val x: Float, val valid: Boolean, val snapped: Boolean)
