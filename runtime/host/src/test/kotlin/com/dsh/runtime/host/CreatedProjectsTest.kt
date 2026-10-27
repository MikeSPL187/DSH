package com.dsh.runtime.host

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** G-38: список папок, созданных DSH, переживает перезапуск; Default — всегда в нём. */
class CreatedProjectsTest {
    @Test
    fun namesAreStoredAndRenamed() {
        val base = Files.createTempDirectory("created").toFile()
        val layout = RuntimeLayout(baseDir = base, nativeLibraryDir = File(base, "lib")).also { it.ensureDirectories() }
        assertEquals(setOf("Default"), CreatedProjects.names(layout))
        CreatedProjects.add(layout, "app")
        CreatedProjects.add(layout, "app")
        CreatedProjects.rename(layout, "app", "android")
        assertEquals(setOf("Default", "android"), CreatedProjects.names(layout))
    }
}
