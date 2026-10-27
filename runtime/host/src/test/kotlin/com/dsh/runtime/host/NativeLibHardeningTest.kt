package com.dsh.runtime.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Аудит N-16: проверка защиты встроенных нативных бинарников.
 *
 * Любая библиотека с динамической секцией должна иметь GNU_RELRO; стек ни у одного
 * бинарника не должен быть исполняемым. `libproot-loader.so` — статический автономный
 * загрузчик без PT_DYNAMIC и релокаций, поэтому RELRO к нему неприменим.
 */
class NativeLibHardeningTest {
    private data class Segments(val hasDynamic: Boolean, val hasRelro: Boolean, val stackExec: Boolean?)

    private fun segments(file: File): Segments {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.getInt(0) == 0x464c457f) { "${file.name}: not ELF" }
        require(bytes.get(4).toInt() == 2) { "${file.name}: not ELF64" }
        val phoff = bytes.getLong(0x20).toInt()
        val phentsize = bytes.getShort(0x36).toInt()
        val phnum = bytes.getShort(0x38).toInt()
        var dynamic = false
        var relro = false
        var stackExec: Boolean? = null
        repeat(phnum) { index ->
            val base = phoff + index * phentsize
            when (bytes.getInt(base)) {
                PT_DYNAMIC -> dynamic = true
                PT_GNU_RELRO -> relro = true
                PT_GNU_STACK -> stackExec = (bytes.getInt(base + 4) and PF_X) != 0
            }
        }
        return Segments(dynamic, relro, stackExec)
    }

    @Test
    fun shippedNativeBinariesAreHardened() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/jniLibs").isDirectory }
        val libs = File(root, "app/src/main/jniLibs").walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        assertTrue("no native libraries found", libs.isNotEmpty())
        libs.forEach { lib ->
            val s = segments(lib)
            val name = lib.relativeTo(root).path
            assertFalse("$name: executable stack", s.stackExec == true)
            if (s.hasDynamic) assertTrue("$name: dynamic library without GNU_RELRO", s.hasRelro)
        }
    }

    private companion object {
        const val PT_DYNAMIC = 2
        const val PT_GNU_STACK = 0x6474e551
        const val PT_GNU_RELRO = 0x6474e552
        const val PF_X = 1
    }
}
