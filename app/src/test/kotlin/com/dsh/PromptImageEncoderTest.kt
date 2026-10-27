package com.dsh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class PromptImageEncoderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun png(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Шум, чтобы PNG не сжался до пары килобайт.
        val random = java.util.Random(1)
        for (y in 0 until height step 2) for (x in 0 until width step 2) bitmap.setPixel(x, y, random.nextInt() or Color.BLACK)
        return tmp.newFile("photo-${width}x$height.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun largePhotoIsDownscaledToJpeg() {
        val file = png(4000, 3000)
        assertTrue(file.length() > PromptImageEncoder.PASS_THROUGH_BYTES)
        val part = PromptImageEncoder.encode(file, "image/png", file.name)
        assertNotNull(part)
        assertEquals("image/jpeg", part!!.mediaType)
        val bytes = android.util.Base64.decode(part.base64, android.util.Base64.NO_WRAP)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(PromptImageEncoder.MAX_SIDE, maxOf(decoded.width, decoded.height))
        assertTrue("encoded payload should be much smaller", bytes.size < file.length())
    }

    @Test
    fun smallImageIsSentUnchanged() {
        val file = tmp.newFile("icon.png").apply {
            outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val part = PromptImageEncoder.encode(file, "image/png", "icon.png")!!
        assertEquals("image/png", part.mediaType)
        assertTrue(android.util.Base64.decode(part.base64, android.util.Base64.NO_WRAP).contentEquals(file.readBytes()))
    }
}
