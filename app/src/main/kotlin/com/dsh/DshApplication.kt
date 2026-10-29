package com.dsh

import android.app.Application
import com.dsh.runtime.host.DefaultHostRuntimeManager
import com.dsh.runtime.host.HostProcessRunner
import com.dsh.runtime.host.HostRuntimeHolder
import com.dsh.runtime.host.HostRuntimeManager
import com.dsh.runtime.host.RuntimeLayout
import androidx.room.Room
import com.dsh.core.data.DefaultSettingsRepository
import com.dsh.core.data.DshDatabase
import com.dsh.core.data.DshRepository
import com.dsh.core.protocol.HostCookieJar
import com.dsh.core.protocol.RemoteClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DshApplication : Application() {

    /**
     * Защита от EOF в потоке чтения WebSocket.
     *
     * Когда система останавливает Host, долгие соединения кончаются EOF, а поток чтения OkHttp 4
     * в редкой гонке при закрытии выбрасывает `EOFException` наружу, и процесс падает. Это лишь
     * «соединение уже закрыто»: пишем в журнал и живём дальше (переподключение произойдёт
     * при следующем вызове). Остальные необработанные исключения падают как обычно.
     */
    private fun installTransportEofGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val cause = generateSequence(error) { it.cause }.lastOrNull() ?: error
            if (cause is java.io.EOFException) {
                android.util.Log.w(
                    "DshTransport",
                    "websocket closed abruptly on ${thread.name}: ${cause.message}",
                )
            } else {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var runtimeLayout: RuntimeLayout
        private set

    val hostRunner = HostProcessRunner()

    lateinit var runtimeManager: HostRuntimeManager
        private set

    val database: DshDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            DshDatabase::class.java,
            "dsh_app.db"
        )
            // Явные миграции вместо «молча удалить базу» (аудит F-03/N-13). Разрушающая пересборка —
            // только с базы разработки v1 без известной схемы; дальше каждая версия — с Migration.
            .addMigrations(*com.dsh.core.data.DshMigrations.ALL)
            .fallbackToDestructiveMigrationFrom(true, 1)
            .build()
    }

    val cookieJar by lazy { HostCookieJar() }

    val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun createRemoteClient(baseUrl: String): RemoteClient {
        return RemoteClient(baseUrl = baseUrl, client = okHttpClient)
    }

    val defaultClient: RemoteClient by lazy {
        createRemoteClient("http://127.0.0.1:1")
    }

    /**
     * Порт loopback и токен запуска работающего локального Host.
     *
     * WebView-островки шлют запросы сами (RPC и ресурсы плагинов) и не могут взять сессию
     * OkHttp приложения, поэтому пара хранится здесь: островок меняет `?token=` на тот же cookie
     * и видит тот же Host и ту же сессию. Пока Host не привязан — 0/null.
     */
    @Volatile
    var localHostPort: Int = 0
        private set

    @Volatile
    var localHostToken: String? = null
        private set

    /**
     * Привязать клиент репозитория к порту loopback, который сообщил локальный Host, и обменять
     * токен запуска на cookie; дальше все запросы интерфейса идут по этому адресу.
     * @return false — Host не принял вход (G-19): без сессии все вызовы падали бы по одному с 401/403.
     */
    suspend fun bindLocalHost(port: Int, token: String?): Boolean {
        // Порт 1 — заглушка, Host ещё не готов: не привязываем, иначе все запросы и потоки
        // ушли бы на адрес, который заведомо отклонит соединение.
        if (port <= 1) return false
        val baseUrl = "http://127.0.0.1:$port"
        localHostPort = port
        localHostToken = token
        defaultClient.updateBaseUrl(baseUrl)
        // Мост и Remote API делят один адрес loopback и CookieJar сессии: сначала адрес,
        // потом обмен токена — так первый запрос моста уже идёт с сессией.
        dshRepository.plugins.bindBridge(com.dsh.core.bridge.BridgeRegistry(baseUrl, okHttpClient))
        if (token.isNullOrBlank()) return true
        // Сразу после запуска Host может ещё не принимать вход: две повторные попытки с паузой.
        repeat(3) { attempt ->
            if (defaultClient.authenticate(baseUrl, token) is com.dsh.core.protocol.RemoteResult.Success) {
                // Состояние входа — в системный CookieManager для WebView и WebIslandContainer.
                runCatching { android.webkit.CookieManager.getInstance().setAcceptCookie(true) }
                return true
            }
            if (attempt < 2) kotlinx.coroutines.delay(700L * (attempt + 1))
        }
        return false
    }

    val dshRepository: DshRepository by lazy {
        DshRepository(
            dao = database.dshDao(),
            client = defaultClient,
            scope = applicationScope
        ).also { it.approvals.approvalPolicy = ::toolApprovalPolicy }
    }

    val settingsRepository: DefaultSettingsRepository by lazy {
        DefaultSettingsRepository(
            dao = database.dshDao(),
            scope = applicationScope,
            secretCipher = com.dsh.core.data.SecretCipher.androidKeystore(),
        )
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        // Сначала запись сбоев, поверх — защита от EOF: проглоченный разрыв не даёт отчёта о сбое.
        TestCrashRecorder.install(this)
        installTransportEofGuard()
        super.onCreate()
        com.dsh.runtime.host.HostStrings.init(this)
        com.dsh.feature.settings.logic.ApiKeyVault.init(this)
        com.dsh.feature.settings.logic.DshUserPrefs.init(this)
        com.dsh.feature.settings.logic.ToolCallLog.init(this)
        com.dsh.feature.settings.logic.ToolPolicy.init(this)
        com.dsh.runtime.host.AgentNetworkPolicy.init(this)
        com.dsh.feature.settings.logic.AutomationStore.init(this)
        AutomationScheduler.rescheduleAll(this)
        // Тексты из модулей без Context следуют за языком интерфейса, а не только за системным.
        com.dsh.core.model.Tr.language = {
            resources.configuration.locales[0]?.language ?: java.util.Locale.getDefault().language
        }
        runtimeLayout = RuntimeLayout(
            baseDir = filesDir,
            nativeLibraryDir = java.io.File(applicationInfo.nativeLibraryDir ?: filesDir.absolutePath)
        )
        runtimeManager = DefaultHostRuntimeManager(
            context = applicationContext,
            layout = runtimeLayout,
            runner = hostRunner
        )
        HostRuntimeHolder.manager = runtimeManager
        HostRuntimeHolder.hasRunningTasks = { dshRepository.runningSessionIdsFlow.value.isNotEmpty() }
        // Host прогревается при открытии интерфейса (MainAppHost, StartupGate), а не здесь (G-39): процесс поднимается
        // и ради JobScheduler в фоне, где foreground-служба запрещена (Android 12+), — proot и Node работали бы без
        // службы и уведомления. Миграция папки Default — там же, перед прогревом. Автоматизации запускают Host сами.
        applicationScope.launch(Dispatchers.IO) { dshRepository.pruneMessageCache() }
        // Память агента: факты — в личных файлах, для агента — блок в ~/.agents/AGENTS.md (если Linux уже установлен).
        applicationScope.launch(Dispatchers.IO) {
            com.dsh.core.data.AgentMemory.init(java.io.File(filesDir, "agent-memory.json"), java.io.File(runtimeLayout.agentsHomeDir, "AGENTS.md"))
        }
    }
}




