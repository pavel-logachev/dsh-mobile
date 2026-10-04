package dev.dshmobile.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class InvitationQrPayloadTest {
    private val json = """{"version":1,"baseUrl":"https://computer.example","pinSha256":"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","pairingToken":"synthetic-one-use-token","label":"Синтетический ПК"}"""

    @Test fun `zlib envelope round trips exact UTF-8 JSON and raw JSON stays unchanged`() {
        assertEquals(json, InvitationQrPayload.decode(compact(json.toByteArray(Charsets.UTF_8))))
        assertEquals("  $json\n", InvitationQrPayload.decode("  $json\n"))
    }

    @Test fun `Node deflateSync fixture is compatible without a raw deflate fallback`() {
        val fixture = "dshm1:eJyrVipLLSrOzM9TsjLUUUpKLE4NLcpRslLKKCkpKLbS10_Ozy0oLUkt0kutSMwtyElV0lEqyMwLzkg0MjVTslIqBjP0HYkHtiATEjOLMvPSQ_KzU_NAhlTmlWSklmQm6-bnpeqWFqfqloBldJRyEpNSQa65sPDCjgt7LzZd2Hqx6cKOi-0Xtl5svLDrwo4LOxUuzL8wS6kWAGraSrE"
        assertEquals(json, InvitationQrPayload.decode(fixture))
    }

    @Test fun `wrong prefix links empty and non-JSON values are rejected with a safe key`() {
        for (value in listOf("dshm2:eJyr", "DSHM1:eJyr", "https://computer.example/?invitation=secret", "", "[]", "null", "dshm1:")) invalid(value)
    }

    @Test fun `malformed padded and noncanonical base64url is rejected`() {
        for (suffix in listOf("*", "A", "AA=", "AA+_", "AA/_", "AA\n", "AB")) invalid("dshm1:$suffix")
    }

    @Test fun `input and compressed limits are checked before inflation`() {
        invalid("dshm1:" + "A".repeat(87_383))
        invalid("dshm1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65_537)))
        invalid("{" + " ".repeat(65_536))
        invalid("{" + "я".repeat(32_768)) // Within the char limit, outside the byte limit.
    }

    @Test(timeout = 2000) fun `oversize zlib bomb is rejected and exact 64 KiB is allowed`() {
        val boundary = "{" + " ".repeat(65_534) + "}"
        assertEquals(boundary, InvitationQrPayload.decode(compact(boundary.toByteArray())))
        invalid(compact((boundary + " ").toByteArray()))
        invalid(compact(("{" + " ".repeat(4 * 1024 * 1024)).toByteArray()))
    }

    @Test fun `truncated zlib checksum corruption raw deflate and gzip are rejected`() {
        val compressed = Base64.getUrlDecoder().decode(compact(json.toByteArray()).removePrefix("dshm1:"))
        for (length in listOf(0, 1, 2, compressed.size - 4, compressed.size - 1)) invalid(envelope(compressed.copyOf(length)))
        invalid(envelope(compressed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }))
        invalid(compact(json.toByteArray(), raw = true))
        val gzip = ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(json.toByteArray()) } }
        invalid(envelope(gzip.toByteArray()))
    }

    @Test fun `trailing bytes concatenated streams and preset dictionaries are rejected`() {
        val compressed = Base64.getUrlDecoder().decode(compact(json.toByteArray()).removePrefix("dshm1:"))
        invalid(envelope(compressed + byteArrayOf(0)))
        invalid(envelope(compressed + compressed))
        val deflater = Deflater().apply { setDictionary("synthetic dictionary".toByteArray()) }
        try {
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output, deflater).use { it.write(json.toByteArray()) }
            invalid(envelope(output.toByteArray()))
        } finally { deflater.end() }
    }

    @Test fun `invalid UTF-8 or non-JSON inflated text never reaches invitation parsing`() {
        invalid(compact(byteArrayOf(123, 34, 0xc3.toByte(), 0x28, 34, 125)))
        invalid(compact("not an invitation".toByteArray()))
        invalid(compact(ByteArray(0)))
    }

    private fun invalid(value: String) {
        val failure = org.junit.Assert.assertThrows(MobileFailure::class.java) { InvitationQrPayload.decode(value) }
        assertEquals("invitation_invalid", failure.key)
        org.junit.Assert.assertNull(failure.cause)
        assertEquals("invitation_invalid", failure.message)
    }

    private fun envelope(bytes: ByteArray) = "dshm1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun compact(bytes: ByteArray, raw: Boolean = false): String {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
        try { DeflaterOutputStream(output, deflater).use { it.write(bytes) } }
        finally { deflater.end() }
        return "dshm1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(output.toByteArray())
    }
}
