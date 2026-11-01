package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ru-rRU-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TurnFailureLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun `AND02 long error keeps transcript and composer visible and full text accessible`() {
        val error = "Synthetic upstream error ".repeat(70)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize()) {
            TurnFailureNotice(error)
            Text("Synthetic message in transcript", Modifier.weight(1f).testTag("transcript"))
            Text("Composer", Modifier.testTag("composer"))
        } } }
        compose.onNodeWithTag("transcript").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        val box = compose.onNodeWithTag("turn_failure").fetchSemanticsNode().boundsInRoot
        assertTrue("error consumed the chat: $box", box.height < 200)
        val output = System.getenv("DSH_ANDROID_RENDER_DIR")
        if (output != null) compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(image))
            File(output, "turn-failure-fixed.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("turn_failure").performClick()
        compose.onNodeWithTag("turn_failure_detail").assertTextContains(error, substring = true).assertIsDisplayed()
    }
    @Test fun `AND02 empty short maximum errors stay bounded at large font scales`() {
        var message by androidx.compose.runtime.mutableStateOf("")
        var scale by androidx.compose.runtime.mutableFloatStateOf(1.5f)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
            Column(Modifier.fillMaxSize()) { TurnFailureNotice(message); Text("History", Modifier.weight(1f).testTag("history")); Text("Composer", Modifier.testTag("composer")) }
        } } }
        for (font in listOf(1.5f, 2f)) for (text in listOf("", "Short failure", "E".repeat(2048), "Ошибка 😀 ".repeat(80))) {
            compose.runOnIdle { message = text; scale = font }
            compose.onNodeWithTag("history").assertIsDisplayed(); compose.onNodeWithTag("composer").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("turn_failure").fetchSemanticsNode().boundsInRoot.height < 200)
        }
    }
}
