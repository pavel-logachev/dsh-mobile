package dev.dshmobile.app.acceptance

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real MainActivity -> production repository factory -> isolated local v1 fixture host. */
@RunWith(AndroidJUnit4::class)
class MobileAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var checkpoint = "configuration"
    private lateinit var mode: AcceptanceMode
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val timeout = 45_000L

    private var previousTheme: String? = null
    private var themeCaptured = false
    @After fun closeActivity() {
        scenario?.close(); scenario = null
        if (themeCaptured) instrumentation.targetContext.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE).edit().apply {
            if (previousTheme == null) remove("theme") else putString("theme", previousTheme)
        }.commit()
    }

    @Test fun fixtureJourney() {
        // Never put an invitation in instrumentation arguments: runners print arguments/errors.
        // This catch deliberately excludes causes/semantics trees, including pairing input text.
        try {
            // Existing matrix follows system night mode; the shipping default remains DARK.
            val appContext = instrumentation.targetContext
            val originalTheme = appContext.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE).getString("theme", null)
            val config = AcceptanceConfig.read()
            if (InstrumentationRegistry.getArguments().getString("acceptanceStage") != "redesignCapture") {
                appContext.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE).edit().putString("theme", "SYSTEM").commit()
            }
            previousTheme = originalTheme
            themeCaptured = true
            mode = config.mode
            val args = InstrumentationRegistry.getArguments()
            val stage = args.getString("acceptanceStage") ?: "pairLifecycle"
            checkpoint = "launch"
            scenario = ActivityScenario.launch(MainActivity::class.java)
            when (stage) {
                "pairLifecycle" -> pairAndExerciseLifecycle(config)
                "pairOnly" -> {
                    require(config.mode == AcceptanceMode.FIXTURE)
                    pairOnly(config)
                }
                "restored" -> {
                    awaitOnline()
                    openCreatedChat(config)
                    assertCanonicalHistory(config)
                    assertDraft()
                    capture("chat-light")
                }
                "offline" -> {
                    checkpoint = "offline-state"
                    awaitState("OFFLINE")
                    compose.onNodeWithTag("reconnect").assertIsDisplayed()
                    // Cold offline restore persists credentials/draft/selection, not transcripts.
                    // No snapshot means no composer; a retained snapshot must disable send.
                    if (nodes(hasTestTag("send_message")) > 0) {
                        compose.onNodeWithTag("send_message").assertIsNotEnabled()
                    }
                    await(hasTestTag("home_screen"))
                    checkpoint = "offline-no-create-controls"
                    assertAllNewChatControlsDisabled()
                    compose.waitForIdle()
                    capture("chat-offline")
                }
                "recovered" -> {
                    checkpoint = "reconnect"
                    if (nodes(hasTestTag("reconnect")) > 0) {
                        compose.onNodeWithTag("reconnect").performClick()
                    }
                    awaitOnline()
                    openCreatedChat(config)
                    assertCanonicalHistory(config)
                    assertDraft()
                    compose.onNodeWithTag("send_message").assertIsEnabled()
                    capture("chat-recovered")
                }
                "capture" -> {
                    awaitOnline()
                    openCreatedChat(config)
                    assertCanonicalHistory(config)
                    assertDraft()
                    val name = args.getString("acceptanceCapture") ?: error("capture name required")
                    require(name in setOf("chat-dark", "chat-font130", "chat-light-en", "chat-light-ru"))
                    verifyCaptureConfiguration(name)
                    capture(name)
                }
                "redesignCapture" -> captureRedesign(config, args.getString("acceptanceCapture") ?: error("capture name required"))
                else -> error("unsupported acceptance stage")
            }
            checkpoint = "stage-receipt"
            writeReceipt(stage)
        } catch (_: Throwable) {
            // An AssertionError with the original cause can leak a full semantics tree to adb.
            throw AssertionError("Mobile fixture acceptance failed at checkpoint: $checkpoint (details redacted)")
        }
    }

    /** Fresh synthetic pairing only; private config is consumed/deleted by AcceptanceConfig.read(). */
    private fun pairOnly(config: AcceptanceConfig) {
        checkpoint = "pair-only-empty-onboarding"
        await(hasTestTag("pairing_import"))
        compose.onNodeWithTag("pairing_invitation").assertDoesNotExist()
        compose.onNodeWithTag("pairing_preview").assertIsNotEnabled()
        compose.onNodeWithTag("pairing_manual").performScrollTo().performClick()
        await(hasTestTag("pairing_invitation"))
        checkpoint = "pair-only-preview"
        compose.onNodeWithTag("pairing_invitation").performTextReplacement(config.invitation ?: error("fresh invitation required"))
        compose.onNodeWithTag("pairing_device_name").performTextReplacement("Disposable Android capture")
        compose.onNodeWithTag("pairing_preview").performScrollTo().performClick()
        await(hasTestTag("pairing_connect"))
        compose.onNodeWithTag("pairing_connect").assertIsEnabled().performClick()
        awaitOnline()
        await(hasTestTag("home_screen"))
        compose.onNodeWithTag("pairing_invitation").assertDoesNotExist()
        compose.onNodeWithTag("demo_label").assertIsDisplayed()
    }

    private fun pairAndExerciseLifecycle(config: AcceptanceConfig) {
        checkpoint = "empty-onboarding"
        await(hasTestTag("pairing_import"))
        compose.onNodeWithTag("pairing_import").assertIsEnabled()
        compose.onNodeWithTag("pairing_invitation").assertDoesNotExist()
        compose.onNodeWithTag("pairing_preview").assertIsNotEnabled()
        capture("onboarding-empty") // Import-first screen, before any secret is entered.
        compose.onNodeWithTag("pairing_manual").performScrollTo().performClick()
        await(hasTestTag("pairing_invitation"))
        compose.onNodeWithTag("pairing_invitation").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
        )
        compose.onNodeWithTag("pairing_preview").assertIsNotEnabled()
        checkpoint = "pairing"
        compose.onNodeWithTag("pairing_invitation").performTextReplacement(config.invitation ?: error("fresh invitation required"))
        compose.onNodeWithTag("pairing_device_name").performTextReplacement("Disposable Android acceptance")
        compose.onNodeWithTag("pairing_preview").performScrollTo().performClick()
        await(hasTestTag("pairing_connect"))
        compose.onNodeWithTag("pairing_connect").assertIsEnabled().performClick()
        awaitOnline()
        compose.onNodeWithTag("pairing_invitation").assertDoesNotExist()

        checkpoint = "fixture-conversation"
        await(hasTestTag("home_screen"))
        val sessionTag = "session_${config.existingSessionId}"
        compose.onNodeWithTag("chat_list").performScrollToNode(hasTestTag(sessionTag))
        compose.onNodeWithTag(sessionTag).performClick()
        await(hasText(config.existingMessage))
        compose.onNodeWithText(config.existingMessage).assertIsDisplayed()

        checkpoint = "create-in-fixture-workspace"
        compose.onNodeWithTag("chat_drawer").performClick()
        compose.onNodeWithTag("new_chat").performClick()
        await(hasTestTag("workspace_picker"))
        compose.onNodeWithTag("workspace_picker").performScrollToNode(hasTestTag("workspace_${config.workspaceId}"))
        compose.onNodeWithTag("workspace_${config.workspaceId}").performClick()
        compose.onNodeWithTag("create_chat").assertIsEnabled().performClick()
        checkpoint = "created-active-title"
        assertExactlyOneDisplayedTitle(config.createdSessionTitle)
        await(hasTestTag("message_input"))

        checkpoint = "send-once-and-authoritative-output"
        compose.onNodeWithTag("message_input").performTextReplacement(config.mode.prompt)
        await(hasTestTag("send_message") and isEnabled())
        compose.onNodeWithTag("send_message").performClick() // Exactly one explicit UI send.
        await(hasText(config.expectedAssistantText, substring = true))
        assertCanonicalHistory(config)
        // Unsent draft is intentionally different from the submitted prompt.
        compose.onNodeWithTag("message_input").performTextReplacement(DRAFT)
        assertDraft()
        compose.waitForIdle()
        capture("chat-initial")

        checkpoint = "activity-recreate"
        scenario!!.recreate()
        awaitOnline()
        assertCanonicalHistory(config)
        assertDraft()
        checkpoint = "activity-close-and-reopen"
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitOnline()
        openCreatedChat(config)
        assertCanonicalHistory(config)
        assertDraft()
        capture("chat-reopened")
    }

    private fun openCreatedChat(config: AcceptanceConfig) {
        if (nodes(hasTestTag("home_screen")) == 0) return
        checkpoint = "open-restored-chat"
        val title = hasText(config.createdSessionTitle)
        compose.onNodeWithTag("chat_list").performScrollToNode(title)
        compose.onNode(title).performClick()
        await(hasTestTag("message_input"))
    }

    /** Opt-in fixture captures; running-chat capture also explicitly sends one synthetic prompt. */
    private fun captureRedesign(config: AcceptanceConfig, name: String) {
        checkpoint = "redesign-capture-guard"
        require(config.mode == AcceptanceMode.FIXTURE)
        require(name in setOf("home-dark", "home-dark-en", "home-project", "chat-markdown-running", "new-chat-sheet", "settings-dark", "pairing-dark", "home-light", "home-font130"))
        val prefs = instrumentation.targetContext.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE)
        check(prefs.edit().putString("theme", if (name == "home-light") "LIGHT" else "DARK").commit())
        scenario!!.recreate()
        if (name == "pairing-dark") {
            // Run this on an empty disposable app BEFORE fresh pairing; never clear an existing pairing.
            await(hasTestTag("pairing_import"))
            compose.onNodeWithTag("pairing_invitation").assertDoesNotExist()
        } else {
            awaitOnline()
            checkpoint = "redesign-home"
            await(hasTestTag("home_screen"))
            compose.onNodeWithTag("demo_label").assertIsDisplayed() // Actual host also confirms synthetic provenance.
            // Index 0 is All: reaching index 2 proves at least two real project chips,
            // without assuming LazyRow has materialized both in the initial viewport.
            compose.onNodeWithTag("project_filters").performScrollToIndex(2).performScrollToIndex(0)
            when (name) {
                "home-project" -> {
                    compose.onNodeWithTag("project_filters").performScrollToNode(hasTestTag("project_filter_${config.filterWorkspaceId}"))
                    compose.onNodeWithTag("project_filter_${config.filterWorkspaceId}").performClick().assertIsSelected()
                }
                "chat-markdown-running" -> {
                    val tag = "session_${config.markdownSessionId}"
                    checkpoint = "redesign-markdown-session"
                    compose.onNodeWithTag("chat_list").performScrollToNode(hasTestTag(tag))
                    compose.onNodeWithTag(tag).performClick()
                    checkpoint = "redesign-markdown-timeline"
                    await(hasTestTag("chat_timeline"))
                    checkpoint = "redesign-markdown-anchor-scroll"
                    // A Markdown message is one oversized lazy item. ScrollToNode cannot
                    // position an internal heading after bottom-following scrolled within it.
                    // The fixture contract is user item 0, assistant Markdown item 1.
                    compose.onNodeWithTag("chat_timeline").performScrollToIndex(1)
                    checkpoint = "redesign-markdown-anchor-visible"
                    compose.onNodeWithText(config.markdownAnchor, substring = true).assertIsDisplayed()
                    checkpoint = "redesign-markdown-running-controls"
                    compose.onNodeWithTag("activity_row").assertIsDisplayed()
                    compose.onNodeWithTag("stop_run").assertIsDisplayed()
                    compose.onNodeWithTag("send_message").assertIsDisplayed().assertIsNotEnabled()
                    checkpoint = "redesign-send-while-running"
                    compose.onNodeWithTag("message_input").performTextReplacement(RUNNING_PROMPT)
                    compose.onNodeWithTag("send_message").assertIsEnabled().performClick()
                    await(hasTestTag("message_input") and
                        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                    compose.onNodeWithTag("send_message").assertIsDisplayed()
                    // Send must not open the Stop confirmation; the turn may finish meanwhile.
                    compose.onNodeWithTag("stop_run_confirm").assertDoesNotExist()
                }
                "new-chat-sheet" -> {
                    compose.onNodeWithTag("new_chat").performClick()
                    await(hasTestTag("workspace_picker"))
                    compose.onNodeWithTag("create_chat").assertIsDisplayed()
                }
                "settings-dark" -> {
                    compose.onNodeWithTag("settings").performClick()
                    await(hasTestTag("settings_screen"))
                }
            }
        }
        var valid = false
        scenario!!.onActivity { activity ->
            val scale = activity.resources.configuration.fontScale
            valid = if (name == "home-font130") kotlin.math.abs(scale - 1.3f) < 0.02f else kotlin.math.abs(scale - 1f) < 0.02f
        }
        check(valid)
        capture(name)
    }

    private fun assertCanonicalHistory(config: AcceptanceConfig) {
        checkpoint = "canonical-history"
        await(hasTestTag("chat_timeline"))
        val timeline = compose.onNodeWithTag("chat_timeline")
        timeline.performScrollToNode(hasText(config.mode.prompt))
        compose.onAllNodesWithText(config.mode.prompt).assertCountEquals(1)
        compose.onNodeWithText(config.mode.prompt).assertIsDisplayed()
        timeline.performScrollToNode(hasText(config.expectedAssistantText, substring = true))
        compose.onAllNodesWithText(config.expectedAssistantText, substring = true).assertCountEquals(1)
        compose.onNodeWithText(config.expectedAssistantText, substring = true).assertIsDisplayed()
        assertExactlyOneDisplayedTitle(config.createdSessionTitle)
        // These are visible canonical UI occurrences, not a claim about full upstream history.
    }

    private fun assertAllNewChatControlsDisabled() {
        // Home FAB and empty-state CTA share this tag; every matching control must be disabled.
        val matcher = hasTestTag("new_chat")
        val count = nodes(matcher)
        check(count > 0)
        for (index in 0 until count) compose.onAllNodes(matcher)[index].assertIsNotEnabled()
    }

    private fun assertExactlyOneDisplayedTitle(title: String) {
        // Prove one visible active title; Home no longer keeps offscreen drawer copies.
        val matcher = hasText(title)
        fun displayedIndices(): List<Int> = (0 until nodes(matcher)).filter {
            compose.onAllNodes(matcher)[it].isDisplayed()
        }
        compose.waitUntil(timeoutMillis = timeout) { displayedIndices().size == 1 }
        compose.waitForIdle()
        val displayed = displayedIndices()
        check(displayed.size == 1)
        compose.onAllNodes(matcher)[displayed.single()].assertIsDisplayed().assertTextEquals(title)
    }

    private fun assertDraft() {
        checkpoint = "durable-unsent-draft"
        await(hasTestTag("message_input") and
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(DRAFT)))
        compose.onNodeWithTag("message_input").assertTextContains(DRAFT)
    }

    private fun nodes(matcher: SemanticsMatcher): Int =
        compose.onAllNodes(matcher).fetchSemanticsNodes().size

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = timeout) { nodes(matcher) == 1 }
        compose.waitForIdle()
    }

    private fun awaitOnline() {
        checkpoint = "online-after-authenticated-sync"
        awaitState("ONLINE")
    }

    private fun awaitState(value: String) = await(
        hasTestTag("connection_state") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    )

    private fun verifyCaptureConfiguration(name: String) {
        checkpoint = "capture-configuration"
        var valid = false
        scenario!!.onActivity { activity ->
            val config = activity.resources.configuration
            val night = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            valid = when (name) {
                "chat-dark" -> night == Configuration.UI_MODE_NIGHT_YES
                "chat-font130" -> kotlin.math.abs(config.fontScale - 1.3f) < 0.02f && night == Configuration.UI_MODE_NIGHT_NO
                "chat-light-en" -> config.locales[0].language == "en" && night == Configuration.UI_MODE_NIGHT_NO
                "chat-light-ru" -> config.locales[0].language == "ru" && night == Configuration.UI_MODE_NIGHT_NO
                else -> false
            }
        }
        check(valid)
    }

    private fun capture(name: String) {
        checkpoint = "native-capture-$name"
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Native compositor capture, not a Compose bitmap, browser screenshot or synthetic render.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("native screenshot unavailable")
        try {
            File(outputDirectory(), "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val metadata = JSONObject().put("source", "android.app.UiAutomation.takeScreenshot")
                .put("width", bitmap.width).put("height", bitmap.height)
            labelEvidence(metadata)
            scenario!!.onActivity { activity ->
                metadata.put("fontScale", activity.resources.configuration.fontScale.toDouble())
                    .put("uiMode", activity.resources.configuration.uiMode)
                    .put("locale", activity.resources.configuration.locales[0].toLanguageTag())
                    .put("themePreference", activity.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE).getString("theme", "DARK"))
            }
            File(outputDirectory(), "$name.json").writeText(metadata.toString())
        } finally { bitmap.recycle() }
    }

    private fun labelEvidence(json: JSONObject): JSONObject = json
        .put("mode", mode.wireName).put("fixtureOnly", mode == AcceptanceMode.FIXTURE)
        .put("isolatedDshCanary", mode == AcceptanceMode.ISOLATED_DSH_CANARY)
        .put("productionCanary", false).put("externalModel", false)

    private fun writeReceipt(stage: String) {
        File(outputDirectory(), "stage-$stage.json").writeText(
            labelEvidence(JSONObject()).put("stage", stage).put("passed", true)
                .put("activity", "dev.dshmobile.app.MainActivity").put("mockRepository", false).toString()
        )
    }

    private fun outputDirectory(): File {
        val root = instrumentation.targetContext.getExternalFilesDir(null) ?: error("external app files unavailable")
        return File(root, "acceptance").apply { check(isDirectory || mkdirs()) }
    }

    companion object {
        const val PROMPT = "DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1"
        const val DRAFT = "DSH_MOBILE_UNSENT_DRAFT_V1"
        const val RUNNING_PROMPT = "DSH_MOBILE_QUEUED_PROMPT_V1"
    }
}

