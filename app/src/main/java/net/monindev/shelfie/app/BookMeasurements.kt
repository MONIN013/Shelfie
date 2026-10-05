package net.monindev.shelfie.app

import java.util.Locale
import net.monindev.shelfie.core.BookInfo
import net.monindev.shelfie.core.Orientation
import net.monindev.shelfie.core.ShelfDocument
import net.monindev.shelfie.core.ShelfItem
import net.monindev.shelfie.core.book

/** Formats millimeters as centimeters with one decimal, dropping a trailing ".0". */
internal fun cm(mm: Float): String = String.format(Locale.JAPAN, "%.1f", mm / 10f).removeSuffix(".0")

internal fun pages(book: BookInfo): String = "${book.pageCount}ページ" + if (book.pageCountEstimated) "（仮）" else ""

/** One line for lists: format, pages and the thickness that decides shelf space. */
internal fun bookSummary(book: BookInfo): String = "${book.formatLabel} · ${pages(book)} · 厚さ\u00A0${cm(book.thicknessMm)}\u00A0cm"

internal fun bookSize(book: BookInfo): String = "${cm(book.widthMm.toFloat())} × ${cm(book.heightMm.toFloat())}\u00A0cm"

internal fun Orientation.label(): String = when (this) {
    Orientation.SPINE -> "背表紙"
    Orientation.COVER -> "表紙"
    Orientation.FLAT -> "平積み"
}

internal fun propTitle(id: String): String = when (id) {
    "pebble" -> "小石のオブジェ"
    "vase" -> "一輪挿し"
    "arch" -> "アーチのオブジェ"
    else -> id
}

internal fun itemTitle(document: ShelfDocument, item: ShelfItem): String =
    item.propId?.let(::propTitle) ?: item.bookIds.first().let { id ->
        document.book(id).title + if (item.bookIds.size > 1) " ほか${item.bookIds.size - 1}冊" else ""
    }

internal fun itemKind(item: ShelfItem): String =
    if (item.propId != null) "小物" else if (item.bookIds.size > 1) "平積み ${item.bookIds.size}冊" else item.orientation.label()

internal fun bookCount(document: ShelfDocument): Int = document.items.sumOf { it.bookIds.size }
