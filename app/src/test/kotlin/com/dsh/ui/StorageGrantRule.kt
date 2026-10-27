package com.dsh.ui

import com.dsh.StorageAccess
import org.junit.rules.ExternalResource

/** Выдаёт доступ к хранилищу до запуска Activity (иначе приложение показывает экран разрешения). */
internal class StorageGrantRule(private val grant: Boolean = true) : ExternalResource() {
    override fun before() {
        StorageAccess.overrideForTests = grant
    }

    override fun after() {
        StorageAccess.overrideForTests = null
    }
}
