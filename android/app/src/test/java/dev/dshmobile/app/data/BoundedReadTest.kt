package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedReadTest {
    @Test fun `bounded stream read exposes at most one overflow byte with partial reads`() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), input.readAtMost(4))
        assertEquals(5, input.read())
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readAtMost(4))
    }
}
