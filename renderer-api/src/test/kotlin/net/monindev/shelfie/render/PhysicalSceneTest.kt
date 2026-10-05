package net.monindev.shelfie.render

import net.monindev.shelfie.core.*
import org.junit.Assert.*
import org.junit.Test

class PhysicalSceneTest {
    @Test fun shelfResizeChangesPanelsAndCameraButNeverScalesBooks() {
        val document=TestBooks.document(ShelfItem("book",listOf("manual-b01"),row=0,x=0f)).copy(theme="oak")
        val viewport=ViewportState(width=1080,height=900)
        val small=SceneComposer.compose(document,null,viewport)
        val wide=SceneComposer.compose(document.copy(shelf=ShelfSpec(900,400,300)),null,viewport)
        assertArrayEquals(small.instances.single{it.id=="book"}.transform,wide.instances.single{it.id=="book"}.transform,.0001f)
        val smallFloor=small.instances.single{it.id=="shelf-floor"}.transform
        val wideFloor=wide.instances.single{it.id=="shelf-floor"}.transform
        assertEquals(.18f,wideFloor[5],.0001f)
        assertEquals(smallFloor[5],wideFloor[5],.0001f)
        assertEquals(9.36f,wideFloor[0],.0001f)
        assertTrue(wide.camera.verticalHalfExtent>small.camera.verticalHalfExtent)
    }
}
