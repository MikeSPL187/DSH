package com.dsh.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.dsh.LockedContent
import com.dsh.core.designsystem.DshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G-07: под замком основной интерфейс не виден службам специальных возможностей — раньше блокировка была
 * только картинкой поверх, и TalkBack читал чат и нажимал «Разрешить» в одобрении агента.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedContentTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lockedUiHasNoSemanticsAndUnlockRestoresIt() {
        var locked by mutableStateOf(true)
        var allowed = 0
        compose.setContent {
            DshTheme {
                LockedContent(locked) { Text("Разрешить", Modifier.clickable { allowed++ }) }
            }
        }
        assertTrue(compose.onAllNodes(hasText("Разрешить")).fetchSemanticsNodes().isEmpty())
        locked = false
        compose.waitForIdle()
        compose.onNode(hasText("Разрешить")).performClick()
        assertEquals(1, allowed)
    }
}
