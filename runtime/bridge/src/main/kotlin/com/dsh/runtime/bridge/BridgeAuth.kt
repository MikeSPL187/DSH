package com.dsh.runtime.bridge

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Локальная авторизация моста устройства.
 *
 * Мост слушает 127.0.0.1, но на Android loopback — не «только это приложение»: любое приложение
 * на телефоне может подключиться к 127.0.0.1:<случайный порт>; случайный порт лишь снижает вероятность попадания и авторизацией не является.
 *
 * Сторона Host (патчи bash / fs-search в proot) читает токен из
 * [TOKEN_PATH_IN_GUEST] гостя и передаёт в каждом запросе; приложение меняет токен при каждом запуске Host.
 * Файл лежит в личной папке приложения, другие приложения Android его не прочитают, а без
 * токена мост не вызвать.
 */
object BridgeAuth {

    /** Постоянный путь токена моста у гостя: `/root/.dsh/.bridge_token` в rootfs. */
    const val TOKEN_PATH_IN_GUEST: String = "/root/.dsh/.bridge_token"

    /** Имя файла токена с точки зрения [java.io.File]. */
    const val TOKEN_FILE_NAME: String = ".bridge_token"

    /** Код ошибки JSON-RPC: запрос не прошёл локальную авторизацию. */
    const val UNAUTHORIZED_CODE: Int = -32001

    /** Число случайных байтов токена (256 бит). */
    private const val TOKEN_BYTES: Int = 32

    /**
     * Создать новый токен моста.
     * @param random источник случайности; по умолчанию [SecureRandom].
     * @returns токен 256 бит, URL-safe, без дополнения.
     */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Сравнить ожидаемый токен с присланным за постоянное время.
     * @param expected токен текущего процесса; null или пусто — не задан, это отказ.
     * @param presented токен из запроса; null или пусто — отказ.
     * @returns true при полном совпадении.
     */
    fun verify(expected: String?, presented: String?): Boolean {
        if (expected.isNullOrEmpty() || presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * Записать токен в файл, читаемый гостем, с правами только для владельца.
     *
     * Сначала временный файл в той же папке, потом rename: Host может читать во время записи, и половина файла
     * дала бы неверный токен. В Unix замена через rename атомарна: запрос со старым токеном получит
     * отказ авторизации, а не выполнит половину операции.
     * @param file целевой файл (`root/.dsh/.bridge_token` в rootfs).
     * @param token токен для записи.
     */
    fun writeTokenFile(file: File, token: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            temp.writeText(token)
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            if (!temp.renameTo(file)) {
                file.writeText(token)
            }
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
