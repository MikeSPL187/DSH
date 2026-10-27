package com.dsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dsh.core.model.ModelProviderGroup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Кэш последнего каталога моделей (меню моделей доступно сразу при холодном старте). */
data class CachedModelCatalog(
    val groups: List<ModelProviderGroup>,
    val names: List<String>,
    val ids: List<String>,
)

/** Наблюдаемое состояние кэша каталога моделей; чтение и запись — только здесь. */
class ModelCatalogCacheState(initial: CachedModelCatalog) {
    var groups by mutableStateOf(initial.groups)
    var names by mutableStateOf(initial.names)
    var ids by mutableStateOf(initial.ids)
}

private const val PREFS = "dsh_model_catalog_cache"

/** Прочитать кэш из SharedPreferences один раз; повреждённые данные — пусто. */
fun readCachedModelCatalog(context: Context): CachedModelCatalog {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val groups = runCatching {
        Json.decodeFromString(
            ListSerializer(ModelProviderGroup.serializer()),
            prefs.getString("groups", "[]").orEmpty(),
        )
    }.getOrDefault(emptyList())
    val names = prefs.getString("names", "").orEmpty()
        .split('\u0001').filter { it.isNotBlank() }
    val ids = prefs.getString("ids", "").orEmpty()
        .split('\u0001').filter { it.isNotBlank() }
    return CachedModelCatalog(groups, names, ids)
}

/** Записать кэш групп провайдеров. */
fun writeCachedModelGroups(context: Context, groups: List<ModelProviderGroup>) {
    runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(
            "groups",
            Json.encodeToString(ListSerializer(ModelProviderGroup.serializer()), groups),
        ).apply()
    }
}

/** Записать кэш названий и id моделей. */
fun writeCachedModelNames(context: Context, names: List<String>, ids: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("names", names.joinToString("\u0001"))
        .putString("ids", ids.joinToString("\u0001"))
        .apply()
}
