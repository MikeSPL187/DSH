package com.deepseek.dsh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Блоки из настоящей записи bash в PTY (`bash-session.json`: что отправили → что пришло). */
class TermEngineTest {
    private var now = 0L
    private val model = TermModel("t") { now }

    private fun session(): List<Pair<String, String>> {
        val raw = javaClass.getResource("/terminal/bash-session.json")!!.readText()
        val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray
        return arr.map {
            val pair = it as kotlinx.serialization.json.JsonArray
            (pair[0] as kotlinx.serialization.json.JsonPrimitive).content to (pair[1] as kotlinx.serialization.json.JsonPrimitive).content
        }
    }

    private fun replay() {
        for ((sent, got) in session()) {
            now += 1000
            when {
                sent.isEmpty() -> Unit
                sent == TermModel.SETUP -> assertEquals(sent, model.setupLine())
                sent == "\u0003" -> Unit
                else -> model.submit(sent.removeSuffix("\r"))
            }
            model.feed(got)
        }
    }

    @Test
    fun setupLineIsHiddenAndBlocksGetCodes() {
        replay()
        assertTrue(model.integrated)
        val b = model.blocks
        assertEquals(listOf("echo hello", "ls --color=always /", "nosuchcmd", "cd /tmp", "printf \"a 10%%\\rb 50%%\\r\\033[Kdone\\n\"", "sleep 5", "exit"), b.map { it.cmd })
        assertEquals("hello", b[0].out.plain())
        assertEquals(BlockState.Ok, b[0].state)
        assertEquals(0, b[0].code)
        assertEquals(BlockState.Err, b[2].state)
        assertEquals(127, b[2].code)
        assertTrue(b[2].out.plain().contains("command not found"))
        assertEquals("", b[3].out.plain())
        assertEquals("done", b[4].out.plain())
        assertEquals(130, b[5].code)
        assertEquals("/tmp", model.cwd)
        // приглашение и эхо команд в вывод не попадают
        assertFalse(b.any { it.out.plain().contains("user@phone") })
    }

    @Test
    fun colorsMapToTones() {
        replay()
        val ls = model.blocks[1].out.lines
        val first = ls.first()
        val at = first.first.indexOf("dev")
        assertEquals(Tone.Pr, first.second[at])
        assertTrue(model.blocks[2].out.lines.first().second.all { it == Tone.Plain })
    }

    @Test
    fun cwdUsesTildeForHome() {
        model.setupLine()
        model.feed("\u001b]133;D;0;/root/workspace;/root\u0007")
        assertEquals("~/workspace", model.cwd)
    }

    @Test
    fun splitEscapeAcrossFrames() {
        model.setupLine()
        model.feed("\u001b]133;D;0;/r;/r\u0007")
        val blk = model.submit("make")
        model.feed("make\r\n\u001b[3")
        model.feed("1merr\u001b[0m 42%\r\n\u001b]133;")
        assertEquals(42, blk.progress)
        model.feed("D;2;/r;/r\u0007")
        assertEquals(BlockState.Err, blk.state)
        assertEquals("err 42%", blk.out.plain())
        assertEquals(Tone.Err, blk.out.lines[0].second[0])
        assertNull(blk.progress)
    }

    @Test
    fun withoutMarkersBlocksStayPlain() {
        model.setupLine()
        model.feed("$ setup echo\r\n$ ")
        now += TermModel.SETUP_TIMEOUT_MS + 1
        model.checkSetupTimeout()
        val blk = model.submit("ls")
        model.feed("ls\r\na b\r\n$ ")
        assertFalse(model.integrated)
        assertEquals(BlockState.Plain, blk.state)
        assertEquals("a b\n$", blk.out.plain())
    }

    /** Блок с метками: вывод после эха команды идёт в него. */
    private fun liveBlock(): TermBlock {
        model.setupLine()
        model.feed("\u001b]133;D;0;/r;/r\u0007")
        val blk = model.submit("cat x")
        model.feed("cat x\r\n")
        return blk
    }

    @Test
    fun cursorFarToTheRightDoesNotGrowTheLine() {
        val blk = liveBlock()
        // Курсор на 5 млн колонок вправо (а `ESC [ 2147483647 G` — OutOfMemoryError).
        model.feed("\u001b[5000000Gx\u001b[2147483647Cy\t\t\r\n")
        assertTrue(blk.out.lines.all { it.first.length <= TermLines.MAX_COLS })
    }

    @Test
    fun veryLongLineIsWrappedNotKeptWhole() {
        val blk = liveBlock()
        model.feed("a".repeat(100_000) + "\r\n")
        assertTrue(blk.out.lines.all { it.first.length <= TermLines.MAX_COLS })
        assertEquals(100_000, blk.out.plain().count { it == 'a' })
    }

    @Test
    fun unterminatedOscDoesNotSwallowLaterOutput() {
        val blk = liveBlock()
        model.feed("\u001b]" + "z".repeat(50_000))
        model.feed("\r\nhello\r\n")
        assertTrue(blk.out.plain().endsWith("hello"))
    }
}

/** H-3: память и пересборка вывода терминала ограничены. */
class TermLimitsTest {
    @Test
    fun blocksPerTabAreCapped() {
        val model = TermModel("t") { 0L }
        repeat(TermModel.MAX_BLOCKS + 50) { model.submit("echo $it") }
        assertEquals(TermModel.MAX_BLOCKS, model.blocks.size)
        assertEquals("echo ${TermModel.MAX_BLOCKS + 49}", model.blocks.last().cmd)
    }

    @Test
    fun tailAndLastLineDoNotNeedWholeOutput() {
        val out = TermLines()
        repeat(1000) { out.put('a'); out.lf() }
        "progress 42%".forEach { out.put(it) }
        out.lf()
        assertEquals(10, out.tail(10).size)
        assertEquals("progress 42%", out.lastNonBlank())
        assertEquals(42, TermModel.lastPercent(out))
    }
}
