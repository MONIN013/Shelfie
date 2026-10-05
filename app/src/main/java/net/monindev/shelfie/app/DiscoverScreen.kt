@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.monindev.shelfie.core.*

internal fun publishedDate(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val pattern = if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) "M月d日" else "yyyy年M月d日"
    return SimpleDateFormat(pattern, Locale.JAPAN).format(Date(millis))
}

@Composable
internal fun DiscoverScreen(activity: Activity, service: ServiceSession, library: LibrarySession, thumbnails: ShelfThumbnails,
    notify: Notifier, pendingPostId: String?, onPendingHandled: () -> Unit) {
    val request = rememberRequest(service)
    val key = if (service.favoritesOnly) "favorites:${library.library.favorites}" else "latest"
    suspend fun load() {
        if (service.favoritesOnly) {
            // Favorites live on the device; shelves that are no longer public are dropped from the list.
            val posts = mutableListOf<PublicPost>()
            for (id in library.library.favorites) {
                try { posts += service.client.post(id) }
                catch (error: ServiceError) { if (error.status == 404) library.forgetSource(id) else throw error }
            }
            service.feed = posts
            service.next = null
        } else {
            val page = service.client.feed(service.query.trim())
            service.feed = page.posts
            service.next = page.next
        }
        service.feedKey = key
    }
    LaunchedEffect(key, library.loaded) {
        if (library.loaded && service.feedKey != key) request.run { load() }
    }
    LaunchedEffect(pendingPostId) {
        val id = pendingPostId ?: return@LaunchedEffect
        onPendingHandled()
        snapshotFlow { request.busy }.first { !it }
        request.run { service.openPost = openPost(service, library, id) }
    }
    val post = service.openPost
    if (post != null) {
        BackHandler { service.openPost = null }
        PostScreen(activity, post, service, library, request, notify, onBack = { service.openPost = null })
    } else FeedScreen(service, library, thumbnails, request, onLoad = { request.run { load() } },
        onMore = { cursor -> request.run {
            val page = service.client.feed(service.query.trim(), cursor)
            service.feed = ((service.feed ?: emptyList()) + page.posts).distinctBy { it.id }
            service.next = page.next
        } },
        onOpen = { id -> request.run { service.openPost = openPost(service, library, id) } })
}

/** A shelf that is no longer public is forgotten by the favorites and reading list. */
internal suspend fun openPost(service: ServiceSession, library: LibrarySession, id: String): PublicPost? = try {
    service.client.post(id)
} catch (error: ServiceError) {
    if (error.status == 404) library.forgetSource(id)
    throw error
}

