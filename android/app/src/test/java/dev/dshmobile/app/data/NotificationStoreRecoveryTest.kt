package dev.dshmobile.app.data

import java.io.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationStoreRecoveryTest {
    @Test fun encryptedWatchRecordSurvivesOwnerRecreationAndResync() = runBlocking {
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        var bytes: ByteArray? = null
        val record = object : AtomicStateFile {
            override val identity = "synthetic/watch-restore.enc"
            override fun openRead() = ByteArrayInputStream(bytes ?: throw FileNotFoundException())
            override fun startWrite() = ByteArrayOutputStream()
            override fun finishWrite(stream: OutputStream) { bytes = (stream as ByteArrayOutputStream).toByteArray() }
            override fun failWrite(stream: OutputStream?) = Unit
            override fun delete() { bytes = null }
        }
        val request = "00000000-0000-4000-8000-000000000001"
        val first = NotificationStore(record, StateCipher { key })
        first.update { it.registerWatch("device", "chat", request, 100) }
        assertFalse(bytes!!.toString(Charsets.UTF_8).contains(request))
        val recreated = NotificationStore(record, StateCipher { key })
        assertTrue(recreated.read().needsWatchService(101))
        var posts = 0
        WatchCoordinator(recreated::read, recreated::update, {
            dev.dshmobile.app.model.SessionSnapshot(dev.dshmobile.app.model.SessionSummary("chat", "Synthetic", "w", 1, false, true),
                emptyList(), 1, false, "idle", promptOutcomes = listOf(dev.dshmobile.app.model.PromptOutcome(request, "turn", "completed")))
        }, { _, _ -> posts++ }, { 101 }).resync()
        assertEquals(1, posts); assertTrue(recreated.read().watches.isEmpty())
    }
    @Test fun missingBaseRecoversApi26BackupInsteadOfForgettingOptInAndCursor() = runBlocking {
        val cipher = StateCipher { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        val expected = NotificationLocal(enabled = true, cursor = "head", processed = listOf(ProcessedNotification("event", 100)))
        var backup: ByteArray? = cipher.encrypt(mobileJson.encodeToString(NotificationLocal.serializer(), expected).toByteArray())
        var recovered = false
        val record = object : AtomicStateFile {
            override val identity = "synthetic/notifications-recovery.enc"
            override fun openRead(): InputStream { recovered = true; return ByteArrayInputStream(backup ?: throw FileNotFoundException()) }
            override fun startWrite(): OutputStream = error("not needed")
            override fun finishWrite(stream: OutputStream) = Unit
            override fun failWrite(stream: OutputStream?) = Unit
            override fun delete() = Unit
        }
        val store = NotificationStore(record, cipher)
        assertEquals(expected, store.read()); assertTrue(recovered)
        backup = null; assertEquals(NotificationLocal(), store.read())
    }
}
