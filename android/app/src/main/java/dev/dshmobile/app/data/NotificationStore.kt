package dev.dshmobile.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Separate encrypted atomic record: service never writes the repository's command/draft record. */
internal class NotificationStore internal constructor(private val file: AtomicStateFile, private val cipher: StateCipher) {
    constructor(context: Context) : this(AndroidAtomicStateFile(File(context.applicationContext.noBackupFilesDir, "notifications.enc")), StateCipher {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    })
    private val mutex = StateFileOwners.mutex(file.identity)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = updates
    suspend fun read(): NotificationLocal = withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }
    suspend fun update(change: (NotificationLocal) -> NotificationLocal): NotificationLocal = withContext(Dispatchers.IO) {
        mutex.withLock {
            val value = change(readLocked())
            val plain = mobileJson.encodeToString(value).toByteArray()
            if (plain.size > 512 * 1024) throw MobileFailure("storage_failed")
            val sealed = try { cipher.encrypt(plain) } finally { plain.fill(0) }
            val out = file.startWrite()
            try { out.write(sealed); file.finishWrite(out) } catch (_: Exception) { file.failWrite(out); throw MobileFailure("storage_failed") }
            updates.value = updates.value + 1
            value
        }
    }
    private fun readLocked(): NotificationLocal {
        try {
            val bytes = file.openRead().use { it.readAtMost(512 * 1024 + 1) }
            if (bytes.size > 512 * 1024) throw MobileFailure("storage_failed")
            val plain = cipher.decrypt(bytes)
            return try { mobileJson.decodeFromString<NotificationLocal>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        } catch (_: java.io.FileNotFoundException) { return NotificationLocal() }
        catch (_: Exception) { throw MobileFailure("storage_failed") }
    }
    companion object { private val updates = kotlinx.coroutines.flow.MutableStateFlow(0L); private const val ALIAS = "dsh-mobile-notifications-v1" }
}
