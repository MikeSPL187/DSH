package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.protocol.DshClientProtocol
import com.deepseek.dsh.core.protocol.RemoteResult
import java.lang.reflect.Proxy

/**
 * Подставной Host для тестов: отвечает на `rpcRaw`, записывает вызовы и ответы на waterfall.
 * Остальные методы протокола в тестах не нужны и бросают исключение.
 */
internal class FakeHost(private val answer: (method: String, args: String) -> String?) {
    val calls = mutableListOf<Pair<String, String>>()

    /** Ответы на запросы waterfall: id события → JSON ответа (null — `next`). */
    val answers = mutableListOf<Pair<String, String?>>()

    val client: DshClientProtocol = Proxy.newProxyInstance(
        DshClientProtocol::class.java.classLoader,
        arrayOf(DshClientProtocol::class.java),
    ) { _, m, args ->
        when (m.name) {
            "rpcRaw" -> {
                val method = args[1] as String
                val json = args[2] as String
                calls += method to json
                answer(method, json)?.let { RemoteResult.Success(it) } ?: RemoteResult.Failure("test", "no answer")
            }
            "answerWaterfall" -> {
                answers += (args[1] as String) to (args[2] as String?)
                Unit
            }
            "toString" -> "FakeHost"
            "hashCode" -> 0
            "equals" -> false
            else -> error("FakeHost: ${m.name} is not expected")
        }
    } as DshClientProtocol
}

/** Конверт ответа Host с [value] (JSON). */
internal fun hostOk(value: String) = """{"result":{"ok":true,"value":$value}}"""
