package com.dsh

/** Keep a bounded recovery tail without copying the whole answer for every token. */
internal class JournalPartialBuffer(initial: String) {
    private val text = StringBuilder(initial)

    val isNotEmpty: Boolean get() = text.isNotEmpty()

    fun append(delta: String, baseline: Boolean) {
        if (baseline || delta.length >= LIMIT) text.setLength(0)
        text.append(delta, tailStart(delta), delta.length)
        // Compact only after a full tail of additional text, not on every delta at capacity.
        if (text.length > 2 * LIMIT) text.delete(0, tailStart(text))
    }

    fun snapshot(): String = text.substring(tailStart(text))

    private fun tailStart(value: CharSequence): Int {
        var start = (value.length - LIMIT).coerceAtLeast(0)
        if (start > 0 && value[start].isLowSurrogate() && value[start - 1].isHighSurrogate()) start++
        return start
    }

    companion object {
        const val LIMIT = 128 * 1024
    }
}
