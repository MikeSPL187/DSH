package com.deepseek.dsh.core.model

import kotlinx.serialization.Serializable

/**
 * Аккаунт DeepSeek (по @deepseek-ai/dsh-deepseek-account).
 */
@Serializable
data class DeepSeekUserProfile(
    val id: String,
    val name: String? = null,
    val contact: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class DeepSeekWallet(
    val currency: String = "CNY",
    val balance: String = "0.00"
)

@Serializable
data class DeepSeekAccountState(
    val isSignedIn: Boolean = false,
    val profile: DeepSeekUserProfile? = null,
    val wallets: List<DeepSeekWallet> = emptyList(),
    val bonusWallets: List<DeepSeekWallet> = emptyList(),
    /** Задан ли API-ключ (бывает «ключ есть, входа в аккаунт нет»). */
    val hasApiKey: Boolean = false,
    val platformUrl: String = "https://platform.deepseek.com"
)

/** Безопасная проекция `account/getState` Host (без учётных данных). */
data class AccountViewInfo(
    val status: String = "signed-out",
    val attemptId: String? = null,
    val attemptPhase: String? = null,
    val authorizeUrl: String? = null
)

/** Безопасный профиль из `account/getProfile` Host. */
data class AccountProfileInfo(
    val id: String? = null,
    val name: String? = null,
    val contact: String? = null,
    val avatarUrl: String? = null
)

/** Баланс из `account/getBalance` Host. */
data class AccountBalanceInfo(
    val wallets: List<DeepSeekWallet> = emptyList(),
    val bonusWallets: List<DeepSeekWallet> = emptyList()
)
