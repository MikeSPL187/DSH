package com.deepseek.dsh.plugin.nativehost

/**
 * Пара «APK — JS-плагин»: при установке APK ставится и JS-плагин.
 *
 * Компаньон APK — дополнение: то, что рисует его интерфейс, даёт парный JS-плагин в Host. Без JS-плагина
 * у APK нет ни логики, ни данных для отрисовки, поэтому они ставятся вместе.
 *
 * Источники пары (по приоритету):
 * 1. `dsh.plugin.jsPackage` в manifest APK (объявил автор — самое точное);
 * 2. jsPackage этого pluginId в индексе магазина (централизованно, можно поправить).
 *
 * Нет ни того ни другого — null: вызывающий сообщает «у нативного плагина нет парного JS-плагина», без догадок.
 */
object NativePluginPairing {

    /**
     * Адрес JS-плагина, который нужно поставить вместе с компаньоном.
     *
     * @param plugin найденный компаньон APK.
     * @param storeLookup поиск по индексу магазина: pluginId → jsPackage (второй источник); без индекса — null.
     * @return адрес установки (имя npm или адрес GitHub); нет обоих источников — null.
     */
    fun resolveJsPackage(
        plugin: InstalledNativePlugin,
        storeLookup: ((pluginId: String) -> String?)? = null,
    ): String? {
        // Первый источник: manifest.
        plugin.jsPackage?.takeIf { it.isNotBlank() }?.let { return it }
        // Второй: индекс магазина.
        return storeLookup?.invoke(plugin.pluginId)?.takeIf { it.isNotBlank() }
    }
}
