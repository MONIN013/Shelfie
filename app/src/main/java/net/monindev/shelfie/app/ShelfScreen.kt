@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.monindev.shelfie.core.*
import net.monindev.shelfie.render.*

private enum class ShelfSheet { ITEMS, PROPS, DETAIL, SETTINGS }
private enum class PanelMode { IDLE, SELECTED, MOVING }

/** One centimeter. Nudges bypass snapping so that small steps are never pulled back. */
private const val NUDGE = 0.1f

@Composable
internal fun ShelfScreen(activity: ComponentActivity, session: ShelfSession, notify: Notifier, active: Boolean,
    onAddBook: () -> Unit, onPublish: () -> Unit) {
    val document = session.document
    val selected = session.selected
    var sheet by remember { mutableStateOf<ShelfSheet?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmReset by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf<BookInfo?>(null) }
    val touchTarget = with(LocalDensity.current) { 48.dp.toPx() }
    val panelHeight = shelfPanelHeight()
    LaunchedEffect(session.settingsRequested) {
        if (session.settingsRequested) { session.settingsRequested = false; sheet = ShelfSheet.SETTINGS }
    }
    BackHandler(enabled = active && session.selectedId != null) {
        if (session.moving) { session.moving = false; session.preview = null } else session.select(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("私の本棚") },
                subtitle = {
                    Text(if (bookCount(document) == 0) "まだ本がありません" else "${bookCount(document)}冊", Modifier.testTag("shelf-status").semantics {
                        stateDescription = when {
                            session.problem != null -> "保存できません"
                            !session.loaded || session.saving -> "保存中"
                            else -> "保存済み"
                        }
                    })
                },
                actions = {
                    TipIconButton(R.drawable.ic_undo, "元に戻す", enabled = session.canUndo && session.editable) {
                        notify.edit(session.edit { it.undo() })
                    }
                    TipIconButton(R.drawable.ic_redo, "やり直す", enabled = session.canRedo && session.editable) {
                        notify.edit(session.edit { it.redo() })
                    }
                    FilledTonalButton(onClick = onPublish, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp).testTag("open-publish"),
                        contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Glyph(R.drawable.ic_public, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("公開")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(bottom = panelHeight - 28.dp)) {
                if (!session.loaded) PageLoading(Modifier.align(Alignment.Center))
                else {
                    ShelfViewport(activity, document, session.selectedId, session.viewport, session.preview,
                        enabled = session.problem == null && active,
                        onViewport = { session.viewport = it },
                        onTap = { point ->
                            val id = session.selectedId
                            val viewport = session.viewport
                            if (session.moving && id != null) {
                                val row = ShelfProjection.nearestRow(point.y, viewport, document)
                                session.preview = session.editor.preview(id, row, ShelfProjection.screenToShelf(point.x, point.y, row, viewport, document))
                            } else {
                                val hits = ShelfProjection.pick(point.x, point.y, document, viewport, touchTarget)
                                if (hits.size > 1) candidates = hits else session.select(hits.firstOrNull())
                            }
                        },
                        onDrag = { point, origin ->
                            session.selectedId?.let { id ->
                                val viewport = session.viewport
                                val row = ShelfProjection.nearestRow(point.y, viewport, document)
                                val originalX = document.items.first { it.id == id }.x
                                val offset = ShelfProjection.screenToShelf(point.x, point.y, row, viewport, document) -
                                    ShelfProjection.screenToShelf(origin.x, origin.y, row, viewport, document)
                                session.preview = session.editor.preview(id, row, originalX + offset,
                                    session.preview?.takeIf { it.snapped && it.row == row }?.x)
                            }
                        },
                        onDrop = {
                            val id = session.selectedId
                            val destination = session.preview
                            if (id != null && destination != null) {
                                if (destination.valid) notify.edit(session.edit { it.move(id, destination.row, destination.x) })
                                else notify.show("ほかの本や小物と重なるため、そこには置けません")
                            }
                            session.preview = null
                            session.moving = false
                        },
                        onCancel = { if (!session.moving) session.preview = null },
                    )
                    val preview = session.preview
                    if (preview != null && selected != null) {
                        PlacementGuide(document, selected, preview, Modifier.align(Alignment.TopCenter).padding(12.dp))
                    }
                    if (preview == null) ZoomControls(session, Modifier.align(Alignment.TopEnd).padding(12.dp))
                    if (session.problem == StorageProblem.UNREADABLE) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
                    }
                }
                session.problem?.let { problem ->
                    StorageBanner(problem, session, notify, onReset = { confirmReset = true },
                        modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                val mode = when {
                    selected == null || !session.editable -> PanelMode.IDLE
                    session.moving -> PanelMode.MOVING
                    else -> PanelMode.SELECTED
                }
                val motion = MaterialTheme.motionScheme
                AnimatedContent(mode, transitionSpec = {
                    fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
                }, label = "panel") { target ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).heightIn(min = panelHeight - 32.dp),
                        verticalArrangement = Arrangement.Center) {
                        val item = session.selected
                        when {
                            target == PanelMode.MOVING && item != null -> MovePanel(session, item, notify)
                            target == PanelMode.SELECTED && item != null -> SelectionPanel(session, item, notify,
                                onDetail = { sheet = ShelfSheet.DETAIL })
                            else -> IdlePanel(session, onAddBook,
                                onItems = { sheet = ShelfSheet.ITEMS }, onProps = { sheet = ShelfSheet.PROPS },
                                onSettings = { sheet = ShelfSheet.SETTINGS })
                        }
                    }
                }
            }
        }
    }

    if (candidates.isNotEmpty()) AlertDialog(
        onDismissRequest = { candidates = emptyList() },
        title = { Text("どれを選びますか？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                candidates.mapNotNull { id -> document.items.firstOrNull { it.id == id } }.forEach { item ->
                    ListItem(onClick = { session.select(item.id); candidates = emptyList() },
                        supportingContent = { Text(itemKind(item)) }) { Text(itemTitle(document, item)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { candidates = emptyList() }) { Text("キャンセル") } },
    )
    when (sheet) {
        ShelfSheet.ITEMS -> ItemListSheet(document, onPick = { session.select(it); sheet = null }, onDismiss = { sheet = null })
        ShelfSheet.PROPS -> PropSheet(document, onPick = { prop ->
            sheet = null
            notify.edit(session.edit { it.addProp(prop) }, "棚に小物を置く場所がありません。本を動かすか外してください。")
        }, onDismiss = { sheet = null })
        ShelfSheet.DETAIL -> if (selected != null) ItemDetailSheet(session, selected, notify, onMeasure = { measuring = it }, onDismiss = { sheet = null })
            else LaunchedEffect(Unit) { sheet = null }
        ShelfSheet.SETTINGS -> ShelfSettingsSheet(document, enabled = session.editable && !session.saving,
            onApply = { next ->
                sheet = null
                if (next != document) notify.edit(session.edit { it.replaceWithUndo(next) })
                session.resetCamera()
            }, onDismiss = { sheet = null })
        null -> {}
    }
    measuring?.let { book ->
        BookSizeDialog(book, onDismiss = { measuring = null }) { updated ->
            val fits = ShelfGeometry.isValid(session.document.copy(books = session.document.books.map { if (it.id == updated.id) updated else it }))
            if (fits && updated != book) notify.edit(session.edit { it.updateBook(updated) })
            fits
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { if (!session.recovering) confirmReset = false },
        icon = { Glyph(R.drawable.ic_error, null) },
        title = { Text("本棚を初期化しますか？") },
        text = { Text("読み込めなかった保存データを消して、空の棚から始めます。この操作は取り消せません。") },
        confirmButton = {
            TextButton(enabled = !session.saving, onClick = {
                session.reset { notify.show("初期化できませんでした。端末の空き容量を確認してください。") }
                confirmReset = false
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("初期化する") }
        },
        dismissButton = { TextButton(enabled = !session.recovering, onClick = { confirmReset = false }) { Text("キャンセル") } },
    )
}

@Composable
private fun StorageBanner(problem: StorageProblem, session: ShelfSession, notify: Notifier, onReset: () -> Unit, modifier: Modifier) {
    val message = when (problem) {
        StorageProblem.UNREADABLE -> "保存した本棚を読み込めませんでした。データを守るため、編集を止めています。"
        StorageProblem.UNWRITABLE -> "本棚を保存できませんでした。変更はまだ残っています。空き容量を確認して、もう一度保存してください。"
    }
    ErrorBanner(message, modifier) {
        if (problem == StorageProblem.UNREADABLE) {
            TextButton(onClick = onReset, enabled = !session.saving) { Text("初期化") }
            TextButton(onClick = { session.retry { notify.show("まだ読み込めません。保存データはそのまま残しています。") } },
                enabled = !session.saving) { Text("読み込み直す") }
        } else {
            TextButton(onClick = { session.retry { notify.show("まだ保存できません。変更は端末のメモリに残っています。") } },
                enabled = !session.saving) { Text("保存し直す") }
        }
    }
}

@Composable
private fun IdlePanel(session: ShelfSession, onAddBook: () -> Unit, onItems: () -> Unit, onProps: () -> Unit, onSettings: () -> Unit) {
    val compact = LocalDensity.current.fontScale > 1.15f
    val empty = session.document.items.isEmpty()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (empty) "＋ から最初の一冊を追加しましょう" else "本をタップすると、向きを変えたり動かしたりできます",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            floatingActionButton = {
                TooltipIcon("本を追加") {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onAddBook,
                        modifier = Modifier.semantics { contentDescription = "本を追加" }.testTag("add-book")) {
                        Glyph(R.drawable.ic_add, null)
                    }
                }
            },
        ) {
            ToolbarAction(R.drawable.ic_format_list_bulleted, "一覧", compact, session.loaded && !empty, onItems, Modifier.testTag("open-item-list"))
            ToolbarAction(R.drawable.ic_potted_plant, "小物", compact, session.editable, onProps, Modifier.testTag("open-props"))
            ToolbarAction(R.drawable.ic_tune, "棚の設定", compact, session.editable && !session.saving, onSettings, Modifier.testTag("shelf-settings"))
        }
    }
}

