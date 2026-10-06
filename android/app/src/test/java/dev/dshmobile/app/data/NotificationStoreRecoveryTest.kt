package dev.dshmobile.app.data

import java.io.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationStoreRecoveryTest {
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
