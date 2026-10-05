@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.monindev.shelfie.core.BookInfo

/** The reading pile is kept on the device, so it works without an account. */
@Composable
internal fun ReadingScreen(library: LibrarySession, notify: Notifier, canPlace: Boolean, placed: Set<String>, onPlace: (BookInfo) -> Unit,
    onFind: () -> Unit, onOpenPost: (String) -> Unit) {
    val reading = library.library.reading
    Scaffold(
        topBar = { TopAppBar(title = { Text("積読") }, subtitle = { Text(if (reading.isEmpty()) "あとで読みたい本" else "${reading.size}冊") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onFind, icon = { Glyph(R.drawable.ic_search, null) }, text = { Text("本を探す") },
                modifier = Modifier.testTag("reading-find"))
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("reading-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (library.unreadable) item { ErrorBanner("積読を読み込めませんでした。データを守るため、変更を止めています。") }
            if (!library.loaded) item { PageLoading() }
            else if (reading.isEmpty() && !library.unreadable) item {
                EmptyState(R.drawable.ic_bookmarks, "積読はまだありません",
                    "みんなの棚で気になる本を見つけたら、積読に追加しておきましょう。「本を探す」から直接追加することもできます。")
            }
            items(reading, key = { it.book.id }) { entry ->
                Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().testTag("reading-${entry.book.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            BookThumb(entry.book, 48.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(entry.book.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                BookLines(entry.book)
                                entry.sourceId?.let { source ->
                                    AssistChip(onClick = { onOpenPost(source) }, label = { Text("見つけた棚を開く") },
                                        leadingIcon = { Glyph(R.drawable.ic_explore, null, Modifier.size(18.dp)) })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            TextButton(onClick = {
                                library.removeReading(entry.book.id) { ok ->
                                    if (ok) notify.action("「${entry.book.title}」を積読から外しました", "元に戻す") { library.restoreReading(entry) {} }
                                    else notify.show("積読を保存できませんでした")
                                }
                            }) { Text("外す") }
                            val onShelf = entry.book.id in placed
                            FilledTonalButton(onClick = { onPlace(entry.book) }, enabled = canPlace && !onShelf, shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.testTag("place-${entry.book.id}")) { Text(if (onShelf) "棚にあります" else "棚に置く") }
                        }
                    }
                }
            }
        }
    }
}
