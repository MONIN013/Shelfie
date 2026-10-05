package net.monindev.shelfie.render

import net.monindev.shelfie.core.*

/** Registered books in the four common formats used by the projection tests. */
internal object TestBooks {
    private val formats = listOf(105 to 148, 128 to 188, 148 to 210, 182 to 257)
    private val pageCounts = listOf(144, 224, 320, 416, 560, 704)
    val all: List<BookInfo> = (1..6).map { number ->
        val format = formats[(number - 1) % formats.size]
        BookInfo("manual-b%02d".format(number), "テストの本 $number", "著者", format.first, format.second, pageCounts[number - 1])
    }
    fun get(id: String): BookInfo = all.first { it.id == id }
    fun document(vararg items: ShelfItem, shelf: ShelfSpec = ShelfSpec(360, 270, 270)) =
        ShelfDocument(items = items.toList(), books = all, shelf = shelf)
}