private enum class AcceptanceMode(val wireName: String, val prompt: String) {
    FIXTURE("fixture", MobileAcceptanceTest.PROMPT),
    ISOLATED_DSH_CANARY("isolated-dsh-canary", "DSH_MOBILE_CANARY_ANDROID: synthetic emulator prompt.");

    companion object {
        fun parse(value: String): AcceptanceMode = entries.single { it.wireName == value }
    }
}

/** No toString(): the one-use invitation is never printable through this object. */
private class AcceptanceConfig(
    val mode: AcceptanceMode, val invitation: String?, val workspaceId: String, val existingSessionId: String,
    val existingMessage: String, val createdSessionTitle: String, val expectedAssistantText: String,
    val markdownSessionId: String, val markdownAnchor: String, val filterWorkspaceId: String
) {
    companion object {
        fun read(): AcceptanceConfig {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.cacheDir, "acceptance-fixture.json")
            require(file.isFile && file.length() in 1..65_536)
            val raw = try { file.readText() } finally { check(file.delete()) }
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            val mode = AcceptanceMode.parse(json.getString("mode"))
            if (json.has("prompt")) require(json.getString("prompt") == mode.prompt)
            if (mode == AcceptanceMode.ISOLATED_DSH_CANARY) {
                require(json.getString("workspaceId") == "canary")
                require(json.getString("expectedAssistantText") == "CANARY_SERVE_OK — deterministic real DSH; no external model.")
            }
            val invitationObject = json.optJSONObject("invitation")
            val fixture = json.optJSONObject("demoFixture") ?: invitationObject?.optJSONObject("demoFixture")
            // Capture metadata is not part of the production invitation schema. Never loosen it.
            invitationObject?.remove("demoFixture")
            val invitation = invitationObject?.also {
                val uri = java.net.URI(it.getString("baseUrl"))
                require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
                require(uri.userInfo == null && uri.query == null && uri.fragment == null)
                require(uri.path.isNullOrEmpty() || uri.path == "/")
                require(it.getString("pairingToken").isNotBlank())
            }?.toString()
            fun text(key: String): String = json.getString(key).also { require(it.isNotBlank() && it.length <= 4096) }
            fun captureText(key: String, fallback: String): String = (json.optString(key).takeIf { it.isNotBlank() }
                ?: fixture?.optString(key)?.takeIf { it.isNotBlank() } ?: fallback).also { require(it.length <= 4096) }
            return AcceptanceConfig(mode, invitation, text("workspaceId"), text("existingSessionId"),
                text("existingMessage"), text("createdSessionTitle"), text("expectedAssistantText"),
                captureText("markdownSessionId", "demo-fund-plan"), captureText("markdownAnchor", "План портала фонда"), captureText("filterWorkspaceId", "demo-fund"))
        }
    }
}
