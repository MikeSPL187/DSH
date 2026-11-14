package com.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dsh.core.designsystem.DshTheme
import com.dsh.core.model.ChatMessage
import com.dsh.core.model.MessageRole
import com.dsh.feature.chat.ModernChatScreen
import com.dsh.feature.files.FilesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Synthetic keyless workloads. This Activity exists only in the isolated benchmark APK. */
class PerformanceFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "chat"
        val count = intent.getIntExtra("count", 1000).coerceIn(100, 10_000)
        setContent {
            DshTheme {
                if (scenario == "files") {
                    val root = remember { File(cacheDir, "benchmark-files-$count") }
                    var ready by remember { mutableStateOf(false) }
                    LaunchedEffect(root) {
                        withContext(Dispatchers.IO) {
                            root.mkdirs()
                            repeat(count) { File(root, "folder-%05d".format(java.util.Locale.ROOT, it)).mkdir() }
                        }
                        ready = true
                    }
                    if (ready) FilesScreen(root = root, rootLabel = "Benchmark", refreshKey = 0, git = null,
                        onClose = { finish() }, onAttach = {}, onAsk = {}, onReview = {},
                        commitMessage = null, onShare = {}, onImport = {}, onToast = {})
                } else {
                    val messages = remember {
                        List(400) { index ->
                            ChatMessage("fixture", "fixture", "m$index",
                                if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                                if (index % 2 == 0) "Question $index" else
                                    "answer-$index\n\n" + "A paragraph of synthetic text for scrolling. ".repeat(12) +
                                        "\n\n```python\nprint('benchmark')\n```", seq = index.toLong(), createdAt = index.toLong())
                        }
                    }
                    var stream by remember { mutableStateOf("") }
                    LaunchedEffect(scenario) {
                        if (scenario == "stream") repeat(2000) {
                            delay(16)
                            stream += " streaming-$it"
                        }
                    }
                    ModernChatScreen(messages = messages, onSendMessage = {}, streamingText = stream,
                        isThinking = scenario == "stream")
                }
            }
        }
    }
}
