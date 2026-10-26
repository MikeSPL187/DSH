package com.deepseek.dsh.plugin.nativehost

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseek.dsh.plugin.nativesdk.host.PluginHostApi
import com.deepseek.dsh.plugin.nativesdk.slots.NativeSlot
import com.deepseek.dsh.plugin.nativesdk.slots.RenderMode
import com.deepseek.dsh.plugin.nativesdk.slots.SlotRegistry
import com.deepseek.dsh.plugin.nativesdk.theme.PluginTheme
import kotlinx.serialization.json.JsonObject

/** Как расположены стандартное содержимое и дополнение плагина. */
enum class SlotArrangement {
    /** Рядом со стандартными элементами (кнопки поля ввода, переключатели). */
    INLINE,

    /** Стандартное сверху, дополнение плагина ниже (целая полоса, целая страница). */
    BLOCK,
}

/**
 * Обёртка слота для стандартного элемента: стандартный интерфейс приходит в [content], вклады плагинов
 * объединяются с ним по правилам. Так и стандартные элементы идут через тот же слот, и плагин может
 * их ENHANCE или OVERRIDE, а не только добавлять своё через SlotRegistry.
 *
 * - нет NativeHost/Theme (превью, тесты): только стандартное содержимое;
 * - плагин OVERRIDE: стандартное скрыто, рисуется только плагин;
 * - ENHANCE: стандартное + вклады плагинов по [arrangement];
 * - ничего нет: только стандартное.
 */
@Composable
fun OfficialSlot(
    slot: NativeSlot,
    modifier: Modifier = Modifier,
    spec: JsonObject = JsonObject(emptyMap()),
    arrangement: SlotArrangement = SlotArrangement.INLINE,
    content: @Composable () -> Unit,
) {
    val host = NativeHost.LocalHost.current
    val theme = NativeHost.LocalTheme.current
    if (host == null || theme == null) {
        content()
        return
    }
    val contributions = SlotRegistry.resolve(slot)
    val slotHost = androidx.compose.runtime.remember(host) { SlotBridgeHost(host) }
    val override = contributions.firstOrNull { it.mode == RenderMode.OVERRIDE && !it.isOfficial }
    if (override != null) {
        NativeSlotGuard(pluginLabel = override.javaClass.simpleName, theme = theme) {
            override.Render(spec, theme, slotHost)
        }
        return
    }
    if (contributions.isEmpty()) {
        content()
        return
    }
    when (arrangement) {
        SlotArrangement.INLINE -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
            for (contribution in contributions) {
                NativeSlotGuard(pluginLabel = contribution.javaClass.simpleName, theme = theme) {
                    contribution.Render(spec, theme, slotHost)
                }
            }
        }

        SlotArrangement.BLOCK -> Column(
            modifier = modifier
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            for (contribution in contributions) {
                NativeSlotGuard(pluginLabel = contribution.javaClass.simpleName, theme = theme) {
                    contribution.Render(spec, theme, slotHost)
                }
            }
        }
    }
}

/**
 * Общий контейнер места вставки: стоит в каком-то месте интерфейса (док, у поля ввода, настройки…),
 * берёт из [SlotRegistry] вклады плагинов для этого места и рисует найденные по очереди.
 *
 * Это ядро «API мест вставки»: приложение ничего не зашивает в место, только тонкий контейнер;
 * что показать, решает плагин. Нет регистраций — контейнер ничего не рисует и места не занимает.
 *
 * Отрисовка в ограждении ошибок: исключение одного вклада заменяет лишь его заглушкой, страница цела.
 *
 * @param slot место этого контейнера.
 * @param spec свежий снимок состояния места (Host присылает через client/refresh; без состояния — пустой объект).
 * @param theme снимок темы хоста.
 * @param host реализация моста возможностей.
 */
@Composable
fun NativeSlotHost(
    slot: NativeSlot,
    spec: JsonObject,
    theme: PluginTheme,
    host: PluginHostApi,
    modifier: Modifier = Modifier,
    /** true — компактный элемент строки (строка состояния под полем ввода), а не вертикальная карточка. */
    inline: Boolean = false,
    /** Можно ли листать строчное место по горизонтали, если не влезает (во внешнем прокручиваемом — false). */
    scrollable: Boolean = false,
) {
    val contributions = SlotRegistry.resolve(slot)
    if (contributions.isEmpty()) return
    // Вклады в слоты и нативные страницы делят RPC Host: базовый мост превращается
    // в PluginRpcHost, иначе плагин не прочитает своё состояние (лимиты, аккаунт и т. п.).
    val slotHost = androidx.compose.runtime.remember(host) { SlotBridgeHost(host) }
    if (inline) {
        Row(
            modifier = if (scrollable) {
                modifier.horizontalScroll(rememberScrollState())
            } else {
                modifier
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (contribution in contributions) {
                NativeSlotGuard(pluginLabel = contribution.javaClass.simpleName, theme = theme) {
                    contribution.Render(spec, theme, slotHost)
                }
            }
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        for (contribution in contributions) {
            NativeSlotGuard(pluginLabel = contribution.javaClass.simpleName, theme = theme) {
                contribution.Render(spec, theme, slotHost)
            }
        }
    }
}

/**
 * Ограждение ошибок одного вклада: исключение при отрисовке — заглушка, страница хоста не падает.
 *
 * Ошибка при отрисовке ловится «дочерней композицией + запомненной меткой сбоя»: после сбоя навсегда
 * заглушка, содержимое не перекомпонуется (иначе плохой плагин падал бы каждый кадр).
 */
@Composable
internal fun NativeSlotGuard(pluginLabel: String, theme: PluginTheme, content: @Composable () -> Unit) {
    val failed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (failed.value) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.deepseek.dsh.plugin.nativehost.R.string.native_slot_load_failed, pluginLabel),
            style = theme.textCaption,
            color = theme.colorOnSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(theme.spacingUnit),
        )
        return
    }
    // Compose не позволяет обернуть @Composable-вызов в try/catch и не имеет границ исключений.
    // Надёжно — дочерняя композиция с ключом и колбэк «сообщить о сбое» через CompositionLocal:
    // исключение композиции всплывает через обработку ошибок Compose и обрабатывается
    // на уровне приложения; здесь главное — после сбоя не перекомпоновывать, чтобы не падать снова.
    val onCrash = androidx.compose.runtime.remember {
        { failed.value = true }
    }
    androidx.compose.runtime.key(pluginLabel) {
        CompositionLocalCrashReporter.provided(onCrash) {
            content()
        }
    }
}

/**
 * CompositionLocal «сообщить о сбое» для дочерней композиции. При ошибке содержимого вызывающий
 * сообщает через него, и хост заменяет плагин заглушкой.
 */
private object CompositionLocalCrashReporter {
    val LocalOnCrash = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

    @Composable
    fun provided(onCrash: () -> Unit, content: @Composable () -> Unit) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOnCrash provides onCrash) {
            content()
        }
    }
}
