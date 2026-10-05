package net.monindev.shelfie.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.monindev.shelfie.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Fixtures require the local server and an emulator or the separate validation application ID. */
@RunWith(AndroidJUnit4::class)
class CommunityFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var client: ServiceClient
    private val password = "test-password-only-12345"
    private val accounts = mutableListOf<Pair<String, String>>()

    @Before fun setup() {
        assumeTrue(BuildConfig.SERVICE_URL == "http://127.0.0.1:8787" && (android.os.Build.HARDWARE == "ranchu" || BuildConfig.APPLICATION_ID == "net.monindev.shelfie.validation.filament"))
        client = ServiceClient(context)
        client.sessions.clear()
        TestShelves.install(ShelfDocument(items = emptyList()))
    }

    @After fun finish() {
        scenario?.close()
        if (::client.isInitialized) {
            runBlocking {
                accounts.forEach { (username, pass) ->
                    runCatching { client.login(username, pass, null, false); client.request("/v1/me", "DELETE", buildJsonObject { put("password", pass) }) }
                }
            }
            client.sessions.clear()
        }
    }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); compose.awaitSaved() }
    private fun serviceReady() {
        try { compose.waitUntil(45_000) { compose.onAllNodesWithTag("service-loading").fetchSemanticsNodes().isEmpty() }; compose.waitForIdle() }
        catch (error: Exception) { capture("community-failure"); throw error }
    }
    private fun capture(name: String) {
        // Native renderer frames run outside Compose's test clock; allow them to reach the display.
        android.os.SystemClock.sleep(600)
        val logs = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d --pid=${android.os.Process.myPid()} -s Filament:E")
        val renderErrors = android.os.ParcelFileDescriptor.AutoCloseInputStream(logs).bufferedReader().use { it.readText() }
        assertFalse("Generated book textures must have a matching image decoder", renderErrors.contains("Missing texture provider"))
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        try { File(context.getExternalFilesDir(null), "$name.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { screenshot.recycle() }
    }
    private fun accountName() = "test_" + UUID.randomUUID().toString().replace("-", "").take(16)
    private fun library() = File(context.filesDir, "library.json").readText()

    @Test fun manualEntryPlacesTheBookAndPersistsAcrossRestart() {
        launch()
        compose.onNodeWithTag("add-book").performClick()
        compose.onNodeWithTag("manual-entry").performClick()
        compose.onNodeWithTag("register-book-title").performTextInput("私が選んだ一冊")
        compose.onNodeWithTag("register-book-author").performTextInput("棚の作者")
        compose.onNodeWithTag("register-book-submit").performClick()
        compose.awaitSaved()
        val document = TestShelves.read()
        assertEquals("私が選んだ一冊", document.books.single().title)
        assertEquals(document.books.single().id, document.items.single().bookIds.single())
        scenario!!.recreate(); compose.awaitSaved()
        capture("registered-book")
        assertEquals(document, TestShelves.read())
        compose.selectFromList("私が選んだ一冊")
    }

    @Test fun publishVisitAndKeepABookWithoutAnAccount() {
        val owner = accountName(); accounts += owner to password
        val firstBook = BookInfo("manual-e2e", "出会いの一冊", "著者", 128, 188, 240, measuredThicknessMm = 22f)
        TestShelves.install(ShelfDocument(theme = "oak", shelf = ShelfSpec(), books = listOf(firstBook),
            items = listOf(ShelfItem("one", listOf(firstBook.id), row = 0, x = 0f))))
        launch()
        compose.onNodeWithTag("open-publish").performClick()
        compose.onNodeWithTag("auth-mode-register").performScrollTo().performClick()
        compose.onNodeWithTag("auth-username").performTextInput(owner)
        compose.onNodeWithTag("auth-display-name").performTextInput("テストの棚主")
        compose.onNodeWithTag("auth-password").performTextInput(password)
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick(); serviceReady()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("shelf-title").fetchSemanticsNodes().isNotEmpty() }; serviceReady()
        compose.onNodeWithTag("shelf-title").performTextReplacement("出会いの棚")
        compose.onNodeWithTag("publish").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-action").performClick(); serviceReady()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("公開中").fetchSemanticsNodes().isNotEmpty() }
        val postId = runBlocking { client.feed("出会いの棚") }.posts.single().id
        compose.onNodeWithTag("logout").performScrollTo().performClick(); serviceReady()
        compose.onNodeWithContentDescription("閉じる").performClick()

        // A visitor needs no account to find a shelf, save it and keep one of its books.
        compose.onNodeWithTag("tab-discover").performClick(); serviceReady()
        compose.onNodeWithTag("feed-query").performTextInput("出会いの棚")
        compose.onNodeWithTag("feed-query").performImeAction(); serviceReady()
        compose.onNodeWithTag("feed-post-$postId").performClick(); serviceReady()
        capture("public-shelf")
        compose.onNodeWithTag("toggle-favorite").performClick()
        compose.onNodeWithTag("want-${firstBook.id}").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("「出会いの一冊」を積読に追加しました").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(library().contains(postId))
        compose.onNodeWithTag("tab-reading").performClick()
        compose.onNodeWithTag("reading-${firstBook.id}").assertExists()

        // When the source shelf becomes private, the book stays and the shelf reference is forgotten.
        runBlocking { client.login(owner, password, null, false); client.request("/v1/me/publication", "DELETE"); client.sessions.clear() }
        compose.onNodeWithText("見つけた棚を開く").performClick(); serviceReady()
        compose.waitUntil(10_000) { !library().contains(postId) }
        compose.onNodeWithTag("tab-reading").performClick()
        compose.onNodeWithTag("reading-${firstBook.id}").assertExists()
        compose.onNodeWithText("見つけた棚を開く").assertDoesNotExist()
    }

    @Test fun encryptedSessionSurvivesRecreationAndLogoutInvalidatesIt() = runBlocking {
        val name = accountName(); accounts += name to password
        val session = client.login(name, password, "読者", true)
        assertEquals(session, SessionStore(context).read())
        val raw = File(context.applicationInfo.dataDir, "shared_prefs/service-session.xml").readText()
        assertFalse(raw.contains(session.token)); assertFalse(raw.contains(password))
        client.logout(); assertNull(SessionStore(context).read())
    }
}
