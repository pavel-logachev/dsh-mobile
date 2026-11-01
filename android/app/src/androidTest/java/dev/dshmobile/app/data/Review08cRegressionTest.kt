package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class Review08cRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun malformedProtectionEntryDoesNotDestroyCoreState() {
        val text = """{"selectedSessionId":"chat","drafts":{"chat":"keep"},"possiblyUsedUploads":{"good":9999999999999,"bad":"not-a-timestamp"}}"""
        val restored = decodeStoredState(text)
        assertEquals("chat", restored.selectedSessionId)
        assertEquals("keep", restored.drafts["chat"])
        assertEquals(9999999999999L, restored.possiblyUsedUploads["good"])
    }
    @Test fun protectionRestoreIsBoundedAndDropsExpiredEntries() {
        val restored = decodeStoredState(mobileJson.encodeToString(StoredState(possiblyUsedUploads =
            (1..1300).associate { "id-$it" to 9999999999999L } + ("expired" to 1L))))
        assertTrue("Restored protection size=${restored.possiblyUsedUploads.size}", restored.possiblyUsedUploads.size <= 1280)
        assertFalse(restored.possiblyUsedUploads.containsKey("expired"))
    }

}
