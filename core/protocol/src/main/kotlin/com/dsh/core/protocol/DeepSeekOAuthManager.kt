package com.dsh.core.protocol

import com.dsh.core.model.AccountBalanceInfo
import com.dsh.core.model.AccountProfileInfo
import com.dsh.core.model.DeepSeekAccountState
import com.dsh.core.model.DeepSeekUserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние входа в аккаунт DeepSeek.
 * Вход — через OAuth DeepSeek Platform.
 */
class DeepSeekOAuthManager {

    // Без сохранённых данных — не вошли; демо-аккаунта и баланса нет.
    private val _accountFlow = MutableStateFlow(
        DeepSeekAccountState(
            isSignedIn = false,
            profile = null,
            wallets = emptyList()
        )
    )
    val accountFlow: StateFlow<DeepSeekAccountState> = _accountFlow.asStateFlow()

    fun signOut() {
        // Выход не удаляет API-ключ: это независимые способы входа.
        _accountFlow.value = DeepSeekAccountState(
            isSignedIn = false,
            profile = null,
            wallets = emptyList(),
            hasApiKey = _accountFlow.value.hasApiKey
        )
    }

    /**
     * Вход по API-ключу («Добавить API-ключ»).
     * Отмечается только «ключ задан», аккаунт остаётся без входа; сам ключ здесь не хранится.
     */
    fun signInWithApiKey(apiKey: String) {
        _accountFlow.value = DeepSeekAccountState(
            isSignedIn = false,
            profile = null,
            wallets = emptyList(),
            hasApiKey = apiKey.isNotBlank()
        )
    }

    /** После credential-stored из account/watch — профиль и баланс из Host. */
    fun applyHostAccount(profile: AccountProfileInfo?, balance: AccountBalanceInfo?) {
        _accountFlow.value = DeepSeekAccountState(
            isSignedIn = true,
            profile = DeepSeekUserProfile(
                id = profile?.id ?: "deepseek-account",
                name = profile?.name ?: com.dsh.core.model.Tr.t("Signed in to DeepSeek", "Вход в DeepSeek выполнен"),
                contact = profile?.contact,
                avatarUrl = profile?.avatarUrl,
            ),
            wallets = balance?.wallets ?: emptyList(),
            bonusWallets = balance?.bonusWallets ?: emptyList(),
            hasApiKey = _accountFlow.value.hasApiKey,
        )
    }
}
