package com.deepseek.dsh.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import java.io.File

/** Скриншоты и текстовые дампы экранов: `app/build/ui-screens/<name>.png|.txt`. */
internal object ScreenShots {
    private val dir = File(System.getProperty("user.dir"), "build/ui-screens").apply { mkdirs() }

    fun capture(rule: ComposeTestRule, name: String, activity: android.app.Activity? = null) {
        runCatching {
            val bitmap = activity?.let { drawWindow(it) } ?: rule.onRoot().captureToImage().asAndroidBitmap()
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { File(dir, "$name.png.error.txt").writeText(it.stackTraceToString()) }
        runCatching {
            File(dir, "$name.txt").writeText(rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 60))
        }
    }

    /** Рисуем корневое View окна напрямую (без ожидания «тихого» кадра — анимации не мешают). */
    private fun drawWindow(activity: android.app.Activity): android.graphics.Bitmap {
        val root = activity.window.decorView
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }
}
