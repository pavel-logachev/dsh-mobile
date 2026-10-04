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
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Format and AEAD are JVM-testable; the production key never leaves AndroidKeyStore. */
internal class StateCipher(private val key: () -> SecretKey) {
    private val aad = "dev.dshmobile.app/state/v1".toByteArray(Charsets.UTF_8)
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Provider generates a fresh random nonce each write.
        cipher.updateAAD(aad)
        val iv = cipher.iv
        require(iv.size == 12)
        return byteArrayOf(1) + iv + cipher.doFinal(plain)
    }
    fun decrypt(sealed: ByteArray): ByteArray {
        if (sealed.size < 29 || sealed[0] != 1.toByte()) throw MobileFailure("storage_failed")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.copyOfRange(13, sealed.size))
    }
}

internal class EncryptedStateStore(context: Context) : SecureStateStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "mobile-state.enc"))
    private val mutex = Mutex()
    private val cipher = StateCipher(::key)
    private val alias = "dsh-mobile-state-v1"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    override suspend fun read(): StoredState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sealed = try {
                file.openRead().use { it.readAtMost(MAX_STORED_BYTES + 1) }
            } catch (_: FileNotFoundException) { return@withLock StoredState() }
            if (sealed.size > MAX_STORED_BYTES) throw MobileFailure("storage_failed")
            try {
                val plain = cipher.decrypt(sealed)
                try { mobileJson.decodeFromString<StoredState>(plain.toString(Charsets.UTF_8)) }
                finally { plain.fill(0) }
            } catch (_: Exception) { throw MobileFailure("storage_failed") }
        }
    }

    override suspend fun write(value: StoredState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val plain = try { mobileJson.encodeToString(value).toByteArray(Charsets.UTF_8) }
                catch (failure: Exception) { safeDebugDiagnostic("store.encode", failure); throw failure }
            val sealed = try {
                if (plain.size > MAX_STORED_BYTES - 29) throw MobileFailure("storage_failed")
                cipher.encrypt(plain)
            } catch (failure: Exception) { safeDebugDiagnostic("store.encrypt", failure); throw failure }
            finally { plain.fill(0) }
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(sealed)
                file.finishWrite(stream)
            } catch (failure: Exception) {
                safeDebugDiagnostic("store.atomic-commit", failure)
                file.failWrite(stream)
                throw MobileFailure("storage_failed")
            }
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Commit an empty encrypted record first: if deletion is interrupted there is no credential.
            val sealed = cipher.encrypt(mobileJson.encodeToString(StoredState()).toByteArray())
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(sealed)
                file.finishWrite(stream)
                file.delete()
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry(alias)
            } catch (_: Exception) {
                file.failWrite(stream)
                throw MobileFailure("storage_failed")
            }
        }
    }
    private companion object { const val MAX_STORED_BYTES = 2 * 1024 * 1024 }
}
