package dev.dshmobile.app.data

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class StateCipherTest {
    @Test fun `state ciphertext decrypts intact uses fresh IV and rejects tampering or wrong key`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = StateCipher { key }
        val plain = "Synthetic private credential and durable draft".toByteArray()
        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, cipher.decrypt(first))
        val corrupted = first.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { cipher.decrypt(corrupted) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(Exception::class.java) { StateCipher { otherKey }.decrypt(first) }
        assertThrows(MobileFailure::class.java) { cipher.decrypt(byteArrayOf(1)) }
    }
}
