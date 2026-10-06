package net.monindev.shelfie.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import net.monindev.shelfie.core.*
import net.monindev.shelfie.render.BookPhotos
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The face of a book is cut from a stored photo by moving its corners; the edit is undoable and saved. */
@RunWith(AndroidJUnit4::class)
class PhotoCropFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var photo: File
    private val corners = listOf(.40f, .20f, .50f, .20f, .50f, .80f, .40f, .80f)

    @Before fun setup() {
        assumeTrue(BuildConfig.APPLICATION_ID == "net.monindev.shelfie.validation.filament" || android.os.Build.HARDWARE == "ranchu")
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 90, 140)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val name = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
        photo = File(BookPhotos.directory(TestShelves.context).apply { mkdirs() }, name).apply { writeBytes(bytes) }
        val book = BookInfo("manual-photo", "写真の本", "著者", 105, 148, 240, spineRegion = PhotoRegion(name, corners))
        TestShelves.install(ShelfDocument(shelf = ShelfSpec(), books = listOf(book), items = listOf(ShelfItem("one", listOf(book.id), row = 0, x = 0f))))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.awaitSaved()
    }

    @After fun close() { scenario?.close(); if (::photo.isInitialized) photo.delete() }

    @Test fun movingACornerSavesTheRegionAndUndoRestoresIt() {
        compose.openDetail("写真の本")
        compose.onNodeWithText("写真から切り出し").assertExists()
        compose.onNodeWithTag("adjust-spine").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("crop-corner-1").fetchSemanticsNodes().isNotEmpty() }
        val corner = compose.onNodeWithTag("crop-corner-1").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(corner)
            moveTo(corner + Offset(0f, -40f), delayMillis = 100)
            moveTo(corner + Offset(40f, -40f), delayMillis = 100)
            up()
        }
        compose.onNodeWithTag("save-crop").assertIsEnabled().performClick(); compose.awaitSaved()
        val saved = TestShelves.read().books.single().spineRegion!!
        assertEquals(photo.name, saved.image)
        assertTrue(saved.corners[2] > corners[2])
        assertTrue(saved.corners[3] < corners[3])
        assertEquals(corners.take(2), saved.corners.take(2))
        compose.onNodeWithContentDescription("元に戻す").performClick(); compose.awaitSaved()
        assertEquals(corners, TestShelves.read().books.single().spineRegion!!.corners)
    }

    @Test fun removingThePhotoFallsBackToTheTitleSpine() {
        compose.openDetail("写真の本")
        compose.onNode(hasText("外す") and hasAnyAncestor(hasTestTag("item-detail"))).performScrollTo().performClick(); compose.awaitSaved()
        assertNull(TestShelves.read().books.single().spineRegion)
        compose.onNodeWithTag("pick-spine").assertExists()
    }
}
