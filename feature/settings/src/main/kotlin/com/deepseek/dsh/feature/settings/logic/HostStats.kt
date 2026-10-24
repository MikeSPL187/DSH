package com.deepseek.dsh.feature.settings.logic

import java.io.File

/**
 * Память и процессор Host: процесс [pid] (proot) и все его потомки, по `/proc`.
 * CPU — доля всех ядер между двумя замерами.
 */
class HostStats {
    private var lastTicks = 0L
    private var lastAt = 0L

    data class Sample(val rssBytes: Long, val cpuPercent: Int?)

    fun sample(pid: Long): Sample? {
        val procs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return null
        val parent = HashMap<Long, Long>()
        val ticks = HashMap<Long, Long>()
        for (p in procs) {
            val stat = runCatching { File(p, "stat").readText() }.getOrNull() ?: continue
            val tail = stat.substringAfterLast(')').trim().split(' ')
            val id = p.name.toLong()
            parent[id] = tail.getOrNull(1)?.toLongOrNull() ?: continue
            ticks[id] = (tail.getOrNull(11)?.toLongOrNull() ?: 0) + (tail.getOrNull(12)?.toLongOrNull() ?: 0)
        }
        if (pid !in parent) return null
        val tree = mutableSetOf(pid)
        var grew = true
        while (grew) {
            grew = false
            for ((id, pp) in parent) if (pp in tree && tree.add(id)) grew = true
        }
        var rss = 0L
        for (id in tree) {
            runCatching {
                File("/proc/$id/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
                    ?.filter(Char::isDigit)?.toLongOrNull()?.let { rss += it * 1024 }
            }
        }
        val total = tree.sumOf { ticks[it] ?: 0 }
        val now = System.nanoTime()
        val cpu = if (lastAt > 0 && total >= lastTicks) {
            val hz = 100.0
            val sec = (now - lastAt) / 1e9
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            (((total - lastTicks) / hz) / sec / cores * 100).toInt().coerceIn(0, 100)
        } else null
        lastTicks = total; lastAt = now
        return Sample(rss, cpu)
    }
}
