package com.dsh.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BranchNameTest {
    @Test
    fun `branch name from a commit subject`() {
        assertEquals("dsh/obnovit-kotlin-do-2-0-21", branchSlug("Обновить Kotlin до 2.0.21"))
        assertEquals("dsh/fix-login-on-android-8", branchSlug("Fix login on Android 8!"))
        assertEquals("dsh/changes", branchSlug("…"))
        assertEquals(44, branchSlug("a".repeat(30) + " " + "b".repeat(30)).length)
        assertNull(branchNameProblem(branchSlug("Сделать «вход» через GitHub"), "main"))
    }

    @Test
    fun `branch name rules`() {
        assertNull(branchNameProblem("dsh/fix-1.2_b", "main"))
        assertNull(branchNameProblem("", "main"))
        assertEquals(BranchNameProblem.CHARS, branchNameProblem("dsh/обнов", "main"))
        assertEquals(BranchNameProblem.CHARS, branchNameProblem("a b", "main"))
        listOf("-x", "/x", "x/", "x.", "a..b", "a//b", "a/.b", "x.lock", ".x").forEach {
            assertEquals(it, BranchNameProblem.FORM, branchNameProblem(it, "main"))
        }
        assertEquals(BranchNameProblem.IS_BASE, branchNameProblem("main", "main"))
    }
}
