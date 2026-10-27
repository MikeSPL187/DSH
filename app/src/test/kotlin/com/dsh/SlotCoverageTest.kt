package com.dsh

import com.dsh.core.model.DshSlotRegistry
import com.deepseek.dsh.plugin.nativesdk.slots.NativeSlotMapping
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контроль покрытия слотов: добавили официальный слот и забыли сопоставить или явно отказаться — тест падает.
 *
 * Источник: константы `DshSlotRegistry` (список официальных слотов) и явная таблица
 * `NativeSlotMapping`. Тест не зависит от интерфейса и ловит пропуск до его реализации.
 */
class SlotCoverageTest {

    private fun officialSlots(): Set<String> {
        return DshSlotRegistry::class.java.declaredFields
            .filter { field ->
                Modifier.isStatic(field.modifiers) && field.type == String::class.java
            }
            .map { field ->
                field.isAccessible = true
                field.get(null) as String
            }
            .toSet()
    }

    @Test
    fun everyOfficialSlotIsMappedOrExplicitlyUnsupported() {
        val official = officialSlots()
        val mapped = NativeSlotMapping.officialToNativeSlot.keys
        val unsupported = NativeSlotMapping.explicitlyUnsupported
        val uncovered = official - mapped - unsupported

        assertTrue(
            "Официальный слот не сопоставлен и не отмечен как неподдерживаемый: $uncovered",
            uncovered.isEmpty(),
        )
    }

    @Test
    fun mappingDoesNotReferenceUnknownSlots() {
        val official = officialSlots()
        val extra = NativeSlotMapping.allOfficialSlots - official

        assertTrue(
            "NativeSlotMapping ссылается на слот, которого нет в DshSlotRegistry: $extra",
            extra.isEmpty(),
        )
    }

    @Test
    fun mappedAndUnsupportedAreDisjoint() {
        val overlap = NativeSlotMapping.officialToNativeSlot.keys intersect
            NativeSlotMapping.explicitlyUnsupported

        assertEquals("Слот не может быть одновременно сопоставлен и не поддерживаться: $overlap", emptySet<String>(), overlap)
    }
}
