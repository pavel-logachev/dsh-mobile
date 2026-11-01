package dev.dshmobile.app.acceptance

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** New watch copy replaces all connection/gap status variants. */
class NotificationLanguageTest {
    @Test fun phoneTaskCopyHasRussianAndEnglishResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val ru = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) })
        assertEquals("Answer ready", en.getString(R.string.notif_answer_ready))
        assertEquals("Ответ готов", ru.getString(R.string.notif_answer_ready))
        assertEquals("Нужен ответ", ru.getString(R.string.notif_needs_decision))
        assertNotEquals(en.getString(R.string.notif_watch_body), ru.getString(R.string.notif_watch_body))
    }
}
