package com.dsh

import com.dsh.runtime.host.FileCheckpoints
import com.dsh.runtime.host.RuntimeLayout
import java.io.File

/** Точки возврата файлов открытого проекта: один [FileCheckpoints] на папку (в нём блокировки снимков). */
internal object ProjectCheckpoints {
    private var cached: Pair<String, FileCheckpoints>? = null

    @Synchronized
    fun of(project: File, layout: RuntimeLayout): FileCheckpoints {
        val key = project.absolutePath
        cached?.takeIf { it.first == key }?.let { return it.second }
        return FileCheckpoints(project, File(layout.runtimeMetaDir, "checkpoints")).also { cached = key to it }
    }
}
