package net.monindev.shelfie.app

import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.monindev.shelfie.core.ShelfCodec
import net.monindev.shelfie.core.ShelfDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses real AtomicFile failures; the normal shared UI performs all recovery actions. */
@RunWith(AndroidJUnit4::class)
class ShelfieRecoveryTest {
    companion object {
        @BeforeClass @JvmStatic
        fun requireTestApplication() {
            assumeTrue(BuildConfig.APPLICATION_ID == "net.monindev.shelfie.validation.filament" || android.os.Build.HARDWARE == "ranchu")
        }
    }

    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val shelfFile: File get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "shelf.json")
    private val blockedWrite: File get() = File(shelfFile.path + ".new")

    @Before
    fun launchWithAnActuallyCorruptFile() {
        AtomicFile(shelfFile).delete()
        shelfFile.writeText("{broken shelf")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("読み込み直す").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun leaveAUsableShelfForOtherTests() {
        scenario?.close()
        // This is only the empty directory created by this test, never recursive deletion.
        if (blockedWrite.isDirectory) check(blockedWrite.delete())
        val atomic = AtomicFile(shelfFile)
        atomic.delete()
        val stream = atomic.startWrite()
        try {
            stream.write(ShelfCodec.encode(ShelfDocument(items = emptyList())).toByteArray())
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    @Test
    fun resetAfterCorruptionThenFailedRetryPreservesTheUnsavedEdit() {
        compose.onNodeWithText("初期化", substring = false).performClick()
        compose.onNodeWithText("初期化する", substring = false).performClick()
        compose.awaitSaved()
        assertTrue(readShelf().items.isEmpty())
        compose.onNodeWithTag("shelf-settings").performClick()
        compose.onNodeWithTag("shelf-width").performTextReplacement("90")
        compose.onNodeWithTag("apply-shelf-configuration").performClick()
        compose.awaitSaved()
        assertEquals(900, readShelf().shelf.widthMm)

        // AtomicFile writes to shelf.json.new. A directory forces a real open failure
        // without modifying the last valid shelf.json or introducing production mocks.
        assertTrue(blockedWrite.mkdir())
        compose.onNodeWithTag("open-props").performClick()
        compose.onNodeWithTag("prop-pebble").performClick()
        awaitSaveRetry()
        compose.onNodeWithText("読み込み直す").assertDoesNotExist()
        assertEquals(0, readShelf().items.count { it.propId != null })

        // Retry while the write remains blocked. The new prop must stay in memory.
        compose.onNodeWithText("保存し直す").performClick()
        awaitSaveRetry()
        assertEquals(0, readShelf().items.count { it.propId != null })

        assertTrue(blockedWrite.delete())
        compose.onNodeWithText("保存し直す").performClick()
        compose.awaitSaved()
        assertEquals(1, readShelf().items.count { it.propId != null })

        scenario!!.recreate()
        compose.awaitSaved()
        assertEquals(1, readShelf().items.count { it.propId != null })
    }

    private fun awaitSaveRetry() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("保存し直す") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun readShelf() = ShelfCodec.decode(shelfFile.readText())
}
