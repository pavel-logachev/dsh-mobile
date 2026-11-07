package dev.dshmobile.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w420dp-h900dp")
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ChatListBackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `search keeps date order while normal project view puts active first`() {
        val now = System.currentTimeMillis()
        val sessions = listOf(SessionSummary("new-idle", "Match newer", "w", now, false, true),
            SessionSummary("old-active", "Match older", "w", now - 30L * 86400000, true, true))
        var query by mutableStateOf("Match")
        compose.setContent { MaterialTheme {
            ChatListScreen(MobileState(sessions = sessions), query, { query = it }, "w", {}, {}, {}, {}, {}, {}, false, {})
        } }
        fun top(id: String) = compose.onNodeWithTag("session_$id").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Search must preserve pre-fix date order", top("new-idle") < top("old-active"))
        compose.runOnIdle { query = "" }
        assertTrue("Normal project view remains active first", top("old-active") < top("new-idle"))
    }

    @Test fun `back closes search clears query and preserves list anchor`() {
        var query by mutableStateOf("Synthetic")
        lateinit var activity: ComponentActivity
        val sessions = (1..50).map { SessionSummary("s$it", "Synthetic $it", "w", it.toLong(), false, true) }
        compose.setContent {
            activity = requireNotNull(LocalActivity.current) as ComponentActivity
            MaterialTheme {
                ChatListScreen(MobileState(sessions = sessions), query, { query = it }, null, {}, {}, {}, {}, {}, {}, false, {})
            }
        }
        compose.onNodeWithTag("home_search_toggle").assertIsOn()
        compose.onNodeWithTag("chat_list").performScrollToIndex(12)
        val listBefore = compose.onNodeWithTag("chat_list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home_search_toggle").assertIsOff()
        compose.runOnIdle { assertEquals("", query); assertFalse(activity.isFinishing) }
        val listAfter = compose.onNodeWithTag("chat_list").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(listBefore, listAfter, 0.01f)
    }
}
