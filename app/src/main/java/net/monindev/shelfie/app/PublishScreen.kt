@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package net.monindev.shelfie.app

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class Confirm { PUBLISH, UNPUBLISH, RESTORE, DELETE }

/** Publishing the shelf is the one task that needs an account; signing in happens here, in context. */
@Composable
internal fun PublishScreen(activity: Activity, service: ServiceSession, session: ShelfSession, thumbnails: ShelfThumbnails,
    notify: Notifier, onDismiss: () -> Unit) {
    val request = rememberRequest(service)
    val account = service.account
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val saved = service.cloud?.shelf
    var title by rememberSaveable(saved?.revision) { mutableStateOf(saved?.title ?: "私の本棚") }
    var note by rememberSaveable(saved?.revision) { mutableStateOf(saved?.note ?: "") }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(account?.id) {
        if (account != null && service.cloud == null) request.run { service.cloud = service.client.cloud() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("棚を公開") }, navigationIcon = { TipIconButton(R.drawable.ic_close, "閉じる", onClick = onDismiss) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (request.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().testTag("service-loading"))
            request.error?.let { error ->
                ErrorBanner(error, Modifier.testTag("service-error")) {
                    if (account != null) TextButton(onClick = { request.run { service.cloud = service.client.cloud() } }) { Text("再読み込み") }
                }
            }
            ShelfImage(thumbnails, "mine:${session.document.hashCode()}", session.document, "公開する棚")
            if (account == null) {
                Text("公開すると、棚が「みんなの棚」に並び、リンクで誰とでも共有できます。公開にはアカウントが必要です。",
                    style = MaterialTheme.typography.bodyLarge)
                SignInForm(service, notify)
            } else {
                Publication(activity, service, session, request, notify, title, { title = it }, note, { note = it }, onConfirm = { confirm = it })
                AccountSection(account, request, onLogout = {
                    request.run { service.client.logout(); service.signedOut(); notify.show("ログアウトしました") }
                }, onDelete = { confirm = Confirm.DELETE })
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(action, request, onDismiss = { confirm = null }) { password ->
            when (action) {
                Confirm.PUBLISH -> request.run {
                    publish(service, session, title.trim(), note.trim())
                    confirm = null
                    notify.show("棚を公開しました")
                }
                Confirm.UNPUBLISH -> request.run {
                    service.client.request("/v1/me/publication", "DELETE")
                    service.cloud = service.cloud?.copy(postId = null)
                    service.feedKey = null
                    confirm = null
                    notify.show("棚を非公開にしました")
                }
                Confirm.RESTORE -> {
                    confirm = null
                    val restored = service.cloud?.shelf?.document
                    when {
                        restored == null -> Unit
                        restored == session.document -> notify.show("端末の棚はクラウドと同じです")
                        notify.edit(session.edit { it.replaceWithUndo(restored) }, "クラウドの棚を読み込めませんでした。端末の棚はそのままです。") == EditResult.APPLIED -> {
                            session.resetCamera()
                            notify.undoable("クラウドの棚を読み込みました")
                        }
                    }
                }
                Confirm.DELETE -> request.run {
                    service.client.request("/v1/me", "DELETE", buildJsonObject { put("password", password) })
                    service.client.sessions.clear()
                    service.signedOut()
                    service.feedKey = null
                    confirm = null
                    notify.show("アカウントを削除しました")
                }
            }
        }
    }
}

private suspend fun publish(service: ServiceSession, session: ShelfSession, title: String, note: String) {
    val document = session.document
    val revision = service.client.save(title, note, document, service.cloud?.shelf?.revision ?: 0)
    service.cloud = service.cloud?.copy(shelf = CloudShelf(title, note, document, revision))
    val post = service.client.publish(revision)
    service.cloud = service.cloud?.copy(postId = post.id)
    service.feedKey = null
}

@Composable
private fun Publication(activity: Activity, service: ServiceSession, session: ShelfSession, request: Request, notify: Notifier,
    title: String, onTitle: (String) -> Unit, note: String, onNote: (String) -> Unit, onConfirm: (Confirm) -> Unit) {
    val cloud = service.cloud
    val published = cloud?.postId != null
    val ready = cloud != null && !request.busy && session.editable && !session.saving
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (published) "公開中" else "非公開", Modifier.weight(1f).testTag("publication-state"), style = MaterialTheme.typography.titleLargeEmphasized)
        Glyph(if (published) R.drawable.ic_public else R.drawable.ic_public_off, null, tint = MaterialTheme.colorScheme.primary)
    }
    Text(if (published) "みんなの棚と共有リンクで公開しています。編集した内容は「公開中の棚を更新」で反映されます。"
        else "公開すると、棚の本・棚の名前・ひとこと・表示名を誰でも見られるようになります。積読とお気に入りは公開されません。",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(title, { if (it.length <= 100) onTitle(it) }, Modifier.fillMaxWidth().testTag("shelf-title"),
        label = { Text("棚の名前") }, singleLine = true)
    OutlinedTextField(note, { if (it.length <= 1000) onNote(it) }, Modifier.fillMaxWidth().testTag("shelf-note"),
        label = { Text("ひとこと（任意）") }, minLines = 2)
    Button(onClick = { onConfirm(Confirm.PUBLISH) }, enabled = ready && title.isNotBlank(), shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("publish")) {
        Glyph(R.drawable.ic_public, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (published) "公開中の棚を更新" else "公開する")
    }
    if (published) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            request.run {
                val post = service.client.post(cloud!!.postId!!)
                activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, post.url)
                }, "棚を共有"))
            }
        }, enabled = !request.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
            Glyph(R.drawable.ic_share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("リンクを共有")
        }
        OutlinedButton(onClick = { onConfirm(Confirm.UNPUBLISH) }, enabled = !request.busy, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).testTag("unpublish")) {
            Glyph(R.drawable.ic_public_off, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("非公開にする")
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("クラウド保存", style = MaterialTheme.typography.titleMediumEmphasized)
    Text(cloud?.shelf?.let { "クラウドに${bookCount(it.document)}冊の棚を保存しています。機種変更のときは「端末に読み込む」で戻せます。" }
        ?: "公開とは別に、端末の棚をクラウドへ保存できます。保存した棚は公開されません。",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = {
            request.run {
                val document = session.document
                val revision = service.client.save(title.trim(), note.trim(), document, cloud?.shelf?.revision ?: 0)
                service.cloud = service.cloud?.copy(shelf = CloudShelf(title.trim(), note.trim(), document, revision))
                notify.show("クラウドに保存しました")
            }
        }, enabled = ready && title.isNotBlank(), shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f).testTag("save-cloud")) {
            Glyph(R.drawable.ic_cloud_upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("保存")
        }
        OutlinedButton(onClick = { onConfirm(Confirm.RESTORE) }, enabled = ready && cloud?.shelf != null, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f).testTag("restore-cloud")) {
            Glyph(R.drawable.ic_cloud_download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("端末に読み込む")
        }
    }
}

