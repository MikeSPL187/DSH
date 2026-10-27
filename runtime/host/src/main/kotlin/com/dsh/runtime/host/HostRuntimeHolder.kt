package com.dsh.runtime.host

/**
 * Ссылка на HostRuntimeManager в процессе — чтобы фоновая служба остановила Host при удалении задачи.
 *
 * Ещё два колбэка подставляет приложение: они соединяют «слой данных приложения» и «управление Host»,
 * не делая `runtime:host` зависимым от них:
 *
 * - [runningBridgeVersionProvider]: версия плагина, **работающего сейчас**, из manifest bridge/v1.
 *   Самый надёжный источник «установленной версии»: `package.json` на диске может быть новым,
 *   а Host ещё не перезапущен, и работает старый код.
 * - [restartHost]: перезапустить Host после установки плагина, чтобы загрузился новый код.
 */
object HostRuntimeHolder {
    @Volatile
    var manager: HostRuntimeManager? = null

    /** Версия bridge-плагина, работающего в Host; мост недоступен или плагина нет — null. */
    @Volatile
    var runningBridgeVersionProvider: (suspend () -> String?)? = null

    /** Перезапустить локальный Host; если сейчас нельзя — понятная причина отказа. */
    @Volatile
    var restartHost: (suspend () -> Result<Unit>)? = null

    /**
     * Есть ли сейчас выполняющиеся задачи агента. Нужен, чтобы «защита долгих задач»
     * держала Host в фоне только пока задача идёт, а не бесконечно.
     */
    @Volatile
    var hasRunningTasks: (() -> Boolean)? = null
}
