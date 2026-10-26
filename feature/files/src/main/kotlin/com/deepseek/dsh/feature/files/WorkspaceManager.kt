package com.deepseek.dsh.feature.files

import java.io.File

/**
 * Рабочие папки на телефоне и модель путей.
 */
enum class WorkspaceStorageType {
    REAL_EXTERNAL,     // общая внешняя папка (Documents / Downloads, видна в файлах системы)
    SAF_MIRROR,        // зеркало через SAF (ограниченно)
    PRIVATE_ISOLATED   // личная папка приложения, самая быстрая
}

data class FileItem(
    val name: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L
)

class WorkspaceManager(
    private val preferredPublicDir: File? = null,
    private val appPrivateDir: File
) {
    fun resolveActiveWorkspace(type: WorkspaceStorageType): File {
        return when (type) {
            WorkspaceStorageType.REAL_EXTERNAL -> {
                // 1. Сначала переданная общая папка (например, /storage/emulated/0/Documents/DSH-Workspace)
                if (preferredPublicDir != null && (preferredPublicDir.exists() || preferredPublicDir.mkdirs())) {
                    preferredPublicDir
                } else {
                    // 2. Затем стандартные общие Documents / Downloads
                    val standardDocs = File("/storage/emulated/0/Documents/DSH-Workspace")
                    val standardDownloads = File("/storage/emulated/0/Download/DSH-Workspace")

                    if (standardDocs.exists() || standardDocs.mkdirs()) {
                        standardDocs
                    } else if (standardDownloads.exists() || standardDownloads.mkdirs()) {
                        standardDownloads
                    } else {
                        // 3. Только если нет доступа ни к одной общей папке — запасной вариант
                        File(appPrivateDir, "workspace").apply { mkdirs() }
                    }
                }
            }
            WorkspaceStorageType.SAF_MIRROR -> {
                File(appPrivateDir, "saf_mirror").apply { mkdirs() }
            }
            WorkspaceStorageType.PRIVATE_ISOLATED -> {
                File(appPrivateDir, "workspace_perf").apply { mkdirs() }
            }
        }
    }

    fun listFiles(dir: File): List<FileItem> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.map { f ->
            FileItem(
                name = f.name,
                absolutePath = f.absolutePath,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isFile) f.length() else 0L,
                lastModified = f.lastModified()
            )
        }?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }

    /**
     * Все папки проектов в корне рабочих папок.
     */
    fun listWorkspaceDirectories(baseDir: File): List<File> {
        if (!baseDir.exists() || !baseDir.isDirectory) return listOf(baseDir)
        val children = baseDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
        return if (children.isEmpty()) listOf(baseDir) else children
    }
}

