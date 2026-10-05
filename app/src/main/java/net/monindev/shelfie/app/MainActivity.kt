@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.monindev.shelfie.core.BookInfo

class MainActivity : ComponentActivity() {
    var linkedPostId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acceptLink(intent)
        setContent { ShelfieTheme { ShelfieApp(this) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptLink(intent) }
    private fun acceptLink(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == "shelfie" && uri.host == "post") linkedPostId = uri.lastPathSegment?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    }
}

private enum class Tab(val label: String, val icon: Int, val selectedIcon: Int) {
    SHELF("本棚", R.drawable.ic_shelves, R.drawable.ic_shelves_fill),
    DISCOVER("みんなの棚", R.drawable.ic_explore, R.drawable.ic_explore_fill),
    READING("積読", R.drawable.ic_bookmarks, R.drawable.ic_bookmarks_fill),
}

@Composable
private fun ShelfieApp(activity: MainActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { ShelfSession(ShelfStore(context), scope) }
    val library = remember { LibrarySession(LibraryStore(context), scope) }
    val service = remember { ServiceSession(ServiceClient(context)) }
    val thumbnails = remember { ShelfThumbnails(context) }
    val snackbar = remember { SnackbarHostState() }
    val notify = remember { Notifier(snackbar, scope, session) }
    var tab by rememberSaveable { mutableStateOf(Tab.SHELF) }
    var adding by remember { mutableStateOf<AddTarget?>(null) }
    var publishing by remember { mutableStateOf(false) }
    var swapping by remember { mutableStateOf<BookInfo?>(null) }
    var pendingPost by remember { mutableStateOf<String?>(null) }
    val covered = adding != null || publishing

    LaunchedEffect(Unit) { session.load() }
    LaunchedEffect(Unit) { session.writeQueuedSaves() }
    LaunchedEffect(Unit) { library.load() }
    DisposableEffect(thumbnails) { onDispose { thumbnails.release() } }
    LaunchedEffect(activity.linkedPostId) {
        activity.linkedPostId?.let { pendingPost = it; activity.linkedPostId = null; adding = null; publishing = false; tab = Tab.DISCOVER }
    }

    /** Registers an unknown book first, then places it or asks which book to swap out. */
    fun place(book: BookInfo) {
        adding = null
        tab = Tab.SHELF
        if (session.document.items.any { book.id in it.bookIds }) { notify.show("「${book.title}」はすでに棚にあります"); return }
        if (session.document.books.none { it.id == book.id } &&
            notify.edit(session.edit { it.registerBook(book) }, "この本は登録できません。") != EditResult.APPLIED) return
        if (session.editor.canAddBook(book.id)) {
            if (notify.edit(session.edit { it.addBook(book.id) }) == EditResult.APPLIED) notify.undoable("「${book.title}」を棚に置きました")
        } else swapping = book
    }

    BackHandler(enabled = !covered && tab != Tab.SHELF) { tab = Tab.SHELF }
    // Pending writes complete before Back closes the activity.
    BackHandler(enabled = !covered && tab == Tab.SHELF && session.loaded && session.saving) {
        scope.launch {
            snapshotFlow { session.saving }.first { !it }
            if (session.problem == null) activity.finish() else notify.show("本棚を保存できていません。「保存し直す」を押してください。")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (covered) Modifier.clearAndSetSemantics {} else Modifier,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                ShortNavigationBar {
                    Tab.entries.forEach { item ->
                        ShortNavigationBarItem(selected = tab == item, onClick = { snackbar.currentSnackbarData?.dismiss(); tab = item },
                            icon = { Glyph(if (tab == item) item.selectedIcon else item.icon, null) }, label = { Text(item.label) },
                            modifier = Modifier.testTag("tab-${item.name.lowercase()}"))
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                when (tab) {
                    Tab.SHELF -> ShelfScreen(activity, session, notify, active = !covered,
                        onAddBook = { adding = AddTarget.SHELF }, onPublish = { publishing = true })
                    Tab.DISCOVER -> DiscoverScreen(activity, service, library, thumbnails, notify, pendingPost, onPendingHandled = { pendingPost = null })
                    Tab.READING -> ReadingScreen(library, notify, canPlace = session.editable,
                        placed = session.document.items.flatMap { it.bookIds }.toSet(), onPlace = ::place,
                        onFind = { adding = AddTarget.READING }, onOpenPost = { pendingPost = it; tab = Tab.DISCOVER })
                }
            }
        }
        adding?.let { target ->
            Surface(Modifier.fillMaxSize()) {
                AddBookScreen(target, service, session, library, notify, onPlace = ::place, onDismiss = { adding = null })
            }
        }
        if (publishing) Surface(Modifier.fillMaxSize()) {
            PublishScreen(activity, service, session, thumbnails, notify, onDismiss = { publishing = false })
        }
        // On the shelf, messages sit above the editing panel instead of covering its buttons.
        val snackbarOffset = if (!covered && tab == Tab.SHELF) 72.dp + shelfPanelHeight() else 80.dp
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = snackbarOffset))
    }
    swapping?.let { book ->
        SwapSheet(session, book, notify, onSettings = { session.settingsRequested = true }, onDismiss = { swapping = null })
    }
}
