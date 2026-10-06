package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileTheme
import dev.dshmobile.app.ui.TrustPreview
import dev.dshmobile.app.ui.home.ChatListScreen
import dev.dshmobile.app.ui.pairing.ScannerSurface
import dev.dshmobile.app.ui.pairing.TrustReviewContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Pure UI fixture: no host, credentials, repository or installed user profile. */
class UiPolishTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = InstrumentationRegistry.getArguments().getString("polishCapture") ?: return
        compose.waitForIdle()
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-polish").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { image ->
            File(dir, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun messageMenuAndTalkBackExposeSameActionsAndSelection() {
        val copies = mutableListOf<String>()
        compose.setContent { MobileTheme { MessageItem(ChatMessage("m", "assistant", "**Hello**", 1), { copies += it }) } }
        compose.onNodeWithTag("message:m").performTouchInput { longClick() }
        capture("message-menu")
        compose.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.mobile_copy_as_text)).performClick()
        assertEquals(listOf("Hello"), copies)
        val actions = compose.onNodeWithTag("message:m").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(listOf(R.string.mobile_copy_message, R.string.mobile_copy_as_text, R.string.mobile_select_message).map { InstrumentationRegistry.getInstrumentation().targetContext.getString(it) }, actions.map { it.label })
            assertTrue(actions.last().action())
        }
        compose.onNodeWithTag("select_message_text").performScrollTo().assertIsDisplayed()
        capture("message-select")
    }
    @Test fun loadedSearchMovesToMatchIncludingCollapsedServiceText() {
        val messages = (0..30).map { ChatMessage("m$it", if (it == 15) "system" else "assistant", "Loaded match $it", it.toLong()) }
        val session = SessionSummary("s", "Synthetic", "w", 1, false, true)
        val selected = mutableStateOf(SearchMatch("m15", 7, 12))
        compose.setContent { MobileTheme { ChatTimeline(SessionSnapshot(session, messages, 1, true, "idle"), null, {},
            searchQuery = "match", selectedMatch = selected.value) } }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("search_text:m15", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search_text:m15", useUnmergedTree = true).assertIsDisplayed()
        capture("search-match")
        compose.runOnIdle { selected.value = SearchMatch("m0", 7, 12) }
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag("search_text:m0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search_text:m0", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun listScannerAndTrustRemainReachable() {
        var screen by mutableIntStateOf(0)
        val state = MobileState(connection = ConnectionState.ONLINE, capabilities = MobileCapabilities(true, true, true, false, false, false, false, false),
            workspaces = listOf(Workspace("w", "Synthetic project", true)),
            sessions = (0..20).map { SessionSummary("s$it", "Synthetic chat $it", "w", 100L - it, false, true) })
        compose.setContent { MobileTheme { when (screen) {
            0 -> ChatListScreen(state, "", {}, null, {}, {}, {}, {}, {}, {}, false, {})
            1 -> ScannerSurface(true, true, false, {}, {}, {})
            else -> TrustReviewContent(TrustPreview("https://synthetic.invalid", "sha256/" + "A".repeat(43) + "=", true, false), false, {}, {})
        } } }
        compose.onNodeWithTag("chat_list").performScrollToIndex(22)
        compose.onNodeWithTag("session_s20").assertIsDisplayed()
        val row = compose.onNodeWithTag("session_s20").fetchSemanticsNode().boundsInRoot
        val fab = compose.onNodeWithTag("new_chat").fetchSemanticsNode().boundsInRoot
        assertTrue("Last row must clear FAB", row.bottom <= fab.top)
        capture("home-last-row")
        compose.runOnIdle { screen = 1 }
        compose.onNodeWithTag("pairing_scan_back").assertIsDisplayed()
        compose.onNodeWithTag("pairing_scan_frame").assertIsDisplayed()
        capture("scanner-frame")
        compose.onNodeWithTag("pairing_scan_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pairing_scan_torch").performScrollTo().assertIsDisplayed()
        capture("scanner-hint")
        compose.runOnIdle { screen = 2 }
        compose.onNodeWithTag("trust_back").assertIsDisplayed()
        compose.onNodeWithTag("copy_fingerprint").performScrollTo().assertIsDisplayed()
        capture("trust-fingerprint")
        compose.onNodeWithTag("copy_fingerprint").performClick()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("sha256/" + "A".repeat(43) + "=", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithTag("pairing_connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("trust_back").assertIsDisplayed()
        capture("trust-connect")
    }
}
