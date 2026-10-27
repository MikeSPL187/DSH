package com.deepseek.dsh.plugin.runtimeweb

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Островок WebView с усиленной безопасностью.
 * - JS включён, File/Content выключены, смешанное содержимое запрещено, сторонние cookie только для host
 * - белый список в shouldOverrideUrlLoading
 * - восстановление после onRenderProcessGone
 *
 * Кроме границ безопасности, контейнер сообщает интерфейсу хоста **состояние загрузки**: интерфейс плагина
 * по [onLoadingChanged] / [onFailed] рисует индикатор и «Повторить», чтобы пользователь не гадал над пустым
 * WebView — не загрузилось, ошибка или содержимого просто нет.
 *
 * @param onLoadingChanged начало и конец загрузки основного фрейма (true — идёт загрузка).
 * @param onFailed основной фрейм не загрузился или пришла ошибка HTTP; аргумент — причина для пользователя.
 */
class WebIslandContainer(
    context: Context,
    private val allowedHost: String = "127.0.0.1",
    private val allowedPort: Int = 8080,
    private val onBridgeMessage: ((String, String) -> Unit)? = null,
    private val onLoadingChanged: ((Boolean) -> Unit)? = null,
    private val onFailed: ((String) -> Unit)? = null,
) : WebView(context) {

    @Serializable
    private data class BridgeEnvelope(val type: String, val payload: kotlinx.serialization.json.JsonElement? = null)

    /** Последний удачный адрес — для [reloadLast]. */
    private var lastUrl: String? = null

    /** Загружена ли страница; скрипт фокуса ждёт её загрузки. */
    private var pageFinished = false

    /** Подписи раздела настроек, который открыть сразу (текст `settings.section` плагина). */
    private var focusLabels: List<String> = emptyList()

    /** id регистрации `settings.section`, который открыть (совпадает с id раздела в веб-клиенте). */
    private var focusSectionId: String? = null

    /**
     * Начинать загрузку, только когда у WebView ненулевой размер.
     *
     * Если Android WebView начинает загрузку при размере 0×0, высота вьюпорта раскладки (initial containing
     * block) фиксируется нулём и потом не пересчитывается: `100vh` на странице — 0,
     * и интерфейсы с высотой по вьюпорту (панель настроек) получают высоту 0 — пользователь видит сплошную серую пелену.
     * Поэтому первая загрузка — только после измерения.
     */
    private var pendingLoad: (() -> Unit)? = null

    /** Журнал диагностики — только в отлаживаемой сборке. */
    private val debugLogs: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        setupSecuritySettings()
        setupClients()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupSecuritySettings() {
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Веб-клиент рисуется по вьюпорту CSS; широкий вьюпорт по умолчанию показал бы на телефоне десктопную раскладку.
            useWideViewPort = false
            loadWithOverviewMode = false
            // fetch на странице идёт на loopback, кэш браузера не должен давать старый HTML.
            // Статика же кэшируется, поэтому кэш целиком не выключаем — решают заголовки ответа Host.
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@WebIslandContainer, false)
        }

        // JS-интерфейс виден всем фреймам страницы (и iframe сторонних плагинов). Регистрируется,
        // только если вызывающему нужны сообщения, — меньше поверхность атаки (аудит F-11).
        if (onBridgeMessage != null) {
            addJavascriptInterface(JsBridge(), "DshNativeBridge")
        }

        // В отладочной сборке WebView виден в chrome://inspect (нужно для отладки с Host).
        if (debugLogs) {
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
    }

    private fun setupClients() {
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageFinished = false
                onLoadingChanged?.invoke(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished = true
                onLoadingChanged?.invoke(false)
                debugLog { "onPageFinished ${redactToken(url)} viewport=${view?.width}x${view?.height}" }
                // При нулевой высоте вьюпорта сначала исправить её, потом управлять интерфейсом веб-клиента — панель
                // настроек раскладывается по `100vh` и при сломанном вьюпорте получит высоту 0, останется только серая пелена.
                repairViewportIfNeeded()
                // Ошибку запуска веб-клиент рисует на странице; читаем её, чтобы хост знал,
                // что WebView так и не вошёл в интерфейс приложения.
                view?.evaluateJavascript(BOOT_FAILURE_PROBE) { result ->
                    val text = result?.trim()
                    if (text != null && text.length > 2 && text != "null") {
                        val message = text.removeSurrounding("\"")
                        if (message.isNotBlank()) onFailed?.invoke(message)
                    }
                }
                // Открыть раздел настроек плагина: маршрутов URL у веб-клиента нет, только управление
                // его интерфейсом (боковая панель → меню аккаунта → настройки → строка раздела). Скрипт на странице
                // ждёт монтирования React; не вышло — остаётся обычный интерфейс, просмотру не мешает.
                if (focusLabels.isNotEmpty()) {
                    view?.evaluateJavascript(focusScript(focusLabels, focusSectionId)) { result ->
                        debugLog { "focus script (page finished): $result" }
                    }
                }
            }

            // Подресурсы (fetch, img, script, iframe) — только с адреса Host (G-34): раньше ограничивались лишь
            // переходы главного фрейма, и веб-часть плагина могла отправить данные страницы на любой адрес.
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return if (subresourceAllowed(url, allowedHost, allowedPort)) null
                else android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                // Только http(s): intent:/javascript:/file: отклоняются даже при том же host.
                if (uri.scheme != "http" && uri.scheme != "https") return true
                val host = uri.host
                val port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

                // Только Host этого приложения: адрес и порт точно. Порт 0 (Host не запущен) не открывает ничего —
                // раньше он разрешал любой локальный сервер на телефоне.
                if (host == allowedHost && allowedPort > 0 && port == allowedPort) {
                    return false
                }
                // Внешние ссылки внутри островка не открываются.
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onLoadingChanged?.invoke(false)
                    val description = error?.description?.toString()?.takeIf { it.isNotBlank() }
                        ?: com.deepseek.dsh.core.model.Tr.t("Network error", "Сетевая ошибка")
                    onFailed?.invoke(description)
                }
                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    onLoadingChanged?.invoke(false)
                    onFailed?.invoke(com.deepseek.dsh.core.model.Tr.t("Host returned HTTP ${errorResponse?.statusCode ?: 0}", "Host вернул HTTP ${errorResponse?.statusCode ?: 0}"))
                }
                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // Защита при падении: сообщить хосту (ошибка и «Повторить») и освободить этот WebView.
                onLoadingChanged?.invoke(false)
                onFailed?.invoke("WebView renderer crashed")
                destroy()
                return true
            }
        }
        // console страницы — в logcat: только там видна полная причина сбоя запуска веб-части.
        // Только в отлаживаемой сборке: вывод страницы может содержать данные сессии и токены (аудит N-05/F-11).
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message ?: return false
                debugLog {
                    "${message.messageLevel()} ${redactToken(message.sourceId())}:${message.lineNumber()} " +
                        redactToken(message.message())
                }
                return true
            }
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun postMessage(jsonString: String) {
            if (jsonString.length > MAX_BRIDGE_MESSAGE_CHARS) return
            try {
                val envelope = Json.decodeFromString<BridgeEnvelope>(jsonString)
                onBridgeMessage?.invoke(envelope.type, envelope.payload?.toString() ?: "")
            } catch (e: Exception) {
                // Ignore parse errors from untrusted web
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        debugLog { "onSizeChanged ${oldw}x${oldh} -> ${w}x${h} pendingLoad=${pendingLoad != null}" }
        if (w > 0 && h > 0) {
            val run = pendingLoad
            pendingLoad = null
            run?.invoke()
        }
    }

    /**
     * Вызвать настоящее изменение размера, чтобы Chromium пересчитал вьюпорт раскладки.
     *
     * Если на этом устройстве при первой раскладке высота вьюпорта 0, то даже когда размер становится
     * 1280×2448, `100vh` в CSS остаётся 0: `window.innerHeight` верен,
     * а интерфейс с высотой по вьюпорту (панель настроек веб-клиента) получает высоту 0. Изменение высоты
     * на 1px и обратно проводит настоящие layout/resize, и вьюпорт обновляется.
     */
    private fun nudgeViewport() {
        val params = layoutParams as? android.view.ViewGroup.LayoutParams ?: return
        if (width <= 0 || height <= 0) return
        val originalHeight = params.height
        debugLog { "nudgeViewport on ${width}x${height} params=$originalHeight" }
        params.height = height + 1
        requestLayout()
        postDelayed({
            params.height = originalHeight
            requestLayout()
        }, 80)
    }

    /** Сломана ли высота вьюпорта (`100vh` = 0); если да — исправить, не больше двух попыток. */
    private fun repairViewportIfNeeded(attempt: Int = 0) {
        evaluateJavascript(VIEWPORT_PROBE) { result ->
            val ok = result?.contains("ok") == true
            debugLog { "viewport probe (attempt $attempt): $result" }
            if (ok || attempt >= 2) return@evaluateJavascript
            nudgeViewport()
            postDelayed({ repairViewportIfNeeded(attempt + 1) }, 400)
        }
    }

    /** Перезагрузить последний адрес; если загрузок не было — ничего. */
    fun reloadLast() {
        val target = lastUrl ?: return
        onLoadingChanged?.invoke(true)
        loadUrl(target)
    }

    /**
     * Сразу открыть раздел настроек плагина.
     *
     * Веб-клиент — SPA без маршрутов URL (`/` — всегда одна оболочка), поэтому «полный интерфейс»
     * — это загрузить клиент целиком и провести его интерфейс к разделу плагина. Сначала напрямую
     * `openSection(sectionId)` хранилища панели настроек (не зависит от раскрытия боковой панели и меню), иначе
     * путь нажатиями; скрипт повторяет попытки какое-то время — на случай медленного запуска клиента,
     * позднего окна предварительной версии, онбординга, закрывающего панель.
     *
     * Не вышло — ничего: остаётся сам веб-клиент, по нему можно пройти вручную.
     *
     * @param labels подписи-кандидаты (на языке интерфейса, id регистрации).
     * @param sectionId id регистрации `settings.section` плагина (тот же в веб-клиенте).
     */
    fun focusSettingsSection(labels: List<String>, sectionId: String? = null) {
        focusLabels = labels.filter { it.isNotBlank() }
        focusSectionId = sectionId?.takeIf { it.isNotBlank() }
        if (!pageFinished || focusLabels.isEmpty()) return
        evaluateJavascript(focusScript(focusLabels, focusSectionId)) { result ->
            debugLog { "focus script: $result" }
        }
    }

    /** Массив подписей — литералом JS. */
    private fun labelsLiteral(labels: List<String>): String =
        labels.joinToString(prefix = "[", postfix = "]") { "\"" + escapeJson(it) + "\"" }

    private fun escapeJson(value: String): String {
        val out = StringBuilder(value.length + 2)
        for (char in value) {
            when (char) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    /**
     * Скрипт, ведущий веб-клиент к нужному разделу.
     *
     * Сначала `openSection(sectionId)` хранилища панели настроек (найден через props волокна React):
     * не зависит от раскрытой боковой панели и меню аккаунта, закрытого онбордингом; затем по DOM проверяется,
     * что строка раздела `aria-current`. Иначе — путь нажатиями (launcher → пункт меню → строка раздела).
     *
     * Скрипт пробует каждые 250 мс около 30 секунд: скорость запуска клиента, окно предварительной версии
     * и онбординг пустой сессии могут поменять интерфейс в любой момент. Удача — выход.
     *
     * @param labels подписи раздела-кандидаты (на языке интерфейса).
     * @param sectionId id регистрации `settings.section`, может быть null (только по подписи).
     */
    private fun focusScript(labels: List<String>, sectionId: String?): String = """
        (async () => {
          const labels = ${labelsLiteral(labels)};
          const sectionId = ${sectionId?.let { "\"" + escapeJson(it) + "\"" } ?: "null"};
          const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
          const visible = (el) => !!el && el.offsetParent !== null;
          const buttons = () => Array.from(document.querySelectorAll('button')).filter(visible);
          const byText = (text) => buttons().find((b) => ((b.innerText || '').trim()) === text);
          const byAria = (aria) => buttons().find((b) => b.getAttribute('aria-label') === aria);
          const waitFor = async (fn, timeout) => {
            const end = Date.now() + timeout;
            while (Date.now() < end) {
              const value = fn();
              if (value) return value;
              await sleep(120);
            }
            return null;
          };
          const clickElement = (el) => {
            if (!el) return;
            try { el.scrollIntoView({ block: 'center', inline: 'center' }); } catch (error) { /* noop */ }
            try { el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, pointerType: 'mouse' })); } catch (error) { /* noop */ }
            try { el.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); } catch (error) { /* noop */ }
            try { el.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); } catch (error) { /* noop */ }
            try { el.click(); } catch (error) { /* noop */ }
          };
          const settingsDialog = () =>
            document.querySelector('[role="dialog"][aria-modal="true"]:has(> nav)');
          const activeNav = () => {
            const dialog = settingsDialog();
            if (!dialog) return null;
            return dialog.querySelector('nav button[aria-current="true"]')
              || Array.from(dialog.querySelectorAll('nav button')).find((b) => {
                const cls = typeof b.className === 'string' ? b.className : '';
                return /(^|[ _-])active($|[ _-])/i.test(cls);
              }) || null;
          };
          const isNav = (b) => labels.some(
            (label) => label.toLowerCase() === (((b.innerText || '').trim()).toLowerCase()),
          );
          const navButton = () => {
            const dialog = settingsDialog();
            if (!dialog) return null;
            const inDialog = Array.from(dialog.querySelectorAll('button')).filter(visible);
            return inDialog.find(isNav) || null;
          };
          // Веб-клиент рассчитан на десктоп: на ширине телефона панель настроек остаётся в две колонки
          // «навигация 188px + содержимое», на содержимое меньше 200px, и текст идёт по слову в строке. Узкая раскладка
          // делает тот же интерфейс читаемым на телефоне: навигация — прокрутка сверху, содержимое во всю ширину.
          const responsiveCss = [
            '@media (max-width: 700px) {',
            '  [role="dialog"][aria-modal="true"]:has(> nav) { flex-direction: column !important; width: calc(100vw - 16px) !important; max-width: calc(100vw - 16px) !important; height: calc(100vh - 40px) !important; }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) > nav { width: 100% !important; padding: 10px 12px 6px !important; gap: 8px !important; flex-direction: row !important; overflow-x: auto !important; flex: none !important; border-bottom: 1px solid rgba(0,0,0,0.08); }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) > nav > div:first-child { display: none !important; }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) > nav > div { display: flex !important; flex-direction: row !important; gap: 8px !important; }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) > nav > div > div { display: flex !important; flex-direction: row !important; gap: 6px !important; flex-wrap: nowrap !important; }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) > div { min-height: 0 !important; flex: 1 1 auto !important; }',
            '  [role="dialog"][aria-modal="true"]:has(> nav) button { white-space: nowrap; }',
            '}',
          ].join('\n');
          const ensureResponsiveStyle = () => {
            let style = document.getElementById('dsh-mobile-responsive');
            if (style === null) {
              style = document.createElement('style');
              style.id = 'dsh-mobile-responsive';
              (document.head || document.documentElement).appendChild(style);
            }
            style.textContent = responsiveCss;
          };
          const dismissPreview = () => {
            if (!(document.body.innerText || '').includes('预览版说明')) return false;
            const cont = byText('继续') || byText('Continue');
            if (!cont) return false;
            clickElement(cont);
            return true;
          };
          /**
           * Подняться по волокну React от launcher настроек к actions панели настроек.
           * SettingsRoot передаёт actions хранилища в props внутренним компонентам, поэтому вверх по волокну
           * обязательно найдётся слой с openSection — это API самого веб-клиента, а не подделка состояния.
           */
          const settingsActions = () => {
            const candidates = Array.from(document.querySelectorAll(
              '[data-slot="sidebar.settings"] button, [data-slot="settings.launcher"] button, button[aria-haspopup="dialog"]',
            ));
            for (const element of candidates) {
              const key = Object.keys(element).find(
                (name) => name.startsWith('__reactFiber$') || name.startsWith('__reactInternalInstance$'),
              );
              if (!key) continue;
              let fiber = element[key];
              let guard = 0;
              while (fiber && guard < 120) {
                const props = fiber.memoizedProps;
                const actions = props && props.actions;
                if (actions && typeof actions.openSection === 'function') return actions;
                fiber = fiber.return;
                guard += 1;
              }
            }
            return null;
          };
          const deadline = Date.now() + 45000;
          let dispatched = false;
          // Если панель открыта, но активной строки ещё нет, нужно несколько подтверждений подряд, что
          // онбординг её не закрыл, — иначе «открыли → закрыли → времени не осталось».
          let stableChecks = 0;
          let lastNote = '';
          ensureResponsiveStyle();
          while (Date.now() < deadline) {
            try {
              if (dismissPreview()) {
                await sleep(450);
                continue;
              }
              let dialog = settingsDialog();
              let active = activeNav();
              if (dialog && active && isNav(active)) {
                ensureResponsiveStyle();
                return 'focused:' + ((active.innerText || '').trim() || sectionId || 'section');
              }
              // Открыли по id, панель есть и активная строка тоже: ещё несколько тактов подтверждения, что
              // онбординг не закрыл панель сразу (тогда окно пропадает и счётчик обнуляется).
              if (dialog && active && dispatched) {
                stableChecks += 1;
                if (stableChecks >= 3) {
                  ensureResponsiveStyle();
                  return 'focused-id:' + ((active.innerText || '').trim() || sectionId || 'section');
                }
              } else {
                stableChecks = 0;
              }
              // 1. Напрямую через хранилище: без зависимости от раскрытия боковой панели и меню аккаунта.
              if (sectionId !== null) {
                let actions = null;
                try { actions = settingsActions(); } catch (error) { lastNote = 'fiber:' + error; }
                if (actions) {
                  try {
                    actions.openSection(sectionId);
                    dispatched = true;
                  } catch (error) {
                    lastNote = 'openSection:' + error;
                  }
                  await sleep(400);
                  ensureResponsiveStyle();
                  dialog = settingsDialog();
                  if (dialog) {
                    const target = navButton();
                    if (target) {
                      clickElement(target);
                      await sleep(300);
                    }
                  }
                  await sleep(250);
                  continue;
                }
                lastNote = 'settings actions not found';
              }
              // 2. Иначе нажатиями: launcher настроек → пункт «Настройки» → строка раздела.
              const existing = navButton();
              if (existing) {
                clickElement(existing);
                await sleep(500);
                continue;
              }
              if (!dialog) {
                const trigger = (document.querySelector('[data-slot="sidebar.settings"] button')
                  || document.querySelector('[data-slot="settings.launcher"] button')
                  || byAria('账号菜单') || byAria('设置'));
                if (trigger) {
                  clickElement(trigger);
                  await sleep(350);
                }
                const menuItem = await waitFor(
                  () => Array.from(document.querySelectorAll('button[role="menuitem"]'))
                    .find((b) => /^\s*(设置|Settings)\s*$/.test(b.innerText || '')),
                  1200,
                );
                if (menuItem) {
                  clickElement(menuItem);
                  await sleep(500);
                }
                const target = await waitFor(navButton, 1500);
                if (target) {
                  clickElement(target);
                  await sleep(500);
                }
              }
            } catch (error) {
              lastNote = String(error);
            }
            await sleep(300);
          }
          return 'not-found:' + lastNote;
        })()
    """.trimIndent()

    fun loadSurface(surfaceSlot: String, pluginId: String, isolated: Boolean = true, token: String? = null) {
        val run = { performLoad(surfaceSlot, pluginId, isolated, token) }
        // Загрузка при 0×0 фиксирует нулевую высоту вьюпорта (см. [pendingLoad]) — ждём измерения.
        if (width > 0 && height > 0) {
            pendingLoad = null
            run()
        } else {
            pendingLoad = run
        }
    }

    private fun performLoad(surfaceSlot: String, pluginId: String, isolated: Boolean, token: String?) {
        val base = "http://$allowedHost:$allowedPort"
        val queryParts = mutableListOf<String>()
        if (isolated) {
            queryParts.add("__dsh_surface=${Uri.encode(surfaceSlot)}")
            queryParts.add("__dsh_plugin=${Uri.encode(pluginId)}")
            queryParts.add("__dsh_shell=none")
        }
        val query = if (queryParts.isNotEmpty()) {
            "?" + queryParts.joinToString("&")
        } else {
            ""
        }
        val targetUrl = "$base/$query"

        // Токен запуска — **страничный** пропуск: на запрос с токеном Host отдаёт полный HTML (со списком boot),
        // но cookie в ответе не ставит — обмен делает JS самой страницы. Поэтому с токеном надо остаться
        // на этом адресе, не переходить сразу на адрес без токена: там упрощённый HTML,
        // и клиент застрянет на «Failed to load plugins». Целевой адрес — после того, как cookie получен.
        if (!token.isNullOrBlank()) {
            val cm = CookieManager.getInstance()
            val existing = cm.getCookie(base)
            if (existing.isNullOrBlank() || !existing.contains("dsh-web-session")) {
                val separator = if (query.isEmpty()) "?" else "&"
                val url = "$base/$query${separator}token=${Uri.encode(token)}"
                lastUrl = url
                onLoadingChanged?.invoke(true)
                loadUrl(url)
                return
            }
        }

        lastUrl = targetUrl
        onLoadingChanged?.invoke(true)
        loadUrl(targetUrl)
    }

    private inline fun debugLog(message: () -> String) {
        if (debugLogs) Log.d(TAG, message())
    }

    private companion object {
        /** Тег logcat: диагностика загрузки островка и исправления вьюпорта. */
        const val TAG = "DshWebIsland"

        /** Предел одного сообщения JS → нативный код. */
        const val MAX_BRIDGE_MESSAGE_CHARS = 256 * 1024

        private val TOKEN_QUERY = Regex("""([?&](?:token|auth|access_token)=)[^&#\s]*""", RegexOption.IGNORE_CASE)

        /** Стереть значения `token=` и подобных параметров в URL и тексте: пропуск запуска в журнал не попадает. */
        fun redactToken(text: String?): String = text?.let { TOKEN_QUERY.replace(it, "$1***") }.orEmpty()

        /**
         * Проверить высоту вьюпорта: `height: 100vh` должна давать ненулевое значение.
         * Возвращает `ok` / `broken:<значение>`.
         */
        const val VIEWPORT_PROBE = """
            (() => {
              try {
                const probe = document.createElement('div')
                probe.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:100vh;pointer-events:none'
                document.body.appendChild(probe)
                const height = probe.getBoundingClientRect().height
                probe.remove()
                return height > 0 ? 'ok' : ('broken:' + height)
              } catch (e) { return 'broken:error' }
            })()
        """

        /**
         * Прочитать текст ошибки со страницы запуска веб-клиента и отдать хосту.
         *
         * Сама страница рисует ошибку (`Failed to load plugins`), но для WebView
         * `onPageFinished` — «успех», и хост ошибки не видит. Здесь текст только читается, страница не меняется.
         */
        const val BOOT_FAILURE_PROBE = """
            (() => {
              try {
                const el = document.querySelector('[class*="failed"], [class*="failedTitle"]')
                if (!el) return null
                const text = (el.textContent || '').replace(/\s+/g, ' ').trim()
                return text.length > 0 ? text.slice(0, 240) : null
              } catch (e) { return null }
            })()
        """
    }
}

/**
 * Можно ли веб-части плагина загрузить [url]: только http(s) на адрес и порт Host; `data:` и `blob:` — локальные
 * данные страницы. Порт 0 (Host не запущен) не разрешает ничего.
 */
internal fun subresourceAllowed(url: String, allowedHost: String?, allowedPort: Int): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("blob:")) return true
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
    return allowedPort > 0 && uri.host != null && uri.host == allowedHost && port == allowedPort
}