@Composable
private fun AccountSection(account: Account, request: Request, onLogout: () -> Unit, onDelete: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(account.displayName, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
            Text("@${account.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onLogout, enabled = !request.busy, modifier = Modifier.testTag("logout")) {
            Glyph(R.drawable.ic_logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("ログアウト")
        }
        TextButton(onClick = onDelete, enabled = !request.busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text("アカウントを削除")
        }
    }
}

@Composable
private fun ConfirmDialog(action: Confirm, request: Request, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val destructive = action == Confirm.DELETE
    AlertDialog(onDismissRequest = { if (!request.busy) onDismiss() },
        icon = { Glyph(when (action) {
            Confirm.PUBLISH -> R.drawable.ic_public
            Confirm.UNPUBLISH -> R.drawable.ic_public_off
            Confirm.RESTORE -> R.drawable.ic_cloud_download
            Confirm.DELETE -> R.drawable.ic_person_remove
        }, null) },
        title = { Text(when (action) {
            Confirm.PUBLISH -> "この棚を公開しますか？"
            Confirm.UNPUBLISH -> "非公開にしますか？"
            Confirm.RESTORE -> "クラウドの棚を読み込みますか？"
            Confirm.DELETE -> "アカウントを削除しますか？"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (action) {
                    Confirm.PUBLISH -> "棚の本・小物・棚の名前・ひとこと・表示名が、みんなの棚と共有リンクで公開されます。棚に置いていない本、積読、お気に入りは公開されません。"
                    Confirm.UNPUBLISH -> "みんなの棚と共有リンクから見られなくなります。"
                    Confirm.RESTORE -> "端末の棚を、クラウドに保存した棚で置き換えます。「元に戻す」で今の棚に戻せます。"
                    Confirm.DELETE -> "アカウント、公開中の棚、クラウドに保存した棚を削除します。端末の棚・積読・お気に入りは残ります。この操作は取り消せません。"
                })
                if (destructive) OutlinedTextField(password, { if (it.length <= 128) password = it }, Modifier.fillMaxWidth().testTag("delete-password"),
                    label = { Text("パスワード") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = !request.busy && (!destructive || password.length >= 12),
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
                modifier = Modifier.testTag("confirm-action")) {
                Text(when (action) {
                    Confirm.PUBLISH -> "公開する"
                    Confirm.UNPUBLISH -> "非公開にする"
                    Confirm.RESTORE -> "読み込む"
                    Confirm.DELETE -> "削除する"
                })
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !request.busy) { Text("キャンセル") } })
}

/** Signing in from a task that needs an account, without leaving the current screen. */
@Composable
internal fun SignInSheet(service: ServiceSession, notify: Notifier, title: String, body: String, onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberFullSheetState()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SignInForm(service, notify, onSignedIn)
        }
    }
}

@Composable
internal fun SignInForm(service: ServiceSession, notify: Notifier, onSignedIn: () -> Unit = {}) {
    val request = rememberRequest(service)
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val usernameOk = username.matches(Regex("[A-Za-z0-9_]{3,32}"))
    val valid = usernameOk && password.length >= 12 && (!register || displayName.isNotBlank())
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(false to "ログイン", true to "新規登録").forEachIndexed { index, (mode, label) ->
                ToggleButton(checked = register == mode, onCheckedChange = { register = mode }, enabled = !request.busy,
                    modifier = Modifier.weight(1f).semantics { role = Role.Tab }.testTag(if (mode) "auth-mode-register" else "auth-mode-login"),
                    colors = segmentColors(),
                    shapes = if (index == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                    Text(label)
                }
            }
        }
        request.error?.let { ErrorBanner(it, Modifier.testTag("service-error")) }
        OutlinedTextField(username, { if (it.length <= 32) username = it }, Modifier.fillMaxWidth().testTag("auth-username"),
            label = { Text("ユーザー名") }, supportingText = { Text("半角英数字と _（3〜32文字）") }, singleLine = true,
            isError = username.isNotEmpty() && !usernameOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
        if (register) OutlinedTextField(displayName, { if (it.length <= 60) displayName = it }, Modifier.fillMaxWidth().testTag("auth-display-name"),
            label = { Text("表示名") }, supportingText = { Text("公開した棚に表示されます") }, singleLine = true)
        OutlinedTextField(password, { if (it.length <= 128) password = it }, Modifier.fillMaxWidth().testTag("auth-password"),
            label = { Text("パスワード") }, supportingText = { Text("12文字以上") }, singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Glyph(if (reveal) R.drawable.ic_visibility_off else R.drawable.ic_visibility, if (reveal) "パスワードを隠す" else "パスワードを表示")
                }
            })
        if (register) Text("パスワードを忘れると再設定できません（メールアドレスは登録しません）。パスワード管理アプリなどに保存してください。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            request.run {
                val signedIn = service.client.login(username, password, if (register) displayName.trim() else null, register)
                service.signedOut()
                service.account = signedIn.user
                notify.show(if (register) "アカウントを作成しました" else "ログインしました")
                onSignedIn()
            }
        }, enabled = valid && !request.busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("auth-submit")) {
            Glyph(if (register) R.drawable.ic_person_add else R.drawable.ic_login, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (register) "アカウントを作成" else "ログイン")
        }
    }
}
