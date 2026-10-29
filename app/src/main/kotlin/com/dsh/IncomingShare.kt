package com.dsh

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Что пришло в DSH снаружи: «Поделиться → DSH» из другого приложения (текст, ссылка, картинки, файлы)
 * или ярлык на иконке. Хранится, пока главный экран его не заберёт (после запуска Host и входа).
 */
data class Incoming(val target: Target, val text: String? = null, val files: List<File> = emptyList(), val skipped: List<String> = emptyList()) {
    enum class Target { NEW_CHAT, TERMINAL, FILES }
}

object IncomingShare {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Incoming?>(null)

    /** Действия ярлыков на иконке (ShortcutManager). */
    const val ACTION_NEW_CHAT = "app.dsh.action.NEW_CHAT"
    const val ACTION_TERMINAL = "app.dsh.action.TERMINAL"
    const val ACTION_FILES = "app.dsh.action.FILES"

    /** Больше — не копируем (телефон не должен забиться одним «поделиться»). */
    const val MAX_FILE_BYTES = 100L shl 20
    private const val MAX_FILES = 20

    /** Ярлык из [intent] → в ожидание; true — это был ярлык. */
    fun fromShortcut(intent: Intent?): Boolean {
        val target = when (intent?.action) {
            ACTION_NEW_CHAT -> Incoming.Target.NEW_CHAT
            ACTION_TERMINAL -> Incoming.Target.TERMINAL
            ACTION_FILES -> Incoming.Target.FILES
            else -> return false
        }
        pending.value = Incoming(target)
        return true
    }

    /** Папка, куда «Поделиться» копирует файлы до переноса в проект. */
    fun inbox(context: Context): File = File(context.cacheDir, "incoming")

    /**
     * Разобрать ACTION_SEND / SEND_MULTIPLE: текст (тема + текст), файлы — копией в [inbox] с исходными именами,
     * пока у DSH есть право их читать. Возвращает null, если в намерении ничего полезного нет.
     */
    fun fromSend(context: Context, intent: Intent): Incoming? {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val text = sharedText(subject, body)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamOf(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamsOf(intent)
            else -> emptyList()
        }
        val dir = inbox(context).apply { mkdirs() }
        val files = ArrayList<File>()
        val skipped = ArrayList<String>()
        for (uri in uris.take(MAX_FILES)) {
            val (name, size) = AttachmentImporter.meta(context, uri) ?: ("file-${System.currentTimeMillis()}" to 0L)
            if (size > MAX_FILE_BYTES) { skipped += name; continue }
            val target = uniqueIn(dir, safeAttachmentName(name))
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        // Размер мог быть неизвестен заранее — предел проверяем по ходу копирования.
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FILE_BYTES) error("too big")
                            out.write(buf, 0, n)
                        }
                    }
                } ?: error("no stream")
            }.isSuccess
            if (ok) files += target else { target.delete(); skipped += name }
        }
        skipped += uris.drop(MAX_FILES).map { it.lastPathSegment ?: "file" }
        if (text == null && files.isEmpty() && skipped.isEmpty()) return null
        return Incoming(Incoming.Target.NEW_CHAT, text, files, skipped)
    }

    /** Тема и текст без повторов: ссылка из браузера часто приходит и темой (заголовок), и текстом. */
    internal fun sharedText(subject: String, body: String): String? = when {
        body.isEmpty() && subject.isEmpty() -> null
        body.isEmpty() -> subject
        subject.isEmpty() || body.contains(subject) -> body
        else -> "$subject\n$body"
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    private fun uniqueIn(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        while (f.exists()) { f = File(dir, "$base-$i$ext"); i++ }
        return f
    }

    /** Ярлыки на иконке: новая сессия, терминал, файлы (динамические — работают и в debug-сборке). */
    fun publishShortcuts(context: Context) = runCatching {
        fun shortcut(id: String, action: String, label: Int, icon: Int) =
            androidx.core.content.pm.ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(context.getString(label))
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(context, icon))
                .setIntent(Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                .build()
        androidx.core.content.pm.ShortcutManagerCompat.setDynamicShortcuts(
            context,
            listOf(
                shortcut("new", ACTION_NEW_CHAT, R.string.shortcut_new_chat, R.drawable.ic_shortcut_new),
                shortcut("terminal", ACTION_TERMINAL, R.string.shortcut_terminal, R.drawable.ic_shortcut_terminal),
                shortcut("files", ACTION_FILES, R.string.shortcut_files, R.drawable.ic_shortcut_files),
            ),
        )
    }
}

/**
 * Приём «Поделиться → DSH»: без интерфейса, копирует присланное (право читать есть только сейчас),
 * кладёт в [IncomingShare.pending] и открывает главный экран — новая сессия с вложениями и текстом в поле.
 */
class ShareActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        Thread {
            val incoming = runCatching { IncomingShare.fromSend(applicationContext, intent) }.getOrNull()
            runOnUiThread {
                if (incoming != null) IncomingShare.pending.value = incoming
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }.start()
    }
}
