@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import net.monindev.shelfie.core.*

internal const val MAX_PROPS = 6

internal fun mm(value: Float): String = String.format(Locale.JAPAN, "%.1f", value).removeSuffix(".0")

@Composable
internal fun SheetTitle(title: String, supporting: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
        if (supporting != null) Text(supporting, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemLeading(document: ShelfDocument, item: ShelfItem) {
    val book = item.bookIds.firstOrNull()?.let(document::book)
    if (book != null) BookThumb(book, 32.dp)
    else {
        // Each prop gets the expressive shape closest to its silhouette.
        val shape = when (item.propId) { "pebble" -> MaterialShapes.Oval; "arch" -> MaterialShapes.Arch; else -> MaterialShapes.Cookie6Sided }
        Box(Modifier.size(40.dp).clip(shape.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
            if (item.propId == "vase") Glyph(R.drawable.ic_potted_plant, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
internal fun ItemListSheet(document: ShelfDocument, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val items = document.items.sortedWith(compareBy({ it.row }, { it.x }))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("棚に並んでいるもの", if (items.isEmpty()) null else "左から順に並んでいます。タップすると選択します。")
        if (items.isEmpty()) EmptyState(R.drawable.ic_shelves, "棚は空です", "「本を追加」から最初の一冊を置きましょう。")
        LazyColumn(Modifier.fillMaxWidth().testTag("shelf-item-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                SegmentedListItem(onClick = { onPick(item.id) }, shapes = ListItemDefaults.segmentedShapes(index, items.size),
                    leadingContent = { ItemLeading(document, item) },
                    supportingContent = { Text(itemKind(item)) }) {
                    Text(itemTitle(document, item), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun PropSheet(document: ShelfDocument, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val count = document.items.count { it.propId != null }
    val full = count >= MAX_PROPS
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle("小物を置く", if (full) "小物は${MAX_PROPS}個までです。どれかを外すと追加できます。" else "あと${MAX_PROPS - count}個置けます（最大${MAX_PROPS}個）")
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag("prop-catalog"),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            val props = PropCatalog.ids.toList()
            props.forEachIndexed { index, prop ->
                SegmentedListItem(onClick = { onPick(prop) }, enabled = !full, shapes = ListItemDefaults.segmentedShapes(index, props.size),
                    modifier = Modifier.testTag("prop-$prop"),
                    leadingContent = { ItemLeading(document, ShelfItem("preview", propId = prop, row = 0, x = 0f)) },

                    trailingContent = { Glyph(R.drawable.ic_add, null) }) { Text(propTitle(prop)) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun ItemDetailSheet(session: ShelfSession, item: ShelfItem, notify: Notifier, onMeasure: (BookInfo) -> Unit, onDismiss: () -> Unit) {
    val document = session.document
    val books = item.bookIds.map(document::book)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        key(item.id) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)
                .testTag("item-detail")) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    books.firstOrNull()?.let { BookThumb(it, 64.dp) } ?: ItemLeading(document, item)
                    Column(Modifier.weight(1f)) {
                        Text(itemTitle(document, item), style = MaterialTheme.typography.headlineSmallEmphasized)
                        Text(books.firstOrNull()?.author?.ifBlank { "著者不明" } ?: "小物", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                books.forEach { book ->
                    SectionHeader(if (books.size > 1) book.title else "サイズ")
                    InfoRow("判型", "${book.formatLabel} · ${bookSize(book)}")
                    InfoRow("ページ数", pages(book))
                    InfoRow("厚さ", "${cm(book.thicknessMm)}\u00A0cm（${book.thicknessBasis}）")
                    if (book in document.books) TextButton(onClick = { onMeasure(book) }, enabled = session.editable && !session.saving,
                        modifier = Modifier.testTag("measure-${book.id}")) {
                        Glyph(R.drawable.ic_straighten, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("サイズを修正")
                    }
                }
                if (item.propId == null) StackSection(session, item, notify)
                OutlinedButton(onClick = {
                    val title = itemTitle(document, item)
                    onDismiss()
                    if (session.edit { it.remove(item.id) } == EditResult.APPLIED) notify.undoable("「$title」を棚から外しました")
                }, enabled = session.editable, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 24.dp).fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Glyph(R.drawable.ic_do_not_disturb_on, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("棚から外す")
                }
            }
        }
    }
}

@Composable
private fun StackSection(session: ShelfSession, item: ShelfItem, notify: Notifier) {
    val document = session.document
    if (item.bookIds.size > 1) {
        SectionHeader("平積みから取り出す")
        item.bookIds.forEach { bookId ->
            ListItem(trailingContent = {
                TextButton(onClick = {
                    notify.edit(session.edit { it.unstack(item.id, bookId) }, "取り出した本を置く空きがありません。ほかの本を動かしてください。")
                }, enabled = session.editable, modifier = Modifier.testTag("unstack-$bookId")) { Text("取り出す") }
            }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Text(document.book(bookId).title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val targets = document.items.filter { it.id != item.id && it.propId == null }
    if (targets.isEmpty()) return
    SectionHeader("ほかの本の上に積む")
    Text("選んだ本の上に平積みで重ねます。", Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        targets.forEachIndexed { index, target ->
            val combined = target.copy(bookIds = target.bookIds + item.bookIds, orientation = Orientation.FLAT)
            val height = ShelfGeometry.dimensions(combined, document).height * ShelfGeometry.MILLIMETERS_PER_UNIT
            val possible = session.editable && session.editor.canStack(item.id, target.id)
            SegmentedListItem(onClick = { notify.edit(session.edit { it.stack(item.id, target.id) }) }, enabled = possible,
                shapes = ListItemDefaults.segmentedShapes(index, targets.size), modifier = Modifier.testTag("stack-onto-${target.id}"),
                leadingContent = { ItemLeading(document, target) },
                supportingContent = {
                    Text(if (possible) "積むと高さ ${cm(height)}\u00A0cm" else "棚に収まらないため積めません")
                }) { Text("「${itemTitle(document, target)}」の上", maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun NumberField(value: String, onValue: (String) -> Unit, label: String, unit: String, supporting: String, error: Boolean,
    modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(value, { if (it.length <= 6) onValue(it) }, modifier, label = { Text(label) }, suffix = { Text(unit) },
        supportingText = { Text(supporting) }, isError = error, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}

private fun centimetersToMillimeters(text: String): Int? = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.let { (it * 10).roundToInt() }

@Composable
internal fun ShelfSettingsSheet(document: ShelfDocument, enabled: Boolean, onApply: (ShelfDocument) -> Unit, onDismiss: () -> Unit) {
    var width by rememberSaveable { mutableStateOf(cm(document.shelf.widthMm.toFloat())) }
    var height by rememberSaveable { mutableStateOf(cm(document.shelf.heightMm.toFloat())) }
    var finish by rememberSaveable { mutableStateOf(document.theme) }
    val w = centimetersToMillimeters(width)
    val h = centimetersToMillimeters(height)
    val widthOk = w != null && w in 240..1800
    val heightOk = h != null && h in 120..800
    val next = if (widthOk && heightOk) document.copy(shelf = document.shelf.copy(widthMm = w!!, heightMm = h!!), theme = finish) else null
    val fits = next != null && ShelfGeometry.isValid(next)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("棚の設定", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text("実際の棚の内側の大きさにすると、本が実物どおりに並びます。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionHeader("大きさ")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(width, { width = it }, "幅", "cm", "24〜180", !widthOk, Modifier.weight(1f).testTag("shelf-width"), decimal = true)
                NumberField(height, { height = it }, "高さ", "cm", "12〜80", !heightOk, Modifier.weight(1f).testTag("shelf-height"), decimal = true)
            }
            SectionHeader("素材")
            val finishes = listOf("oak" to "木目", "lavender" to "ラベンダー")
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                finishes.forEachIndexed { index, (id, label) ->
                    ToggleButton(checked = finish == id, onCheckedChange = { finish = id },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }.testTag("finish-$id"),
                        colors = segmentColors(),
                    shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                        if (finish == id) { Glyph(R.drawable.ic_check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                        Text(label)
                    }
                }
            }
            if (next != null && !fits) Text("今の本が収まりません。大きくするか、先に本を動かしてください。",
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
                Button(onClick = { next?.let(onApply) }, enabled = enabled && fits, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("apply-shelf-configuration")) { Text("適用") }
            }
        }
    }
}

/** Book sizes are entered in millimeters, as printed in bibliographic data. */
@Composable
internal fun BookSizeDialog(book: BookInfo, onDismiss: () -> Unit, onApply: (BookInfo) -> Boolean) {
    var width by rememberSaveable { mutableStateOf(book.widthMm.toString()) }
    var height by rememberSaveable { mutableStateOf(book.heightMm.toString()) }
    var pages by rememberSaveable { mutableStateOf(book.pageCount.toString()) }
    var thickness by rememberSaveable { mutableStateOf(book.measuredThicknessMm?.let(::mm) ?: "") }
    var overlaps by remember { mutableStateOf(false) }
    val form = BookForm(width, height, pages, thickness)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("サイズを修正") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BookSizeFields(book, form, onWidth = { width = it; overlaps = false }, onHeight = { height = it; overlaps = false },
                onPages = { pages = it; overlaps = false }, onThickness = { thickness = it; overlaps = false })
            if (overlaps) Text("このサイズでは棚や隣の本と重なります。本を動かしてから変更してください。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
    }, confirmButton = {
        TextButton(enabled = form.valid, modifier = Modifier.testTag("apply-book-size"), onClick = {
            if (onApply(form.applyTo(book))) onDismiss() else overlaps = true
        }) { Text("適用") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } })
}

internal class BookForm(val width: String, val height: String, val pages: String, val thickness: String) {
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val p = pages.toIntOrNull()
    val t = thickness.replace(',', '.').toFloatOrNull()
    val widthOk = w != null && w in 50..400
    val heightOk = h != null && h in 50..500
    val pagesOk = p != null && p in 1..3000
    val thicknessOk = thickness.isBlank() || (t != null && t.isFinite() && t in 1f..150f)
    val valid = widthOk && heightOk && pagesOk && thicknessOk
    fun applyTo(book: BookInfo): BookInfo = book.copy(widthMm = w!!, heightMm = h!!, pageCount = p!!,
        measuredThicknessMm = if (thickness.isBlank()) null else t,
        pageCountEstimated = book.pageCountEstimated && p == book.pageCount)
}

@Composable
internal fun BookSizeFields(book: BookInfo, form: BookForm, onWidth: (String) -> Unit, onHeight: (String) -> Unit,
    onPages: (String) -> Unit, onThickness: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(form.width, onWidth, "幅", "mm", "50〜400", !form.widthOk, Modifier.weight(1f).testTag("book-width"))
        NumberField(form.height, onHeight, "高さ", "mm", "50〜500", !form.heightOk, Modifier.weight(1f).testTag("book-height"))
    }
    NumberField(form.pages, onPages, "ページ数", "ページ", if (book.pageCountEstimated && form.p == book.pageCount) "不明のため仮の値です" else "1〜3000",
        !form.pagesOk, Modifier.fillMaxWidth().testTag("dimension-pages"))
    val automatic = book.copy(measuredThicknessMm = null, pageCount = form.p?.takeIf { form.pagesOk } ?: book.pageCount)
    NumberField(form.thickness, onThickness, "厚さ（実測・任意）", "mm",
        "空欄なら自動計算: ${mm(automatic.thicknessMm)} mm（${automatic.thicknessBasis}）", !form.thicknessOk,
        Modifier.fillMaxWidth().testTag("measured-thickness"), decimal = true)
}
