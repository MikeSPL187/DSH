package com.deepseek.dsh.plugin.nativehost

/** Нативный плагин-компаньон (APK) в списке «Плагинов»: установленный или доступный для установки. */
data class NativePluginItem(
    val pluginId: String,
    val name: String,
    val description: String,
    val version: String,
    val capabilities: List<String>,
    /** true — установлен на телефоне, false — доступен в магазине. */
    val installed: Boolean,
    /** Состояние установленного: работает, ждёт доверия, нужно обновить, подпись не совпала. */
    val statusLabel: String? = null,
    val signatureFingerprint: String? = null,
    /** Android-пакет APK (для удаления `package:<имя>`); `pluginId` — заявленный автором id, это разные вещи. */
    val packageName: String? = null,
    /** Парный JS-плагин из манифеста APK (`dsh.plugin.jsPackage`): компаньон показывается в его карточке. */
    val jsPackage: String? = null,
    /** Время обновления APK, мс — по нему магазин подсказывает обновление. */
    val lastUpdateTime: Long = 0L,
)
