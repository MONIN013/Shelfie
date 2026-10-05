package net.monindev.shelfie.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import net.monindev.shelfie.core.*

@Serializable internal data class Account(val id: String, val username: String, val displayName: String)
@Serializable internal data class Session(val token: String, val user: Account)
@Serializable internal data class PublicPost(val id: String, val title: String, val note: String, val displayName: String,
    val publishedAt: Long, val document: ShelfDocument, val url: String)
@Serializable internal data class FeedCursor(val before: Long, val cursorId: String)
@Serializable internal data class FeedPage(val posts: List<PublicPost>, val next: FeedCursor? = null)
@Serializable internal data class CloudShelf(val title: String, val note: String, val document: ShelfDocument, val revision: Int)
@Serializable internal data class CloudState(val shelf: CloudShelf? = null, val postId: String? = null)
@Serializable private data class SearchBooks(val books: List<BookInfo>)
internal class ServiceError(val status: Int, override val message: String) : Exception(message)

/** The bearer token is encrypted using a non-exportable Android Keystore key. */
internal class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("service-session", Context.MODE_PRIVATE)
    private val alias = "shelfie-session-v1"
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): Session? {
        val encoded = prefs.getString("encrypted", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            json.decodeFromString<Session>(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { clear(); null }
    }
    fun write(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        check(prefs.edit().putString("encrypted",Base64.encodeToString(cipher.iv + cipher.doFinal(json.encodeToString(session).toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).commit())
    }
    fun clear() { check(prefs.edit().remove("encrypted").commit()) }
}

internal class ServiceClient(context: Context, private val baseUrl: String = BuildConfig.SERVICE_URL) {
    val sessions = SessionStore(context)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun request(path: String, method: String = "GET", data: JsonObject? = null, auth: Boolean = true): JsonElement = withContext(Dispatchers.IO) {
        val token = if (auth) sessions.read()?.token else null
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (data != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(4_194_304) } ?: ByteArray(0)
            val value = runCatching { json.parseToJsonElement(String(bytes,Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299) {
                if (status == 401 && auth) sessions.clear()
                throw ServiceError(status, (value as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull ?: "サーバーに接続できませんでした。")
            }
            value ?: throw ServiceError(502,"サーバーの応答を読み込めませんでした。")
        } finally { connection.disconnect() }
    }
    suspend fun login(username: String, password: String, displayName: String?, register: Boolean): Session {
        val response = request("/v1/auth/${if(register) "register" else "login"}","POST",buildJsonObject {
            put("username",username);put("password",password); if(displayName != null) put("displayName",displayName)
        },auth=false)
        return json.decodeFromJsonElement<Session>(response).also { withContext(Dispatchers.IO) { sessions.write(it) } }
    }
    /** Shelves in an unsupported format are skipped, never drawn. */
    suspend fun feed(query: String, cursor: FeedCursor? = null): FeedPage = json.decodeFromJsonElement<FeedPage>(request(
        "/v1/feed?q=${encode(query)}" + (cursor?.let { "&before=${it.before}&cursorId=${it.cursorId}" } ?: ""),auth=false))
        .let { page -> page.copy(posts = page.posts.filter { ShelfGeometry.isValid(it.document) }) }
    suspend fun post(id: String): PublicPost = json.decodeFromJsonElement<PublicPost>(request("/v1/posts/${checkedId(id)}",auth=false))
        .also { if (!ShelfGeometry.isValid(it.document)) throw ServiceError(422, "この棚は現在のアプリでは表示できない形式です。") }
    suspend fun search(query: String): List<BookInfo> = json.decodeFromJsonElement<SearchBooks>(request("/v1/books?q=${encode(query)}",auth=false)).books
    suspend fun cloud(): CloudState = json.decodeFromJsonElement(request("/v1/me/shelf"))
    suspend fun save(title: String, note: String, document: ShelfDocument, revision: Int): Int = request("/v1/me/shelf","PUT",buildJsonObject {
        put("title",title);put("note",note);put("document",json.encodeToJsonElement(document));put("revision",revision)
    }).jsonObject.getValue("revision").jsonPrimitive.int
    suspend fun publish(revision: Int): PublicPost = json.decodeFromJsonElement(request("/v1/me/publication","POST",buildJsonObject { put("revision",revision) }))
    suspend fun logout() { request("/v1/auth/logout","POST",buildJsonObject {}); withContext(Dispatchers.IO) { sessions.clear() } }
    private fun checkedId(id: String): String { require(id.matches(Regex("[a-f0-9-]{36}"))); return id }
    private fun encode(s: String) = URLEncoder.encode(s,"UTF-8")
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk); if (count < 0) break
        check(out.size() + count <= limit) { "応答が大きすぎます。" }; out.write(chunk,0,count)
    }
    return out.toByteArray()
}
