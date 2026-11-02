package dev.dshmobile.app.data

import java.io.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationStoreRecoveryTest {
    @Test fun restoredWatchCarriesChatTitleIntoEveryAlertPhase() = runBlocking {
        // Synthetic persisted record; a recreated service has no repository/session list.
        val restored = mobileJson.decodeFromString<NotificationLocal>("""{"deviceId":"device","watches":[{"sessionId":"chat","requestId":"00000000-0000-4000-8000-000000000001","expiresAt":1000,"title":"Синтетический чат 🚀"}]}""")
        var local = restored
        val posted = mutableListOf<WatchAlert>()
        var status = "waiting"
        val coordinator = WatchCoordinator({ local }, { change -> change(local).also { local = it } }, { _, ids, _, _ ->
            PromptOutcomes("t", listOf(PromptOutcome(ids.single(), status, "2026-11-01T12:00:00Z", "turn")))
        }, { alert, _ -> posted += alert }, { 101 })
        coordinator.resync()
        status = "completed"
        coordinator.resync()
        assertEquals(listOf("attention", "answer"), posted.map { it.kind })
        assertEquals(listOf("Синтетический чат 🚀", "Синтетический чат 🚀"), posted.map { it.title })
        assertTrue(local.watches.isEmpty())
    }
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
        first.update { it.registerWatch("device", "chat", request, 100, "Synthetic encrypted title 🚀") }
        assertFalse(bytes!!.toString(Charsets.UTF_8).contains(request))
        assertFalse(bytes!!.toString(Charsets.UTF_8).contains("Synthetic encrypted title"))
        val recreated = NotificationStore(record, StateCipher { key })
        assertTrue(recreated.read().needsWatchService(101))
        var posts = 0
        WatchCoordinator(recreated::read, recreated::update, { _, _, _, _ ->
            PromptOutcomes("t", listOf(PromptOutcome(request, "completed", "2026-11-01T12:00:00Z", "turn")))
        }, { alert, _ -> assertEquals("Synthetic encrypted title 🚀", alert.title); posts++ }, { 101 }).resync()
        assertEquals(1, posts); assertTrue(recreated.read().watches.isEmpty())
    }
    @Test fun missingBaseRecoversApi26BackupInsteadOfForgettingLocalPolicyAndWatch() = runBlocking {
        val cipher = StateCipher { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        val expected = NotificationLocal(enabled = false, watches = listOf(PhoneWatch("chat", "00000000-0000-4000-8000-000000000001", 1000)))
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
