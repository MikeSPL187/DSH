package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.model.AccountBalanceInfo
import com.deepseek.dsh.core.model.AccountBonusInfo
import com.deepseek.dsh.core.model.AccountProfileInfo
import com.deepseek.dsh.core.model.AccountViewInfo
import com.deepseek.dsh.core.protocol.DshClientProtocol
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Аккаунт DeepSeek в Host: состояние входа, профиль, баланс, бонусы. */
class AccountRepository internal constructor(
    private val client: DshClientProtocol,
    private val calls: HostCalls,
) {
    /** Состояние аккаунта из `account/watch`; null — ещё не пришло. */
    private val _hostAccountFlow = MutableStateFlow<AccountViewInfo?>(null)
    val hostAccountFlow: StateFlow<AccountViewInfo?> = _hostAccountFlow.asStateFlow()

    /** Host сообщил, что сессия DeepSeek истекла (`deepseek-account/session-expired`). */
    private val _accountExpiredFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val accountExpiredFlow: SharedFlow<Unit> = _accountExpiredFlow.asSharedFlow()

    /** Модели нужен повторный вход (`deepseek-account/model-sign-in-required`). */
    private val _modelSignInRequiredFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val modelSignInRequiredFlow: SharedFlow<Unit> = _modelSignInRequiredFlow.asSharedFlow()

    suspend fun getAccountState(hostId: String): Result<AccountViewInfo> =
        client.getAccountState(hostId).toResult()

    suspend fun startAccountSignIn(hostId: String, callbackOrigin: String): Result<AccountViewInfo> =
        client.startAccountSignIn(hostId, callbackOrigin).toResult()

    suspend fun getAccountProfile(hostId: String): Result<AccountProfileInfo?> =
        client.getAccountProfile(hostId).toResult()

    suspend fun getAccountBalance(hostId: String): Result<AccountBalanceInfo> =
        client.getAccountBalance(hostId).toResult()

    /** Бонусы, которые ещё не показывали (`account/getUnnotifiedBonuses`): id аккаунта и список. */
    suspend fun fetchUnnotifiedBonuses(hostId: String): Pair<String, List<AccountBonusInfo>>? {
        val args = buildJsonObject { put("client", bonusClientInfo()) }
        val value = calls.value(hostId, "account/getUnnotifiedBonuses", args) as? JsonObject ?: return null
        val accountId = value.str("accountId") ?: return null
        val bonuses = (value["bonuses"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            AccountBonusInfo(
                orderId = obj.str("orderId") ?: return@mapNotNull null,
                title = obj.str("title"),
                message = obj.str("message").orEmpty(),
                expiresAt = obj.str("expiresAt").orEmpty(),
            )
        }.orEmpty()
        return accountId to bonuses
    }

    /** Отметить бонус показанным (`account/ackBonusNotified`). */
    suspend fun ackBonusNotified(hostId: String, accountId: String, orderId: String): Boolean {
        val args = buildJsonObject {
            put("accountId", accountId)
            put("orderId", orderId)
            put("client", bonusClientInfo())
        }
        return calls.ok(hostId, "account/ackBonusNotified", args)
    }

    private fun bonusClientInfo() = buildJsonObject {
        put("version", "0.2.1")
        put("locale", "en")
        put("timezoneOffsetSeconds", 0)
    }

    internal fun onAccountView(view: AccountViewInfo) {
        _hostAccountFlow.value = view
    }

    internal fun onSessionExpired() {
        _accountExpiredFlow.tryEmit(Unit)
    }

    internal fun onModelSignInRequired() {
        _modelSignInRequiredFlow.tryEmit(Unit)
    }
}
