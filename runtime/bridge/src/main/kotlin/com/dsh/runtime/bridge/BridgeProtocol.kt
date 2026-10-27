package com.dsh.runtime.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Протокол JSON-RPC моста возможностей устройства и модель возможностей.
 */
@Serializable
data class BridgeRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    /**
     * Токен локальной авторизации.
     *
     * Мост слушает 127.0.0.1, но на Android loopback — не «только это приложение»,
     * случайный порт лишь снижает вероятность; патчи Host читают его из
     * `/root/.dsh/.bridge_token` гостя и передают в каждом запросе. Без токена или с неверным
     * запрос отклоняется, никакие действия на устройстве не выполняются.
     */
    val auth: String? = null
)

@Serializable
data class BridgeResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: BridgeError? = null
)

@Serializable
data class BridgeError(
    val code: Int,
    val message: String
)

enum class BridgeMethod(val methodName: String) {
    GET_DEVICE_INFO("android_device_info"),
    GET_CLIPBOARD("android_clipboard_get"),
    SET_CLIPBOARD("android_clipboard_set"),
    SHOW_NOTIFICATION("android_notify"),
    SPEAK_TTS("android_tts"),
    PICK_FILE("android_pick_file"),
    SHARE_TEXT("android_share"),
    EXEC_SHELL("android_exec_shell"),
    EXEC_PROOT("android_exec_proot")
}


