package net.monindev.shelfie.core

/** Registered books in four common formats, used where tests need varied physical sizes. */
internal object TestBooks {
    private val formats = listOf(105 to 148, 128 to 188, 148 to 210, 182 to 257)
    private val pageCounts = listOf(144, 224, 320, 416, 560, 704)
    val all: List<BookInfo> = (1..36).map { number ->
        val format = formats[(number - 1) % formats.size]
        BookInfo("manual-b%02d".format(number), "テストの本 $number", "著者", format.first, format.second, pageCounts[(number - 1) % pageCounts.size])
    }
    fun get(id: String): BookInfo = all.first { it.id == id }

    /** A 36 cm shelf with three flat books and three upright formats. */
    fun starter(): ShelfDocument = ShelfDocument(books = all, items = listOf(
        ShelfItem("starter-stack", listOf("manual-b03", "manual-b02", "manual-b01"), row = 0, x = -0.96f, orientation = Orientation.FLAT),
        ShelfItem("book-b05", listOf("manual-b05"), row = 0, x = 0.405f, orientation = Orientation.COVER),
        ShelfItem("book-b04", listOf("manual-b04"), row = 0, x = 1.14f),
        ShelfItem("book-b06", listOf("manual-b06"), row = 0, x = 1.52f),
    ))
}