@Composable
private fun ToolbarAction(icon: Int, label: String, compact: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (compact) TooltipIcon(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) { Glyph(icon, label) }
    } else TextButton(onClick = onClick, enabled = enabled, modifier = modifier, shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
        contentPadding = PaddingValues(horizontal = 12.dp)) {
        Glyph(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun SelectionPanel(session: ShelfSession, item: ShelfItem, notify: Notifier, onDetail: () -> Unit) {
    val document = session.document
    val book = item.bookIds.firstOrNull()?.let(document::book)
    Column(Modifier.fillMaxWidth().testTag("selection-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (book != null) BookThumb(book, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(itemTitle(document, item), style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(book?.author?.ifBlank { "著者不明" } ?: "小物",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TipIconButton(R.drawable.ic_close, "選択を解除") { session.select(null) }
        }
        if (item.propId == null) OrientationGroup(item, onChange = { orientation ->
            notify.edit(session.edit { it.orient(item.id, orientation) },
                "${orientation.label()}にすると棚に収まりません。空いている場所へ動かしてから変えてください。")
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                session.moving = true
                session.preview = exactPreview(document, item, item.x)
            }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).testTag("start-move")) {
                Glyph(R.drawable.ic_open_with, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("移動", maxLines = 1)
            }
            OutlinedButton(onClick = onDetail, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).testTag("open-detail")) {
                Glyph(R.drawable.ic_info, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("詳細", maxLines = 1)
            }
            OutlinedButton(onClick = {
                val title = itemTitle(document, item)
                if (session.edit { it.remove(item.id) } == EditResult.APPLIED) notify.undoable("「$title」を棚から外しました")
            }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).testTag("remove-item"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Glyph(R.drawable.ic_do_not_disturb_on, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("外す", maxLines = 1)
            }
        }
    }
}

/** Connected toggle buttons, one per way of placing the book. Stacks can only lie flat. */
@Composable
private fun OrientationGroup(item: ShelfItem, onChange: (Orientation) -> Unit) {
    val options = listOf(Orientation.SPINE, Orientation.COVER, Orientation.FLAT)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, orientation ->
            ToggleButton(
                checked = item.orientation == orientation,
                onCheckedChange = { if (item.orientation != orientation) onChange(orientation) },
                enabled = item.bookIds.size == 1 || orientation == Orientation.FLAT,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton }.testTag("orientation-${orientation.name}"),
                colors = segmentColors(),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                if (item.orientation == orientation) {
                    Glyph(R.drawable.ic_check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                }
                Text(orientation.label(), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MovePanel(session: ShelfSession, item: ShelfItem, notify: Notifier) {
    val document = session.document
    val preview = session.preview ?: exactPreview(document, item, item.x)
    fun nudge(delta: Float) { session.preview = exactPreview(document, item, preview.x + delta, preview.row) }
    Column(Modifier.fillMaxWidth().testTag("move-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("「${itemTitle(document, item)}」を移動", style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("棚の置きたい場所をタップするか、矢印で少しずつ動かします", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { nudge(-NUDGE) }, shapes = IconButtonDefaults.shapes()) { Glyph(R.drawable.ic_chevron_left, "左へ動かす") }
            Text(if (preview.valid) "ここに置けます" else "重なるため置けません", Modifier.weight(1f).testTag("move-status"),
                color = if (preview.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalIconButton(onClick = { nudge(NUDGE) }, shapes = IconButtonDefaults.shapes()) { Glyph(R.drawable.ic_chevron_right, "右へ動かす") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { session.moving = false; session.preview = null }) { Text("キャンセル") }
            Button(onClick = {
                if (preview.row == item.row && preview.x == item.x) { session.moving = false; session.preview = null }
                else if (notify.edit(session.edit { it.move(item.id, preview.row, preview.x) }) == EditResult.APPLIED) session.moving = false
            }, enabled = preview.valid, shapes = ButtonDefaults.shapes(), modifier = Modifier.testTag("confirm-move")) {
                Glyph(R.drawable.ic_check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("ここに置く")
            }
        }
    }
}

/** Fixed, so selecting a book never resizes the 3D view and moves the camera. */
@Composable
internal fun shelfPanelHeight(): Dp = (196f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

internal fun exactPreview(document: ShelfDocument, item: ShelfItem, x: Float, row: Int = item.row): PlacementPreview =
    PlacementPreview(row, x, x.isFinite() && ShelfGeometry.isValid(document.copy(items = document.items.map {
        if (it.id == item.id) it.copy(row = row, x = x) else it
    })), false)

@Composable
private fun ZoomControls(session: ShelfSession, modifier: Modifier) {
    val viewport = session.viewport
    HorizontalFloatingToolbar(expanded = true, modifier = modifier) {
        TipIconButton(R.drawable.ic_zoom_in, "拡大", enabled = viewport.zoom < 2.5f) {
            session.viewport = viewport.copy(zoom = (viewport.zoom + .25f).coerceAtMost(2.5f))
        }
        TipIconButton(R.drawable.ic_zoom_out, "縮小", enabled = viewport.zoom > 1f) {
            session.viewport = viewport.copy(zoom = (viewport.zoom - .25f).coerceAtLeast(1f))
        }
        TipIconButton(R.drawable.ic_fit_screen, "全体を表示", enabled = viewport.zoom != 1f || viewport.panX != 0f || viewport.panY != 0f) {
            session.resetCamera()
        }
    }
}

@Composable
internal fun TooltipIcon(label: String, content: @Composable () -> Unit) {
    TooltipBox(TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above), { PlainTooltip { Text(label) } },
        rememberTooltipState(), content = content)
}

@Composable
internal fun TipIconButton(icon: Int, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipIcon(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier, shapes = IconButtonDefaults.shapes()) { Glyph(icon, label) }
    }
}

/** A flat map of the shelf keeps the destination visible while a finger covers the book. */
@Composable
private fun PlacementGuide(document: ShelfDocument, selected: ShelfItem, placement: PlacementPreview, modifier: Modifier) {
    val width = ShelfGeometry.dimensions(selected, document).width
    val shelfWidth = ShelfGeometry.width(document)
    val status = if (placement.valid) "ここに置けます" else "重なるため置けません"
    val accent = if (placement.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val others = MaterialTheme.colorScheme.outlineVariant
    Surface(modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("placement-guide"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (placement.valid) R.drawable.ic_check else R.drawable.ic_error, null, Modifier.size(18.dp), accent)
                Spacer(Modifier.width(8.dp))
                Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    color = accent, style = MaterialTheme.typography.labelLarge)
            }
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 8.dp).semantics { contentDescription = "配置先の見取り図。$status" }) {
                fun x(value: Float) = (value / shelfWidth + .5f) * size.width
                clipRect {
                    val floor = size.height - 2.dp.toPx()
                    drawLine(others, Offset(0f, floor), Offset(size.width, floor), 2.dp.toPx())
                    document.items.filter { it.id != selected.id && it.row == placement.row }.forEach { item ->
                        val itemWidth = ShelfGeometry.dimensions(item, document).width
                        drawRect(others, Offset(x(item.x - itemWidth / 2), floor - 10.dp.toPx()), Size(itemWidth / shelfWidth * size.width, 10.dp.toPx()))
                    }
                    drawRect(accent, Offset(x(placement.x - width / 2), floor - 14.dp.toPx()),
                        Size((width / shelfWidth * size.width).coerceAtLeast(3.dp.toPx()), 14.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun ShelfViewport(
    activity: ComponentActivity, document: ShelfDocument, selectedId: String?, viewport: ViewportState,
    preview: PlacementPreview?, enabled: Boolean, onViewport: (ViewportState) -> Unit, onTap: (Offset) -> Unit,
    onDrag: (Offset, Offset) -> Unit, onDrop: () -> Unit, onCancel: () -> Unit,
) {
    val renderer = remember { createShelfRenderer() }
    var coversRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(document.books) { document.books.filter { b -> document.items.any { b.id in it.bookIds } }.forEach { if (BookCovers.fetch(activity, it)) coversRevision++ } }
    val inputEnabled by rememberUpdatedState(enabled)
    val minimumTouchTargetPx by rememberUpdatedState(with(LocalDensity.current) { 48.dp.toPx() })
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by rememberUpdatedState(viewport)
    val selected by rememberUpdatedState(selectedId)
    val currentDocument by rememberUpdatedState(document)
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    val changeViewport by rememberUpdatedState(onViewport)
    DisposableEffect(renderer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) renderer.setActive(inputEnabled)
            if (event == Lifecycle.Event.ON_PAUSE) { renderer.setActive(false); cancel() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        renderer.setActive(enabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); renderer.setActive(false); renderer.release() }
    }
    SideEffect { renderer.setActive(enabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    Box(Modifier.fillMaxSize().onSizeChanged { if (it.width > 0 && it.height > 0) onViewport(viewport.copy(width = it.width, height = it.height)) }) {
        AndroidView(factory = { renderer.createView(activity) }, modifier = Modifier.fillMaxSize(),
            update = { coversRevision; renderer.update(SceneComposer.compose(document, selectedId, viewport, preview)) })
        Box(Modifier.fillMaxSize().testTag("shelf-viewport").semantics {
            contentDescription = "本棚の立体表示。${bookCount(document)}冊。本をタップすると選択できます。下の「一覧」からも選べます。"
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!inputEnabled) return@awaitEachGesture
                val start = down.position
                var last = start
                var dragging = false
                var transformed = false
                var cancelled = false
                var completed = false
                val mayDrag = selected != null && ShelfProjection.pick(start.x, start.y, currentDocument, state, minimumTouchTargetPx).contains(selected)
                try {
                    do {
                        val event = awaitPointerEvent()
                        // Android ACTION_CANCEL arrives as consumed synthetic ups. It must never
                        // become a drop; ancestor gesture interception follows the same contract.
                        if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            if (!transformed) { cancel(); dragging = false }
                            transformed = true
                            val zoom = (state.zoom * event.calculateZoom()).coerceIn(1f, 2.5f)
                            val pan = event.calculatePan()
                            changeViewport(state.copy(zoom = zoom, panX = (state.panX + pan.x).coerceIn(-state.width * .65f, state.width * .65f), panY = (state.panY + pan.y).coerceIn(-state.height * .65f, state.height * .65f)))
                        } else if (!transformed) {
                            event.changes.firstOrNull { it.id == down.id }?.let { change ->
                                last = change.position
                                if (change.pressed && mayDrag && (last - start).getDistance() > viewConfiguration.touchSlop) dragging = true
                                if (dragging) drag(last, start)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (!cancelled && !transformed) { if (dragging) drop() else if ((last - start).getDistance() <= viewConfiguration.touchSlop) tap(last) }
                    completed = true
                } finally { if (!completed || cancelled || transformed) cancel() }
            }
        })
    }
}
