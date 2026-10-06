package dev.dshmobile.app.data

import java.io.*
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EncryptedStateOwnershipTest {
    /** Models API26 AtomicFile: openRead rolls an in-flight write back unless serialized. */
    private class Record : AtomicStateFile {
        override val identity = "synthetic/mobile-state.enc"
        var bytes: ByteArray? = null
        var writing = false
        var rolledBack = false
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        override fun openRead(): InputStream { if (writing) rolledBack = true; return ByteArrayInputStream(bytes ?: throw FileNotFoundException()) }
        override fun startWrite(): OutputStream { writing = true; entered.complete(Unit); release.await(); return ByteArrayOutputStream() }
        override fun finishWrite(stream: OutputStream) { if (!rolledBack) bytes = (stream as ByteArrayOutputStream).toByteArray(); writing = false }
        override fun failWrite(stream: OutputStream?) { writing = false }
        override fun delete() { bytes = null }
    }
    @Test fun serviceReadCannotRollbackAnotherInstancesDurableCommandWrite() = runBlocking {
        val record = Record(); val cipher = StateCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        val repository = EncryptedStateStore(record, cipher) {}
        val service = EncryptedStateStore(record, cipher) {}
        val value = StoredState(drafts = mapOf("chat" to "durable draft"), pending = StoredCommand("command", "send", "chat", "text", status = "uncertain"))
        val writer = async(Dispatchers.IO) { repository.write(value) }; record.entered.await()
        val reader = async(Dispatchers.IO) { service.read() }
        try { delay(50); assertFalse("read must wait for the other instance's commit", reader.isCompleted) }
        finally { record.release.countDown() }
        writer.await(); assertEquals(value, reader.await()); assertFalse(record.rolledBack)
        service.clear(); assertEquals(StoredState(), repository.read())
    }
}
