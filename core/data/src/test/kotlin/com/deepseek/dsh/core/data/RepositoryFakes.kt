package com.deepseek.dsh.core.data

import com.deepseek.dsh.core.protocol.DshClientProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.reflect.Proxy

/**
 * Подставные DAO и клиент Host для тестов [DshRepository]. Методы отвечают сразу (без приостановки),
 * поэтому `suspend`-методы можно отдавать через [Proxy]: результат возвращается вместо COROUTINE_SUSPENDED.
 */
internal class RepositoryFakes(private val answer: (method: String, args: List<Any?>) -> Any?) {
    val clientCalls = mutableListOf<String>()
    val daoCalls = mutableListOf<String>()

    val client: DshClientProtocol = Proxy.newProxyInstance(
        DshClientProtocol::class.java.classLoader,
        arrayOf(DshClientProtocol::class.java),
    ) { _, m, args ->
        when (m.name) {
            "toString" -> "RepositoryFakes"
            "hashCode" -> 0
            "equals" -> false
            else -> {
                clientCalls += m.name
                answer(m.name, args?.toList().orEmpty())
            }
        }
    } as DshClientProtocol

    val dao: DshDao = Proxy.newProxyInstance(DshDao::class.java.classLoader, arrayOf(DshDao::class.java)) { _, m, _ ->
        when (m.name) {
            "toString" -> "FakeDao"
            "hashCode" -> 0
            "equals" -> false
            else -> {
                daoCalls += m.name
                if (m.returnType == Int::class.javaPrimitiveType) 0 else Unit
            }
        }
    } as DshDao

    fun repository(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) =
        DshRepository(dao, client, scope)
}
