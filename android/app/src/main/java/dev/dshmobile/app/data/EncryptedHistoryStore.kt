package dev.dshmobile.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Separate no-backup atomic cache; never enlarges or rewrites the credential/draft record. */
internal class EncryptedHistoryStore internal constructor(private val file: AtomicStateFile,
    private val cipher: StateCipher, private val deleteKey: () -> Unit) : HistoryStore {
    constructor(context: Context) : this(AndroidAtomicStateFile(File(context.applicationContext.noBackupFilesDir, "history.enc")),
        StateCipher { key() }, { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) })
    override val authority = HistoryAuthorities.owner(file.identity)
    suspend fun reject(key: String) = authority.invalidate(key) { clear() }
    private val mutex = StateFileOwners.mutex(file.identity)
    override suspend fun read(): HistoryRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = try { file.openRead().use { it.readAtMost(LIMIT + 1) } } catch (_: FileNotFoundException) { return@withLock HistoryRecord() }
            if (bytes.size > LIMIT) throw MobileFailure("storage_failed")
            val plain = cipher.decrypt(bytes)
            try { mobileJson.decodeFromString<HistoryRecord>(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        }
    }
    override suspend fun write(value: HistoryRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plain = mobileJson.encodeToString(value).toByteArray(Charsets.UTF_8)
            val sealed = try { require(plain.size <= LIMIT - 29); cipher.encrypt(plain) } finally { plain.fill(0) }
            val out = file.startWrite()
            try { out.write(sealed); file.finishWrite(out) } catch (failure: Exception) { file.failWrite(out); throw failure }
        }
    }
    override suspend fun clear() = withContext(Dispatchers.IO) { mutex.withLock {
        file.delete()
        try { deleteKey() } catch (_: Exception) { /* Deletion needs no working Keystore. */ }
    } }
    companion object {
        internal const val RECORD_LIMIT = 5 * 1024 * 1024
        private const val LIMIT = RECORD_LIMIT
        const val ALIAS = "dsh-mobile-history-v1"
        fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
                generateKey()
            }
        }
    }
}
