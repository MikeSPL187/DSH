package com.dsh.feature.files

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Что умеет показывать просмотр файла кроме кода: оформленный Markdown, страницу HTML, страницы PDF. */
internal enum class PreviewKind { MARKDOWN, HTML, PDF, NONE }

internal fun previewKindOf(name: String): PreviewKind = when (name.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> PreviewKind.MARKDOWN
    "html", "htm" -> PreviewKind.HTML
    "pdf" -> PreviewKind.PDF
    else -> PreviewKind.NONE
}

/** `.fv-seg`: «Вид | Код» (для HTML — «Страница | Код»), 40 dp, выбранный — карточка на s2. */
@Composable
internal fun PreviewSeg(viewLabel: String, codeLabel: String, showCode: Boolean, onChange: (Boolean) -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.s2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to viewLabel, true to codeLabel).forEach { (code, label) ->
            val on = showCode == code
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)).background(c.s1) else Modifier)
                    .clickable(role = Role.Tab) { onChange(code) }
                    .semantics { selected = on; role = Role.Tab },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp), color = if (on) c.t1 else c.t2)
            }
        }
    }
}

/**
 * Страница HTML как картинка: без JavaScript, без сети и без доступа к файлам — превью не может ни выполнить код
 * из рабочей папки, ни сходить в интернет. Живую страницу открывает «Открыть в браузере».
 */
@Composable
internal fun HtmlStaticPreview(html: String) {
    val c = Dsh.colors
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(AndroidColor.WHITE)
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, c.line, RoundedCornerShape(20.dp)),
    )
}

/** Открытый PDF: `PdfRenderer` не потокобезопасен — страницы рисуются по одной под замком. */
internal class PdfDoc private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {
    private val lock = Mutex()
    val pages: Int get() = renderer.pageCount

    /** Страница [index] шириной [widthPx] на белом фоне (у PDF прозрачный фон — бумага белая). */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val w = widthPx.coerceIn(1, MAX_PAGE_PX)
                    val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_PAGE_PX * 2)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(AndroidColor.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()
        }
    }

    /** Соотношение сторон страницы (ширина / высота) — место под неё до того, как она нарисована. */
    suspend fun ratio(index: Int): Float = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) } }.getOrDefault(A4)
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        /** Ширина страницы в пикселях не больше этой: 1440 × 2036 × 4 байта ≈ 12 МБ на видимую страницу. */
        const val MAX_PAGE_PX = 1440
        const val A4 = 1f / 1.414f

        /** null — файл не PDF, повреждён или зашифрован (PdfRenderer бросает SecurityException). */
        fun open(file: File): PdfDoc? = runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfDoc(pfd, PdfRenderer(pfd)) } catch (e: Exception) { pfd.close(); throw e }
        }.getOrNull()
    }
}

/** Одна страница PDF: место по её пропорциям, затем картинка; номер в углу. */
@Composable
internal fun PdfPage(doc: PdfDoc, index: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val ratio by produceState(PdfDoc.A4, doc, index) { value = doc.ratio(index) }
        val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) { value = doc.render(index, widthPx) }
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio).shadow(4.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Color.White),
        ) {
            bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
            Text(
                "${index + 1}", style = TextStyle(fontFamily = DshFonts.body, fontSize = 11.sp), color = Color(0xFF5B5D68),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * Масштаб PDF сведением пальцев (1–4×) и сдвиг по горизонтали; прокрутку одним пальцем по вертикали
 * оставляет списку — жест забирается, только когда пальцев два или страница увеличена и ведут вбок.
 */
internal fun Modifier.pdfZoom(scale: () -> Float, offset: () -> Float, onChange: (scale: Float, offset: Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                val maxShift = size.width * (scale() - 1f) / 2f
                if (pressed.size >= 2) {
                    val s = (scale() * event.calculateZoom()).coerceIn(1f, 4f)
                    val limit = size.width * (s - 1f) / 2f
                    onChange(s, (offset() + event.calculatePan().x).coerceIn(-limit, limit))
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && scale() > 1f) {
                    val d = pressed[0].positionChange()
                    if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y)) {
                        onChange(scale(), (offset() + d.x).coerceIn(-maxShift, maxShift))
                        pressed[0].consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
