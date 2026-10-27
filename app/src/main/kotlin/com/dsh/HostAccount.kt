package com.dsh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.dsh.core.data.DshRepository
import com.dsh.core.protocol.DeepSeekOAuthManager
import com.dsh.runtime.host.HostRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Вход в аккаунт DeepSeek через Host: `account/startSignIn` выдаёт ссылку авторизации
 * для этой попытки, ссылка и итог приходят через `account/watch`. Здесь — запуск входа,
 * открытие ссылки и подсказки, если Host не ответил или ссылка не пришла.
 */
class HostAccount internal constructor(
    private val context: Context,
    private val ui: AppUiState,
    private val repository: DshRepository,
    private val runtimeManager: HostRuntimeManager,
    private val scope: CoroutineScope,
    private val workspace: () -> File,
    private val baseUrl: () -> String,
) {
    fun signIn() {
        scope.launch {
            ui.signInPending = true
            ui.signInOpenedUrl = null
            val callbackOrigin = baseUrl()
            var view = repository.account.startAccountSignIn(ui.activeHostId, callbackOrigin).getOrNull()
            if (view == null) {
                runCatching { runtimeManager.start(workspace().absolutePath) }
                // Host поднимается не сразу: до 10 попыток раз в 2 с, первая удачная — последняя.
                for (attempt in 1..10) {
                    kotlinx.coroutines.delay(2000)
                    view = repository.account.startAccountSignIn(ui.activeHostId, baseUrl()).getOrNull()
                    if (view != null) break
                }
            }
            if (view == null) {
                ui.signInPending = false
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.login_failed_host),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            when {
                view.status == "credential-stored" -> ui.signInPending = false
                !view.authorizeUrl.isNullOrBlank() -> {
                    ui.signInOpenedUrl = view.authorizeUrl
                    openWebLink(context, view.authorizeUrl!!)
                }
                else -> {
                    // Ссылку присылает watch; нет её 30 секунд — сообщаем об ошибке.
                    kotlinx.coroutines.delay(30_000)
                    if (ui.signInPending && ui.signInOpenedUrl == null) {
                        ui.signInPending = false
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.login_failed_link),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            if (ui.signInPending) {
                // Слишком долго на странице входа: watch не сообщил ни успеха, ни ошибки — сказать один раз.
                kotlinx.coroutines.delay(10 * 60_000)
                if (ui.signInPending) {
                    ui.signInPending = false
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.login_incomplete),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

/** Вход в аккаунт и подписка на его состояние у Host (вход, сохранённый ключ, выход, истечение). */
@Composable
internal fun rememberHostAccount(
    ui: AppUiState,
    repository: DshRepository,
    oauthManager: DeepSeekOAuthManager,
    runtimeManager: HostRuntimeManager,
    scope: CoroutineScope,
    workspace: () -> File,
    baseUrl: () -> String,
): HostAccount {
    val context = LocalContext.current
    LaunchedEffect(ui.activeHostId) {
        kotlinx.coroutines.coroutineScope {
            launch {
                repository.account.hostAccountFlow.collect { view ->
                    val pending = ui.signInPending
                    when (view?.status) {
                        "credential-stored" -> {
                            ui.signInPending = false
                            ui.signInOpenedUrl = null
                            if (!oauthManager.accountFlow.value.isSignedIn) {
                                val profile = repository.account.getAccountProfile(ui.activeHostId).getOrNull()
                                val balance = repository.account.getAccountBalance(ui.activeHostId).getOrNull()
                                oauthManager.applyHostAccount(profile, balance)
                            }
                        }
                        "signed-out" -> {
                            ui.signInPending = false
                            if (oauthManager.accountFlow.value.isSignedIn) oauthManager.signOut()
                        }
                        else -> if (pending && view != null) {
                            // Ссылку на вход Host получает асинхронно и присылает через watch — опрос getState не нужен.
                            val url = view.authorizeUrl
                            if (!url.isNullOrBlank() && url != ui.signInOpenedUrl) {
                                ui.signInOpenedUrl = url
                                openWebLink(context, url)
                            }
                            if (view.attemptPhase in setOf("failed", "expired", "cancelled")) {
                                ui.signInPending = false
                                ui.signInOpenedUrl = null
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.login_incomplete),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }
            launch {
                // Host сообщил, что сессия DeepSeek истекла: сбросить вход (API-ключ остаётся).
                repository.account.accountExpiredFlow.collect { oauthManager.signOut() }
            }
            launch {
                // Модели нужен повторный вход: сразу открыть вход.
                repository.account.modelSignInRequiredFlow.collect { ui.showSignInDialog = true }
            }
        }
    }
    return remember(repository, runtimeManager) { HostAccount(context, ui, repository, runtimeManager, scope, workspace, baseUrl) }
}
