package com.deepseek.dsh

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import com.deepseek.dsh.core.designsystem.LocalDshPanelColors
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Диагностика NodeJS (Chrome DevTools) в «Инструментах разработчика».
 *
 * Плагин `experimental-inspector` встраивает chrome-devtools-frontend в iframe `shell.bottom`
 * и проксирует CDP через Host. Нативного аналога нет, поэтому так же:
 * WebView загружает тот же фронтенд DevTools (у Host —
 * `/inspector/devtools/devtools_app.html`), cookie сессии передаются в WebView.
 *
 * Вход — вкладка в панели отладки (диагностика сессии), а не страница плагина:
 * на телефоне нет нижней панели по Ctrl/Cmd+Shift+.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NodeJsDevToolsPane() {
    val colors = LocalDshPanelColors.current
    val context = LocalContext.current
    val app = context.applicationContext as? DshApplication
    val port = app?.localHostPort ?: 0
    val token = app?.localHostToken
    var reloadKey by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.inspector_node_diagnostics), color = colors.labelPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = stringResource(R.string.inspector_devtools_hint),
                    color = colors.labelTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.bgModulePlatform)
                    .border(0.5.dp, colors.borderL2, RoundedCornerShape(9.dp))
                    .clickable { reloadKey += 1 }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(stringResource(R.string.inspector_reload), color = colors.labelSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            port <= 0 -> Text(
                text = stringResource(R.string.inspector_host_not_running),
                color = colors.labelTertiary,
                fontSize = 12.5.sp,
            )
            else -> {
                val url = remember(port, reloadKey) {
                    "http://127.0.0.1:$port/inspector/devtools/devtools_app.html?disableLocaleInfoBar=true"
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(0.5.dp, colors.borderL2, RoundedCornerShape(10.dp)),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            // В окне — cookie сессии Host: переходы только по его адресу, внешние ссылки не открываются.
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val target = request?.url ?: return true
                                    return !(target.scheme == "http" && target.host == "127.0.0.1" && target.port == port)
                                }
                            }
                            // Отладочные маршруты Host — по cookie сессии; переносим cookie приложения в WebView.
                            runCatching {
                                @Suppress("UNUSED_VARIABLE")
                                val ignored = token
                                val cookies = app?.cookieJar?.loadForRequest(
                                    "http://127.0.0.1:$port/".toHttpUrl(),
                                ).orEmpty()
                                // setCookie принимает одну cookie: строка «a=1; b=2» записала бы только первую.
                                cookies.forEach { CookieManager.getInstance().setCookie("http://127.0.0.1:$port/", "${it.name}=${it.value}") }
                                if (cookies.isNotEmpty()) CookieManager.getInstance().flush()
                            }
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}
