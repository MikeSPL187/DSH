package com.dsh.feature.settings.logic

import com.dsh.core.data.AgentLimit
import com.dsh.core.data.AgentLimitValue

/** Шаги степпера «Пределов агента» по `LIM` прототипа (в единицах Host: мс, байты, штуки). */
object AgentLimitSteps {
    private val BASE: Map<AgentLimit, List<Long>> = mapOf(
        AgentLimit.COMMAND_TIME to listOf(10L, 30, 60, 120, 300, 600).map { it * 1000 },
        AgentLimit.COMMAND_OUTPUT to listOf(16L, 32, 64, 128, 256, 512, 1000).map { it * 1000 },
        AgentLimit.SUBAGENT_DEPTH to listOf(0L, 1, 2, 3, 4, 5),
        AgentLimit.SUBAGENT_ACTIVE to listOf(1L, 2, 4, 6, 8, 12, 16),
        AgentLimit.PARALLEL_CALLS to listOf(1L, 2, 4, 6, 8, 10, 16, 32),
        AgentLimit.WEB_RESULTS to listOf(3L, 5, 10, 20, 30, 50),
        AgentLimit.WEB_SEARCHES to listOf(1L, 2, 3, 5, 8, 10),
    )

    /**
     * Шаги для [v]: стандартные плюс текущее и стандартное значение Host (значение из `cordis.yml` может
     * не совпадать ни с одним шагом — степпер не должен его терять), не выше потолка Host.
     */
    fun of(v: AgentLimitValue): List<Long> {
        val all = (BASE.getValue(v.limit) + v.value + v.base).distinct().sorted()
        val ceiling = v.ceiling ?: return all
        return all.filter { it <= ceiling }.ifEmpty { listOf(ceiling) }
    }

    /** Соседний шаг от [current] в сторону [dir] (−1 / +1); на краю — [current]. */
    fun next(steps: List<Long>, current: Long, dir: Int): Long {
        val i = steps.indexOfFirst { it >= current }.let { if (it < 0) steps.size else it }
        val exact = i < steps.size && steps[i] == current
        val target = when {
            dir < 0 -> i - 1
            exact -> i + 1
            else -> i
        }
        return steps.getOrNull(target) ?: current
    }
}
