package com.dsh

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.core.designsystem.Dsh
import com.dsh.core.designsystem.DshFonts
import com.dsh.core.designsystem.DshGlyphs
import com.dsh.core.designsystem.DshIconButton
import com.dsh.core.designsystem.DshTopBar
import java.io.File

/** Что открыть во встроенном браузере: адрес и, для файлов рабочей папки, её корень. */
data class BrowserTarget(val url: String, val workspaceRoot: File? = null)

/**
 * Встроенный браузер: страницы dev-сервера из Linux DSH (`localhost`), HTTPS-сайты и HTML из рабочей папки.
 * Безопасность: без мостов в Kotlin, без `file://` и `content://`, только http(s) по политике [BrowserUrls],
 * Safe Browsing, смешанный контент запрещён. Файлы рабочей папки отдаются только изнутри неё.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(target: BrowserTarget, onClose: () -> Unit) {
    val c = Dsh.colors
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var url by remember(target) { mutableStateOf(target.url) }
    var field by remember(target) { mutableStateOf(shownAddress(target.url)) }
    var title by remember(target) { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val root = target.workspaceRoot

    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }
    DisposableEffect(Unit) { onDispose { webView?.apply { stopLoading(); destroy() } } }

    fun go(input: String) {
        // Путь без схемы, который есть в рабочей папке, — её файл, а не сайт.
        val inWorkspace = root?.takeIf { !input.contains("://") }?.let { r -> BrowserUrls.resolveWorkspace(r, input.trim())?.let { BrowserUrls.workspaceUrl(r, it) } }
        val next = when {
            input.startsWith(BrowserUrls.WORKSPACE_ORIGIN) -> input
            inWorkspace != null -> inWorkspace
            else -> BrowserUrls.normalize(input)
        }
        if (next == null) { refused = true; return }
        refused = false; failed = null
        focus.clearFocus()
        webView?.loadUrl(next)
    }

    val host = remember(url) { runCatching { java.net.URI(url).host }.getOrNull() }
    val local = BrowserUrls.isLoopback(host)
    val workspace = host == BrowserUrls.WORKSPACE_HOST
    Column(Modifier.fillMaxSize().background(c.bg)) {
        DshTopBar(
            title = stringResource(R.string.br_title),
            subtitle = title.ifBlank { if (workspace) stringResource(R.string.br_sub_workspace) else if (local) stringResource(R.string.br_sub_local) else host.orEmpty() },
            onBack = onClose,
            backDescription = stringResource(R.string.br_close),
            modifier = Modifier.statusBarsPadding(),
        ) {
            // Внешний браузер видит и localhost (сервер в Linux DSH слушает сеть телефона); файлы рабочей папки — нет.
            if (!workspace) DshIconButton(DshGlyphs.Share, stringResource(R.string.br_external), tint = c.t2) {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val desc = stringResource(R.string.br_address)
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(c.s2).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (url.startsWith("https://")) DshGlyphs.Lock else DshGlyphs.Globe, null, tint = c.t3, modifier = Modifier.size(16.dp))
                BasicTextField(
                    value = field, onValueChange = { field = it }, singleLine = true,
                    textStyle = TextStyle(fontFamily = DshFonts.body, fontSize = 14.5.sp, color = c.t1), cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { go(field) }),
                    modifier = Modifier.weight(1f).semantics { contentDescription = desc },
                )
            }
            if (progress in 1..99) DshIconButton(DshGlyphs.X, stringResource(R.string.br_stop), tint = c.t2) { webView?.stopLoading() }
            else DshIconButton(DshGlyphs.Redo, stringResource(R.string.br_reload), tint = c.t2) { failed = null; webView?.reload() }
        }
        when {
            refused -> Note(stringResource(R.string.br_refused), c.warn)
            workspace -> Note(stringResource(R.string.br_note_workspace), c.t3)
            local -> Note(stringResource(R.string.br_note_local), c.t3)
        }
        // Тонкая полоса загрузки под адресом.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) Box(Modifier.fillMaxWidth(progress / 100f).height(2.dp).background(c.accent))
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.White)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setGeolocationEnabled(false)
                        settings.mediaPlaybackRequiresUserGesture = true
                        if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val next = request.url.toString()
                                // Другие схемы (intent:, file:, javascript:, market:) и открытый HTTP вне localhost не открываются.
                                return !(BrowserUrls.allowed(next) || request.url.host == BrowserUrls.WORKSPACE_HOST)
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.url.host != BrowserUrls.WORKSPACE_HOST) return null
                                val file = root?.let { BrowserUrls.resolveWorkspace(it, request.url.encodedPath.orEmpty()) }
                                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                                val mime = BrowserUrls.mime(file.name)
                                return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json")) "utf-8" else null, file.inputStream())
                            }

                            override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
                                url = pageUrl; field = shownAddress(pageUrl); failed = null
                            }

                            override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
                                canBack = view.canGoBack(); canForward = view.canGoForward()
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = error.description?.toString().orEmpty()
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
                        }
                        webView = this
                        loadUrl(target.url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            failed?.let { reason ->
                Column(
                    Modifier.fillMaxSize().background(c.bg).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(DshGlyphs.Alert, null, tint = c.warn, modifier = Modifier.size(28.dp))
                    Text(
                        stringResource(if (local) R.string.br_failed_local else R.string.br_failed, host.orEmpty()),
                        style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp), color = c.t1, textAlign = TextAlign.Center,
                    )
                    if (reason.isNotBlank()) Text(reason, style = TextStyle(fontFamily = DshFonts.mono, fontSize = 12.sp), color = c.t3, textAlign = TextAlign.Center)
                    Box(
                        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(c.s2).clickable { failed = null; webView?.reload() }.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.br_retry), style = TextStyle(fontFamily = DshFonts.body, fontWeight = FontWeight.Medium, fontSize = 14.5.sp), color = c.accent) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(c.bg).drawBehind { drawRect(c.line, size = Size(size.width, 1.dp.toPx())) }
                .navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            DshIconButton(DshGlyphs.Left, stringResource(R.string.br_back), tint = c.t2, enabled = canBack) { webView?.goBack() }
            DshIconButton(DshGlyphs.Right, stringResource(R.string.br_forward), tint = c.t2, enabled = canForward) { webView?.goForward() }
            val copied = stringResource(R.string.br_copied)
            DshIconButton(DshGlyphs.Copy, stringResource(R.string.br_copy), tint = c.t2) {
                clipboard.setText(AnnotatedString(url))
                android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = TextStyle(fontFamily = DshFonts.body, fontSize = 12.sp, lineHeight = 16.sp), color = color, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
}

/** Адрес для поля: без `http://` у localhost и с путём файла у рабочей папки. */
internal fun shownAddress(url: String): String = when {
    url.startsWith(BrowserUrls.WORKSPACE_ORIGIN) -> java.net.URLDecoder.decode(url.removePrefix(BrowserUrls.WORKSPACE_ORIGIN), "UTF-8")
    url.startsWith("http://") -> url.removePrefix("http://").removeSuffix("/")
    else -> url.removeSuffix("/")
}
