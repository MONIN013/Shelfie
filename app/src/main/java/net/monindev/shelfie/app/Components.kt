@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.monindev.shelfie.core.*
import net.monindev.shelfie.render.BookCovers

@Composable
internal fun Glyph(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) = Icon(painterResource(id), contentDescription, modifier, tint)

/** Segmented list items on a plain surface need a container to read as separate rows. */
@Composable
internal fun surfaceListColors(): ListItemColors =
    ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** Unselected segments need a visible container on the surface-container panels. */
@Composable
internal fun segmentColors(): ToggleButtonColors =
    ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)

@Composable
internal fun rememberFullSheetState(): SheetState =
    rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))

@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
}

/** A friendly placeholder for empty lists. The shape gives the screen a focal point. */
@Composable
internal fun EmptyState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center) {
            Glyph(icon, null, Modifier.size(40.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

@Composable
internal fun PageLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        ContainedLoadingIndicator(Modifier.semantics { contentDescription = "読み込み中" })
    }
}

@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (actions == null) 12.dp else 4.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(R.drawable.ic_error, null, Modifier.padding(top = 2.dp).size(20.dp))
                Text(message, Modifier.weight(1f).padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
    }
}

/** Pill-shaped search input in the style of the Material search bar. */
@Composable
internal fun SearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, onSearch: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val keyboard = LocalSoftwareKeyboardController.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(query, onQueryChange, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Glyph(R.drawable.ic_search, null) },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQueryChange("") }) { Glyph(R.drawable.ic_close, "検索語を消去") }
        }) else null,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(focusedContainerColor = container, unfocusedContainerColor = container, disabledContainerColor = container,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch() }))
}

/** An initial inside an expressive shape stands in for profile photos, which Shelfie does not store. */
@Composable
internal fun Avatar(name: String, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer)
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).ifEmpty { "?" }, color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = if (size >= 64.dp) MaterialTheme.typography.headlineMediumEmphasized else MaterialTheme.typography.titleMediumEmphasized)
    }
}

/** Shows the downloaded cover; otherwise a title cover like the one drawn on the shelf. */
@Composable
internal fun BookThumb(book: BookInfo, width: Dp = 40.dp) {
    val context = LocalContext.current
    var image by remember(book.coverUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(book.coverUrl) {
        if (BookCovers.fetch(context, book)) image = withContext(Dispatchers.IO) { BookCovers.bitmap(context, book)?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(if (width >= 80.dp) 8.dp else 4.dp)
    val modifier = Modifier.width(width).aspectRatio(book.widthMm.toFloat() / book.heightMm).clip(shape)
    image?.let { Image(it, "${book.title}の表紙", modifier, contentScale = ContentScale.Crop) } ?: Box(
        modifier.background(bookTone(book.id)).padding(if (width >= 80.dp) 8.dp else 3.dp)
            .clearAndSetSemantics {},
    ) {
        Text(book.title, color = Color.White, fontSize = if (width >= 80.dp) 12.sp else 7.sp,
            lineHeight = if (width >= 80.dp) 15.sp else 8.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** Author and physical summary on separate lines so units never wrap on their own. */
@Composable
internal fun BookLines(book: BookInfo) {
    Column {
        if (book.author.isNotBlank()) Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(bookSummary(book))
    }
}

/** Same palette as the generated covers on the shelf, so a book looks alike in lists and in 3D. */
private val BookTones = listOf(Color(0xFF544465), Color(0xFF315D51), Color(0xFF46687F), Color(0xFF805448))
internal fun bookTone(id: String): Color = BookTones[Math.floorMod(id.hashCode(), BookTones.size)]
