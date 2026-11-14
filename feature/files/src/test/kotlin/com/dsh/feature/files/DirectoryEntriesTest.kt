package com.dsh.feature.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryEntriesTest {
    @Test fun listingPreservesSortOrderAndCapturesMetadataBeforeFilesChange() {
        val root = Files.createTempDirectory("directory-entries").toFile()
        try {
            java.io.File(root, ".git").mkdir()
            java.io.File(root, "folder").mkdir()
            val file = java.io.File(root, "a.txt").apply { writeText("abc") }
            val entries = directoryEntries(root, root)
            assertEquals(listOf("folder", "a.txt"), entries.map { it.relativePath })
            assertTrue(entries[0].directory)
            assertEquals(3L, entries[1].size)
            file.writeText("changed")
            assertEquals(3L, entries[1].size)
            assertEquals(7L, directoryEntries(root, root)[1].size)
        } finally { root.deleteRecursively() }
    }
}
