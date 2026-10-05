package net.monindev.shelfie.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Short feedback. A newer message replaces the visible one instead of queueing behind it. */
internal class Notifier(private val host: SnackbarHostState, private val scope: CoroutineScope, private val session: ShelfSession) {
    fun show(text: String) = action(text, null) {}

    fun action(text: String, label: String?, onAction: () -> Unit) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(text, label, duration = if (label == null) SnackbarDuration.Short else SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    /** Undo applies only while the announced edit is still the latest one. */
    fun undoable(text: String) {
        val revision = session.revision
        action(text, "元に戻す") { if (session.revision == revision) edit(session.edit { it.undo() }) }
    }

    fun edit(result: EditResult, rejected: String = "棚に収まらないため変更できません。空いている場所を選んでください。"): EditResult {
        when (result) {
            EditResult.REJECTED -> show(rejected)
            EditResult.BLOCKED -> show("本棚を保存できる状態になるまで、変更はできません。")
            EditResult.APPLIED -> Unit
        }
        return result
    }
}

/** Account and community data shared by the screens, so switching tabs keeps lists. */
@Stable
internal class ServiceSession(val client: ServiceClient) {
    var account by mutableStateOf(client.sessions.read()?.user)
    var feed by mutableStateOf<List<PublicPost>?>(null)
    var next by mutableStateOf<FeedCursor?>(null)
    var feedKey by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var favoritesOnly by mutableStateOf(false)
    var openPost by mutableStateOf<PublicPost?>(null)
    var cloud by mutableStateOf<CloudState?>(null)

    fun refreshAccount() {
        val current = client.sessions.read()?.user
        if (current?.id != account?.id) signedOut()
        account = current
    }

    fun signedOut() {
        account = null
        cloud = null
    }
}

/** One request at a time per screen, with a readable error. */
@Stable
internal class Request(private val scope: CoroutineScope, private val service: ServiceSession) {
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = if (e is ServiceError) e.message else "通信できませんでした。接続を確認して、もう一度お試しください。"
                service.refreshAccount()
            } finally { busy = false }
        }
    }
}

@Composable
internal fun rememberRequest(service: ServiceSession): Request {
    val scope = rememberCoroutineScope()
    return remember(service) { Request(scope, service) }
}
