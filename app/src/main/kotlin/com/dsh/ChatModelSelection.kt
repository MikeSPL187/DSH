package com.dsh

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dsh.core.model.ModelCatalogInfo
import com.dsh.core.model.ModelItem
import com.dsh.core.model.ModelProviderGroup
import com.dsh.feature.chat.ReasoningOption

/**
 * Модель и уровень рассуждений для поля ввода чата.
 *
 * Модель сессии — из проекции Host `modelSelection`; модель из кэша сессий — запас, пока Host
 * не ответил; последняя выбранная — только для новой сессии. Пока каталог Host не пришёл,
 * показывается последняя выбранная, чтобы не мигало «Выберите модель».
 */
internal class ChatModelSelection(
    /** Название выбранной модели (как в каталоге). */
    val name: String,
    /** id модели для Host. */
    val id: String,
    /** Группа провайдера выбранной модели; null — модель не нашлась в каталоге. */
    val providerGroup: ModelProviderGroup?,
    /** Группы каталога для листа «Модель» (каталог Host или его кэш). */
    val groups: List<ModelProviderGroup>,
    /** Уровни рассуждений модели из каталога Host. */
    val efforts: List<ReasoningOption>,
    reasoning: MutableState<String>,
    private val lastUsed: LastUsedModel,
    private val available: List<String>,
) {
    /** Выбранный уровень рассуждений; сбрасывается при смене модели. */
    var reasoningId by reasoning

    /** Подпись выбранного уровня рассуждений. */
    val effortLabel: String get() = efforts.firstOrNull { it.id == reasoningId }?.label.orEmpty()

    /** Запомнить модель как последнюю выбранную (для следующей новой сессии). */
    fun rememberChoice(name: String) = lastUsed.set(name, available)
}

/** Последняя выбранная модель; хранится в SharedPreferences. */
@Stable
internal class LastUsedModel(context: Context) {
    private val prefs = context.getSharedPreferences("dsh_model_prefs", Context.MODE_PRIVATE)
    var name by mutableStateOf(prefs.getString(KEY, "").orEmpty())
        private set

    fun set(model: String, available: List<String>) {
        if (model in available && model != name) {
            name = model
            prefs.edit().putString(KEY, model).apply()
        }
    }

    private companion object {
        const val KEY = "last_model_name"
    }
}

/**
 * @param catalog каталог моделей Host.
 * @param cachedGroups группы из кэша каталога (пока Host запускается).
 * @param available названия доступных моделей (Host или кэш).
 * @param idByName id модели по названию.
 * @param sessionModel модель открытой сессии из кэша сессий.
 * @param hostSessionModel модель открытой сессии из проекции Host.
 */
@Composable
internal fun rememberChatModelSelection(
    catalog: ModelCatalogInfo,
    cachedGroups: List<ModelProviderGroup>,
    available: List<String>,
    idByName: Map<String, String>,
    sessionModel: String?,
    hostSessionModel: String?,
): ChatModelSelection {
    val context = LocalContext.current
    val lastUsed = remember { LastUsedModel(context) }

    fun nameOf(value: String?): String? = when {
        value.isNullOrBlank() -> null
        value in available -> value
        else -> idByName.entries.firstOrNull { it.value == value }?.key
    }
    val hostName = nameOf(hostSessionModel)
    val catalogDefault = catalog.groups.flatMap { it.models }.firstOrNull { it.id == catalog.defaultModel }?.name
    val current = when {
        available.isEmpty() -> lastUsed.name
        hostName != null -> hostName
        else -> nameOf(sessionModel)
            ?: lastUsed.name.takeIf { it in available }
            ?: catalogDefault
            ?: available.first()
    }
    LaunchedEffect(current, available) { lastUsed.set(current, available) }

    val groups = catalog.groups.ifEmpty { cachedGroups }
    val group = groups.firstOrNull { g -> g.models.any { it.name == current } }
    val meta: ModelItem? = group?.models?.firstOrNull { it.name == current }
    val efforts = meta?.reasoningEfforts?.map { ReasoningOption(it.id, it.name, it.description) }.orEmpty()
    val defaultEffort = meta?.defaultReasoningEffort ?: efforts.firstOrNull()?.id.orEmpty()

    val reasoning = remember(current) { mutableStateOf(defaultEffort) }
    // Уровень, которого у модели нет (каталог ещё не пришёл), заменяется её уровнем по умолчанию.
    LaunchedEffect(current, meta) {
        if (efforts.none { it.id == reasoning.value }) reasoning.value = defaultEffort
    }
    return ChatModelSelection(
        name = current,
        id = idByName[current] ?: current,
        providerGroup = group,
        groups = groups,
        efforts = efforts,
        reasoning = reasoning,
        lastUsed = lastUsed,
        available = available,
    )
}
