package net.monindev.shelfie.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.monindev.shelfie.core.ShelfDocument
import net.monindev.shelfie.render.SceneComposer
import net.monindev.shelfie.render.ViewportState

@Composable internal fun ReadOnlyShelf(activity: Activity, document: ShelfDocument, modifier: Modifier = Modifier) {
    val renderer=remember {createShelfRenderer()}
    var viewport by remember {mutableStateOf(ViewportState())}
    var coversRevision by remember {mutableIntStateOf(0)}
    LaunchedEffect(document.books) { document.books.filter{b->document.items.any{b.id in it.bookIds}}.forEach { if(net.monindev.shelfie.render.BookCovers.fetch(activity,it)) coversRevision++ } }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(renderer,lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME) renderer.setActive(true)
            if(event==Lifecycle.Event.ON_PAUSE) renderer.setActive(false)
        }
        lifecycle.addObserver(observer)
        renderer.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {lifecycle.removeObserver(observer);renderer.setActive(false);renderer.release()}
    }
    Box(modifier.onSizeChanged {if(it.width>0&&it.height>0)viewport=viewport.copy(width=it.width,height=it.height)}) {
        AndroidView(factory={renderer.createView(activity)},modifier=Modifier.fillMaxSize(),update={coversRevision;renderer.update(SceneComposer.compose(document,null,viewport))})
    }
}
