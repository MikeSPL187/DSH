package com.dsh

import android.content.Context
import com.dsh.core.designsystem.kit.DeviceCodeView
import com.dsh.core.designsystem.kit.DeviceSignInError
import com.dsh.feature.settings.logic.ApiKeyVault
import com.dsh.runtime.host.GitHubAuth
import com.dsh.runtime.host.GitHubPulls
import com.dsh.runtime.host.GitHubPush
import kotlinx.coroutines.delay
import java.io.File

/**
 * Аккаунт GitHub приложения: токен — в Android Keystore ([ApiKeyVault], ссылка [REF]) и никогда не показывается;
 * логин и время входа — в обычных настройках. Вход — по одноразовому коду ([GitHubAuth]), отправка — из процесса
 * приложения ([GitHubPush]), так что токен не попадает в Linux.
 */
object GitHubAccount {
    const val REF = "github"
    private const val PREFS = "dsh_github"

    private val auth by lazy { GitHubAuth(BuildConfig.GITHUB_CLIENT_ID) }
    @Volatile private var pending: GitHubAuth.DeviceCode? = null

    /** Ошибка входа с её видом — для листа входа. */
    class SignInException(val kind: DeviceSignInError) : Exception(kind.toString())

    /** Отправке нужен вход в GitHub. */
    class NeedLoginException : Exception("github login")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Логин вошедшего пользователя; null — не вошёл (или токен потерян вместе с ключом Keystore). */
    fun login(context: Context): String? = prefs(context).getString("login", null)?.takeIf { ApiKeyVault.has(REF) }

    fun signedAt(context: Context): Long? = prefs(context).getLong("at", 0L).takeIf { it > 0 && ApiKeyVault.has(REF) }

    val configured: Boolean get() = auth.configured

    /** Получить код для пользователя. */
    suspend fun begin(): Result<DeviceCodeView> {
        if (!auth.configured) return Result.failure(SignInException(DeviceSignInError.NotConfigured))
        return auth.start().map { code -> pending = code; DeviceCodeView(code.userCode, code.verificationUri, code.expiresInSec) }
    }

    /**
     * Ждать подтверждения по правилам device flow: не чаще `interval`, при `slow_down` — на 5 с реже, до срока кода.
     * Токен сохраняется в Keystore; не сохранился — вход не считается выполненным.
     */
    suspend fun await(context: Context): Result<String> {
        val code = pending ?: return Result.failure(SignInException(DeviceSignInError.Expired))
        var interval = code.intervalSec
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            when (val p = auth.poll(code)) {
                is GitHubAuth.Poll.Token -> {
                    pending = null
                    if (!ApiKeyVault.save(REF, p.token)) return Result.failure(SignInException(DeviceSignInError.Other("Android Keystore")))
                    val login = auth.login(p.token).getOrDefault("")
                    prefs(context).edit().putString("login", login).putLong("at", System.currentTimeMillis()).apply()
                    return Result.success(login)
                }
                GitHubAuth.Poll.Pending -> Unit
                GitHubAuth.Poll.SlowDown -> interval += 5
                GitHubAuth.Poll.Denied -> return Result.failure(SignInException(DeviceSignInError.Denied))
                GitHubAuth.Poll.Expired -> return Result.failure(SignInException(DeviceSignInError.Expired))
                is GitHubAuth.Poll.Failed -> return Result.failure(SignInException(DeviceSignInError.Other(p.message)))
            }
        }
        return Result.failure(SignInException(DeviceSignInError.Expired))
    }

    fun errorOf(t: Throwable): DeviceSignInError = (t as? SignInException)?.kind ?: DeviceSignInError.Other(t.message.orEmpty())

    /** Выйти: токен удаляется из Keystore. Права на github.com пользователь отзывает сам (Settings → Applications). */
    fun signOut(context: Context) {
        ApiKeyVault.delete(REF)
        openPulls.clear(); defaults.clear()
        prefs(context).edit().clear().apply()
    }

    private val pulls by lazy { GitHubPulls() }
    private val defaults = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Ветка по умолчанию репозитория по ответу GitHub (запоминается на время работы); null — не вошли или нет сети. */
    suspend fun defaultBranch(owner: String, repo: String): String? = defaults["$owner/$repo"]
        ?: ApiKeyVault.use(REF) { token -> pulls.defaultBranch(token, owner, repo).getOrNull() }?.also { defaults["$owner/$repo"] = it }

    /** Ответы «есть ли запрос из ветки» на [PULL_TTL_MS]: экран «Изменения» перечитывает состояние при каждой правке файлов. */
    private val openPulls = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, GitHubPulls.Pull?>>()
    private const val PULL_TTL_MS = 60_000L

    /** Открытый запрос из ветки; null — нет, не вошли или не узнать (неудача не запоминается). */
    suspend fun openPull(owner: String, repo: String, branch: String): GitHubPulls.Pull? {
        val key = "$owner/$repo:$branch"
        openPulls[key]?.takeIf { System.currentTimeMillis() - it.first < PULL_TTL_MS }?.let { return it.second }
        val got = ApiKeyVault.use(REF) { token -> pulls.findOpen(token, owner, repo, branch) } ?: return null
        return got.getOrNull().also { if (got.isSuccess) openPulls[key] = System.currentTimeMillis() to it }
    }

    /** Создать запрос на слияние; без входа — [NeedLoginException]. Новый запрос сразу виден в [openPull]. */
    suspend fun createPull(owner: String, repo: String, branch: String, base: String, title: String, body: String, draft: Boolean): Result<GitHubPulls.Pull> {
        val r = ApiKeyVault.use(REF) { token -> pulls.create(token, owner, repo, branch, base, title, body, draft) } ?: return Result.failure(NeedLoginException())
        openPulls.remove("$owner/$repo:$branch")
        r.onSuccess { openPulls["$owner/$repo:$branch"] = System.currentTimeMillis() to it }
        return r
    }

    /** Отправить текущую ветку проекта [dir]; без входа — [NeedLoginException]. */
    suspend fun push(dir: File): Result<GitHubPush.Target> =
        ApiKeyVault.use(REF) { token -> GitHubPush.push(dir, token) } ?: Result.failure(NeedLoginException())
}
