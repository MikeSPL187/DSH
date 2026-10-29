package com.dsh.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Удаление папки из «Файлов» не выходит за рабочую папку по символическим ссылкам. */
class DeleteTreeTest {
    @Test
    fun `deleting a folder removes links but never their targets`() {
        val tmp = Files.createTempDirectory("dsh-delete").toFile()
        try {
            val outside = File(tmp, "outside").apply { mkdirs() }
            val keep = File(outside, "keep.txt").apply { writeText("must survive") }
            val project = File(tmp, "project/nested-folder").apply { mkdirs() }
            File(project, "a.txt").writeText("a")
            File(project, "deep/er").mkdirs()
            Files.createSymbolicLink(File(project, "linked-dir").toPath(), outside.toPath())
            Files.createSymbolicLink(File(project, "linked-file").toPath(), keep.toPath())

            assertTrue(deleteTree(project))
            assertFalse(project.exists())
            assertTrue("a file outside the workspace was deleted through a link", keep.exists())
            assertEquals("must survive", keep.readText())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `deleting a link to a folder removes only the link`() {
        val tmp = Files.createTempDirectory("dsh-delete-link").toFile()
        try {
            val outside = File(tmp, "outside").apply { mkdirs() }
            File(outside, "keep.txt").writeText("x")
            val link = File(tmp, "link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(deleteTree(link))
            assertTrue(File(outside, "keep.txt").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `missing path counts as deleted`() {
        assertTrue(deleteTree(File(Files.createTempDirectory("dsh-none").toFile(), "nope")))
    }
}
