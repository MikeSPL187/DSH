package com.dsh.runtime.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** G-50 (гигиена): проект с клонированным кодом помнится, переименовывается и выходит из списка по доверию. */
class ClonedProjectsTest {
    @Test
    fun clonedProjectIsRememberedUntilTrusted() {
        val base = Files.createTempDirectory("cloned").toFile()
        val layout = RuntimeLayout(baseDir = base, nativeLibraryDir = File(base, "lib")).also { it.ensureDirectories() }
        assertFalse(ClonedProjects.contains(layout, "app"))
        ClonedProjects.add(layout, "app")
        assertTrue(ClonedProjects.contains(layout, "app"))
        ClonedProjects.rename(layout, "app", "android")
        assertFalse(ClonedProjects.contains(layout, "app"))
        assertTrue(ClonedProjects.contains(layout, "android"))
        ClonedProjects.trust(layout, "android")
        assertFalse(ClonedProjects.contains(layout, "android"))
    }
}
