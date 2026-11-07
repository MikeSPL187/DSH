package com.dsh

import java.io.File
import java.nio.file.Paths

/** Persisted projects use Android paths; Host session cwd uses the /workspace mount. */
internal fun automationWorkspacePath(project: String, androidRoot: File): String? = runCatching {
    if (project.isBlank()) return@runCatching HOST_WORKSPACE_ROOT
    val guestRoot = Paths.get(HOST_WORKSPACE_ROOT)
    val root = androidRoot.canonicalFile.toPath()
    if (project == HOST_WORKSPACE_ROOT || project.startsWith("$HOST_WORKSPACE_ROOT/")) {
        val guest = Paths.get(project).normalize().takeIf { it.startsWith(guestRoot) } ?: return@runCatching null
        val selected = File(androidRoot, guestRoot.relativize(guest).toString()).canonicalFile.toPath()
        return@runCatching guest.toString().takeIf { selected.startsWith(root) }
    }
    if (!File(project).isAbsolute) return@runCatching null
    val selected = File(project).canonicalFile.toPath()
    if (!selected.startsWith(root)) return@runCatching null
    guestRoot.resolve(root.relativize(selected)).toString()
}.getOrNull()
