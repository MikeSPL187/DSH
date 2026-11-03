package com.dsh.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Приглашение bash с bracketed paste — это ожидание: иначе настройка меток не уходила, и у блоков не было кода. */
class LooksIdleTest {
    @Test
    fun promptWithBracketedPasteIsIdle() {
        assertTrue(looksIdle("\u001B[?2004hroot@vm:/workspace/Default# \u001B[?2004h"))
        assertTrue(looksIdle("total 12\r\n\u001B]133;D;0;/;/root\u0007\u001B[?2004hroot@vm:/# "))
    }

    @Test
    fun runningProgramIsNotIdle() {
        assertFalse(looksIdle("Downloading 45%\r\n"))
    }
}
