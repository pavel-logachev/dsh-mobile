package dev.dshmobile.app.ui.home

import dev.dshmobile.app.ui.MobileTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.data.HostApi
import dev.dshmobile.app.data.HostEndpoint
import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic host HTTP -> real presentation. Opt-in screenshot stand, never a physical device. */
class ActiveChatsStandTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun capture(name: String) {
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "active-chats").apply { mkdirs() }
        instrumentation.waitForIdleSync()
        Thread.sleep(600) // UiAutomation needs a committed Surface frame, not just Compose semantics.
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Composable private fun RussianTheme(content: @Composable () -> Unit) {
        val original = androidx.compose.ui.platform.LocalContext.current
        val configuration = android.content.res.Configuration(androidx.compose.ui.platform.LocalConfiguration.current).apply { setLocale(java.util.Locale.forLanguageTag("ru")) }
        val context = original.createConfigurationContext(configuration)
        CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides configuration,
            androidx.compose.ui.platform.LocalContext provides context) { MobileTheme(content) }
    }
    @Test fun fixtureListCapture() = runBlocking {
        val config = JSONObject(File(instrumentation.targetContext.cacheDir, "active-fixture.json").readText())
        val api = HostApi(HostEndpoint(config.getString("baseUrl")), config.getString("deviceToken"))
        val index = try { api.sessions() } finally { api.close() }
        assertTrue(index.items.any { it.running })
        val workspaces = index.items.map { it.workspaceId }.distinct().map { Workspace(it, "Демо-проект", true) }
        compose.setContent { RussianTheme {
            ChatListScreen(MobileState(connection = ConnectionState.ONLINE, sessions = index.items, workspaces = workspaces, demo = true),
                "", {}, null, {}, {}, {}, {}, {}, {}, false, {})
        } }
        compose.onNodeWithTag("session_${index.items.first().id}").assertIsDisplayed()
        compose.waitForIdle()
        val name = InstrumentationRegistry.getArguments().getString("captureName") ?: "fixture"
        if (name.startsWith("after")) compose.onNodeWithText("Сейчас работают").assertIsDisplayed()
        capture(name)
    }
    @Test fun searchKeepsDateOrderOnRenderedScreen() {
        val now = System.currentTimeMillis()
        var query by mutableStateOf("Проверка")
        val rows = listOf(SessionSummary("new-idle", "Проверка новая", "w", now, false, true),
            SessionSummary("old-active", "Проверка старая", "w", now - 30L * 86400000, true, true))
        compose.setContent { RussianTheme {
            ChatListScreen(MobileState(connection = ConnectionState.ONLINE, sessions = rows), query, { query = it }, null, {}, {}, {}, {}, {}, {}, false, {})
        } }
        fun top(id: String) = compose.onNodeWithTag("session_$id").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("new-idle") < top("old-active"))
        compose.onNodeWithText("Сейчас работают").assertDoesNotExist()
        capture("review-search-date-order")
        compose.runOnIdle { query = "" }
        assertTrue(top("old-active") < top("new-idle"))
        compose.onNodeWithText("Сейчас работают").assertIsDisplayed()
        capture("review-normal-active-first")
    }
    @Test fun finishingOffscreenActiveKeepsVisibleAnchor() {
        val now = System.currentTimeMillis()
        var rows by mutableStateOf(listOf(SessionSummary("active", "Активный чат", "w", now - 86400000 * 30L, true, true)) +
            (1..45).map { SessionSummary("idle-$it", "Неактивный чат $it", "w", now - it * 1000L, false, true) })
        compose.setContent { RussianTheme {
            ChatListScreen(MobileState(connection = ConnectionState.ONLINE, sessions = rows), "", {}, null, {}, {}, {}, {}, {}, {}, false, {})
        } }
        compose.onNodeWithTag("chat_list").performScrollToNode(hasTestTag("session_idle-15"))
        compose.waitForIdle()
        fun firstVisible(): Pair<String, Float> {
            val viewport = compose.onNodeWithTag("chat_list").fetchSemanticsNode().boundsInRoot
            val node = compose.onAllNodes(hasTestTag("session_idle-15") or hasTestTag("session_idle-14") or hasTestTag("session_idle-16"))
                .fetchSemanticsNodes().filter { it.boundsInRoot.bottom > viewport.top }.minBy { it.boundsInRoot.top }
            return node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] to node.boundsInRoot.top
        }
        val before = firstVisible()
        capture("anchor-before")
        compose.runOnIdle { rows = rows.map { if (it.id == "active") it.copy(running = false) else it } }
        compose.waitForIdle()
        val after = firstVisible()
        capture("anchor-after")
        assertEquals(before.first, after.first)
        assertEquals(before.second, after.second, 1f)
        File(instrumentation.targetContext.getExternalFilesDir(null), "active-chats/anchor.txt").writeText("before=$before\nafter=$after\n")
    }
}