@Composable
private fun FeedScreen(service: ServiceSession, library: LibrarySession, thumbnails: ShelfThumbnails, request: Request,
    onLoad: () -> Unit, onMore: (FeedCursor) -> Unit, onOpen: (String) -> Unit) {
    val query = service.query.trim()
    val posts = service.feed.orEmpty().let { all ->
        // Favorites are fetched by ID, so search them on the device.
        if (!service.favoritesOnly || query.isEmpty()) all
        else all.filter { post -> post.title.contains(query, true) || post.document.books.any { it.title.contains(query, true) } }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("みんなの棚") }, actions = { TipIconButton(R.drawable.ic_refresh, "更新", enabled = !request.busy, onClick = onLoad) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("shelf-feed"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SearchField(service.query, { service.query = it; if (it.isEmpty() && !service.favoritesOnly) onLoad() },
                    "棚の名前・本のタイトルで検索", onSearch = { if (!service.favoritesOnly) onLoad() }, Modifier.testTag("feed-query"))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !service.favoritesOnly, onClick = { service.favoritesOnly = false }, label = { Text("新着") },
                        enabled = !request.busy, modifier = Modifier.testTag("filter-latest"))
                    FilterChip(selected = service.favoritesOnly, onClick = { service.favoritesOnly = true },
                        label = { Text("お気に入り") }, enabled = !request.busy, modifier = Modifier.testTag("filter-favorites"),
                        leadingIcon = { Glyph(if (service.favoritesOnly) R.drawable.ic_favorite_fill else R.drawable.ic_favorite, null, Modifier.size(18.dp)) })
                }
            }
            request.error?.let { item { ErrorBanner(it, Modifier.testTag("service-error")) { TextButton(onClick = onLoad) { Text("再読み込み") } } } }
            if (request.busy && posts.isEmpty()) item { PageLoading(Modifier.testTag("service-loading")) }
            else if (request.busy) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag("service-loading")) }
            if (!request.busy && request.error == null && service.feed != null && posts.isEmpty()) item {
                when {
                    service.favoritesOnly && query.isNotEmpty() -> EmptyState(R.drawable.ic_search, "見つかりませんでした", "お気に入りの棚に「$query」はありません。")
                    service.favoritesOnly -> EmptyState(R.drawable.ic_favorite, "お気に入りはまだありません", "気に入った棚を開いて、ハートを押すとここに表示されます。")
                    query.isNotEmpty() -> EmptyState(R.drawable.ic_search, "見つかりませんでした", "別のキーワードで探してみてください。")
                    else -> EmptyState(R.drawable.ic_explore, "まだ公開された棚がありません", "自分の棚を公開すると、ここに表示されます。")
                }
            }
            items(posts, key = { it.id }) { post -> ShelfCard(post, thumbnails, library.isFavorite(post.id), enabled = !request.busy) { onOpen(post.id) } }
            service.next?.takeIf { !service.favoritesOnly }?.let { cursor ->
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { onMore(cursor) }, enabled = !request.busy, shapes = ButtonDefaults.shapes()) { Text("さらに表示") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(post: PublicPost, thumbnails: ShelfThumbnails, favorite: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().testTag("feed-post-${post.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShelfImage(thumbnails, "post:${post.id}:${post.publishedAt}", post.document, "${post.title}の棚")
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (favorite) Glyph(R.drawable.ic_favorite_fill, "お気に入り", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Avatar(post.displayName, 24.dp)
                    Text("${post.displayName} · ${bookCount(post.document)}冊 · ${publishedDate(post.publishedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (post.note.isNotBlank()) Text(post.note, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PostScreen(activity: Activity, post: PublicPost, service: ServiceSession, library: LibrarySession, request: Request,
    notify: Notifier, onBack: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val favorite = library.isFavorite(post.id)
    val books = post.document.items.sortedWith(compareBy({ it.row }, { it.x })).flatMap { it.bookIds }.map(post.document::book)
    Scaffold(topBar = {
        TopAppBar(title = { Text(post.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TipIconButton(R.drawable.ic_arrow_back, "みんなの棚に戻る", onClick = onBack) },
            actions = {
                TipIconButton(if (favorite) R.drawable.ic_favorite_fill else R.drawable.ic_favorite,
                    if (favorite) "お気に入りから外す" else "お気に入りに追加", Modifier.testTag("toggle-favorite")) {
                    library.toggleFavorite(post.id) { ok ->
                        notify.show(if (!ok) "お気に入りを保存できませんでした" else if (favorite) "お気に入りから外しました" else "お気に入りに追加しました")
                    }
                }
                TipIconButton(R.drawable.ic_share, "共有") {
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, post.url)
                    }, "棚を共有"))
                }
                Box {
                    TipIconButton(R.drawable.ic_more_vert, "その他の操作") { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("この棚を通報") }, leadingIcon = { Glyph(R.drawable.ic_flag, null) }, onClick = {
                            menu = false
                            if (service.account == null) signingIn = true else reporting = true
                        })
                    }
                }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().testTag("public-shelf"),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item { ReadOnlyShelf(activity, post.document, Modifier.fillMaxWidth().height(300.dp)) }
            if (request.busy) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag("service-loading")) }
            request.error?.let { item { ErrorBanner(it, Modifier.padding(16.dp).testTag("service-error")) } }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(post.displayName, 48.dp)
                    Column {
                        Text(post.displayName, style = MaterialTheme.typography.titleMediumEmphasized)
                        Text("${books.size}冊 · ${publishedDate(post.publishedAt)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (post.note.isNotBlank()) item {
                Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(post.note, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionHeader("並んでいる本")
                    Text("気になる本は積読に追加して、あとで自分の棚に置けます。", Modifier.padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                val saved = library.has(book.id)
                SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, books.size),
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = ListItemDefaults.SegmentedGap), colors = surfaceListColors(),
                    leadingContent = { BookThumb(book, 32.dp) },
                    supportingContent = { BookLines(book) },
                    trailingContent = {
                        FilledTonalIconButton(onClick = {
                            library.addReading(book, post.id) { ok ->
                                notify.show(if (ok) "「${book.title}」を積読に追加しました" else "積読に追加できませんでした")
                            }
                        }, enabled = !saved, shapes = IconButtonDefaults.shapes(), modifier = Modifier.testTag("want-${book.id}")) {
                            Glyph(if (saved) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark_add,
                                if (saved) "「${book.title}」は積読にあります" else "「${book.title}」を積読に追加")
                        }
                    }) { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    if (signingIn) SignInSheet(service, notify, "通報するにはログインしてください", "不適切な棚の通報は、なりすましを防ぐためアカウントで受け付けています。",
        onDismiss = { signingIn = false }, onSignedIn = { signingIn = false; reporting = true })
    if (reporting) ReportDialog(request, onDismiss = { reporting = false }) { reason ->
        request.run {
            service.client.request("/v1/posts/${post.id}/report", "POST", buildJsonObject { put("reason", reason) })
            reporting = false
            notify.show("通報を受け付けました。ご協力ありがとうございます。")
        }
    }
}

@Composable
private fun ReportDialog(request: Request, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!request.busy) onDismiss() }, icon = { Glyph(R.drawable.ic_flag, null) },
        title = { Text("この棚を通報") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("どのような問題があるか、具体的に書いてください。運営が確認します。")
                OutlinedTextField(reason, { if (it.length <= 1000) reason = it }, Modifier.fillMaxWidth().testTag("report-reason"),
                    label = { Text("通報の理由") }, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onSend(reason.trim()) }, enabled = reason.isNotBlank() && !request.busy) { Text("送信") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !request.busy) { Text("キャンセル") } })
}
