@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import net.monindev.shelfie.core.*

internal enum class AddTarget { SHELF, READING }

@Composable
internal fun AddBookScreen(target: AddTarget, service: ServiceSession, session: ShelfSession, library: LibrarySession, notify: Notifier,
    onPlace: (BookInfo) -> Unit, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<BookInfo?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BookInfo>?>(null) }
    val request = rememberRequest(service)
    BackHandler { if (editing != null) editing = null else onDismiss() }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(editing, transitionSpec = {
        fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
    }, label = "add-book") { book ->
        if (book == null) BookSearch(target, session, request, query, { query = it }, results,
            onSearch = { text -> request.run { results = service.client.search(text) } },
            onEdit = { editing = it }, onPlace = onPlace, onDismiss = onDismiss)
        else BookRegistration(book, target, library, notify, canPlace = session.editable,
            onBack = { editing = null }, onPlace = onPlace, onSaved = onDismiss)
    }
}

@Composable
private fun BookSearch(target: AddTarget, session: ShelfSession, request: Request, query: String, onQuery: (String) -> Unit,
    results: List<BookInfo>?, onSearch: (String) -> Unit, onEdit: (BookInfo) -> Unit, onPlace: (BookInfo) -> Unit, onDismiss: () -> Unit) {
    var hint by remember { mutableStateOf(false) }
    val manual = { onEdit(BookInfo("manual-${UUID.randomUUID()}", "", "", 128, 188, 240, pageCountEstimated = true)) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (target == AddTarget.READING) "積読に本を追加" else "本を追加") },
            navigationIcon = { TipIconButton(R.drawable.ic_close, "閉じる", onClick = onDismiss) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                SearchField(query, { onQuery(it); hint = false }, "タイトル・著者名・ISBN", onSearch = {
                    val text = query.trim()
                    if (text.length >= 2) onSearch(text) else hint = true
                }, Modifier.testTag("book-search-query"))
                Text(if (hint) "2文字以上入力して検索してください" else "書誌データ: openBD・Open Library",
                    Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (hint) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            request.error?.let { item { ErrorBanner(it, Modifier.padding(bottom = 8.dp).testTag("service-error")) } }
            if (request.busy) item { PageLoading(Modifier.testTag("service-loading")) }
            else if (results != null) {
                item { SectionHeader(if (results.isEmpty()) "検索結果" else "検索結果 ${results.size}件", Modifier.padding(start = 16.dp)) }
                if (results.isEmpty()) item {
                    EmptyState(R.drawable.ic_search, "見つかりませんでした", "別のキーワードやISBNで探すか、手入力で追加してください。") {
                        FilledTonalButton(onClick = manual, shapes = ButtonDefaults.shapes()) { Text("手入力で追加") }
                    }
                }
                bookList(results, "result", onEdit)
            } else {
                item {
                    SegmentedListItem(onClick = manual, shapes = ListItemDefaults.segmentedShapes(0, 1), modifier = Modifier.testTag("manual-entry"),
                        colors = surfaceListColors(),
                        leadingContent = { Glyph(R.drawable.ic_edit, null) },
                        supportingContent = { Text("検索で見つからない本は、タイトルとサイズを入力して追加できます") }) { Text("手入力で追加") }
                }
                if (target == AddTarget.SHELF) {
                    val document = session.document
                    val placed = document.items.flatMap { it.bookIds }.toSet()
                    val removed = document.books.filter { it.id !in placed }
                    if (removed.isNotEmpty()) {
                        item { SectionHeader("棚から外した本", Modifier.padding(start = 16.dp)) }
                        bookList(removed, "removed", onPlace)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.bookList(books: List<BookInfo>, prefix: String, onClick: (BookInfo) -> Unit) {
    itemsIndexed(books, key = { _, book -> "$prefix-${book.id}" }) { index, book ->
        SegmentedListItem(onClick = { onClick(book) }, shapes = ListItemDefaults.segmentedShapes(index, books.size),
            modifier = Modifier.padding(bottom = ListItemDefaults.SegmentedGap).testTag("book-${book.id}"), colors = surfaceListColors(),
            leadingContent = { BookThumb(book, 32.dp) },
            supportingContent = { BookLines(book) },
            trailingContent = { Glyph(if (prefix == "result") R.drawable.ic_chevron_right else R.drawable.ic_add, null) }) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BookRegistration(book: BookInfo, target: AddTarget, library: LibrarySession, notify: Notifier,
    canPlace: Boolean, onBack: () -> Unit, onPlace: (BookInfo) -> Unit, onSaved: () -> Unit) {
    var title by rememberSaveable(book.id) { mutableStateOf(book.title) }
    var author by rememberSaveable(book.id) { mutableStateOf(book.author) }
    var width by rememberSaveable(book.id) { mutableStateOf(book.widthMm.toString()) }
    var height by rememberSaveable(book.id) { mutableStateOf(book.heightMm.toString()) }
    var pages by rememberSaveable(book.id) { mutableStateOf(book.pageCount.toString()) }
    var thickness by rememberSaveable(book.id) { mutableStateOf(book.measuredThicknessMm?.let(::mm) ?: "") }
    val form = BookForm(width, height, pages, thickness)
    val valid = form.valid && title.isNotBlank()
    fun result() = form.applyTo(book).copy(title = title.trim(), author = author.trim())
    val toReading = {
        val saved = result()
        library.addReading(saved, null) { ok -> notify.show(if (ok) "「${saved.title}」を積読に追加しました" else "積読に追加できませんでした") }
        onSaved()
    }
    val toShelf = { onPlace(result()) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("本の情報を確認") }, navigationIcon = { TipIconButton(R.drawable.ic_arrow_back, "検索に戻る", onClick = onBack) })
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shelfFirst = target == AddTarget.SHELF
                    OutlinedButton(onClick = if (shelfFirst) toReading else toShelf, enabled = valid && (shelfFirst || canPlace),
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag(if (shelfFirst) "register-book-reading" else "register-book-submit")) {
                        Text(if (shelfFirst) "積読に追加" else "棚に置く", maxLines = 1)
                    }
                    Button(onClick = if (shelfFirst) toShelf else toReading, enabled = valid && (!shelfFirst || canPlace),
                        shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .testTag(if (shelfFirst) "register-book-submit" else "register-book-reading")) {
                        Text(if (shelfFirst) "棚に置く" else "積読に追加", maxLines = 1)
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookThumb(book.copy(title = title.ifBlank { "タイトル" }, author = author), 72.dp)
                Text(if (book.coverUrl != null) "表紙の画像は書誌データから取得しました。"
                    else "表紙の画像がない本は、タイトルを書いた表紙で棚に並びます。",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(title, { if (it.length <= 200) title = it }, Modifier.fillMaxWidth().testTag("register-book-title"),
                label = { Text("タイトル（必須）") }, singleLine = true)
            OutlinedTextField(author, { if (it.length <= 200) author = it }, Modifier.fillMaxWidth().testTag("register-book-author"),
                label = { Text("著者名（任意）") }, singleLine = true)
            SectionHeader("サイズ")
            Text(if (book.pageCountEstimated) "棚に置ける冊数は、本のサイズと厚さで決まります。わからない値は、一般的な単行本（四六判・240ページ）のままで構いません。"
                else "棚に置ける冊数は、本のサイズと厚さで決まります。書誌データの値が実物と違う場合は修正してください。",
                Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BookSizeFields(book, form, onWidth = { width = it }, onHeight = { height = it }, onPages = { pages = it }, onThickness = { thickness = it })
        }
    }
}

/** Shown when a book does not fit. Choosing a book swaps immediately; the snackbar offers undo. */
@Composable
internal fun SwapSheet(session: ShelfSession, book: BookInfo, notify: Notifier, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val document = session.document
    val targets = document.items.filter { session.editor.canReplaceItemWithBook(it.id, book.id) }
    val fitsAlone = Orientation.entries.any { orientation ->
        ShelfGeometry.isValid(document.copy(items = listOf(ShelfItem("probe", listOf(book.id), row = 0, x = 0f, orientation = orientation))))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("swap-sheet")) {
            SheetTitle("棚に空きがありません", if (targets.isEmpty()) null
                else "「${book.title}」と入れ替える本を選んでください。外した本は「本を追加」からいつでも戻せます。")
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookThumb(book, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(bookSummary(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (targets.isEmpty()) {
                Text(if (fitsAlone) "入れ替えられる本がありません。棚の本を外すか、平積みにまとめて空きを作ってから追加してください。"
                    else "この本は棚の内側より大きいため置けません（高さ ${cm(book.heightMm.toFloat())}\u00A0cm）。棚のサイズを広げてください。",
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                    if (!fitsAlone) FilledTonalButton(onClick = { onDismiss(); onSettings() }, shapes = ButtonDefaults.shapes()) { Text("棚の設定を開く") }
                }
            } else {
                SectionHeader("入れ替える本", Modifier.padding(start = 24.dp))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    targets.forEachIndexed { index, target ->
                        SegmentedListItem(onClick = {
                            val title = itemTitle(document, target)
                            onDismiss()
                            if (notify.edit(session.edit { it.replaceItemWithBook(target.id, book.id) }) == EditResult.APPLIED)
                                notify.undoable("「$title」を外して「${book.title}」を置きました")
                        }, shapes = ListItemDefaults.segmentedShapes(index, targets.size), modifier = Modifier.testTag("exchange-${target.id}"),
                            leadingContent = { target.bookIds.firstOrNull()?.let { BookThumb(document.book(it), 32.dp) } },
                            supportingContent = {
                                Text(if (target.bookIds.size > 1) "平積みの${target.bookIds.size}冊をすべて外します"
                                    else itemKind(target))
                            },
                            trailingContent = { Glyph(R.drawable.ic_swap_horiz, null) }) {
                            Text(itemTitle(document, target), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
