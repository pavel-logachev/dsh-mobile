package dev.dshmobile.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dev.dshmobile.app.ui.pairing.OfflineQrDecoder
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class OfflineQrDecoderTest {
    @Test fun `QR-only worker decoder accepts exact UTF-8 including rotated padded luminance frames`() {
        val payload = """{"version":1,"label":"Синтетический ПК","baseUrl":"https://computer.example"}"""
        val decoder = OfflineQrDecoder()
        var qr = qr(payload)
        repeat(4) {
            val frame = frame(qr, pixelStride = 2, padding = 17, offset = 9)
            val before = frame.buffer.array().copyOf()
            assertEquals(payload, decoder.decode(frame.buffer, qr.width, qr.height, frame.rowStride, 2))
            assertEquals(9, frame.buffer.position())
            assertArrayEquals(before, frame.buffer.array()) // Never mutates/owns the camera plane.
            qr = rotate(qr)
        }
    }
    @Test fun `compact envelope reaches invitation decoding without scanner transformations`() {
        val payload = "dshm1:eJyrVipLLSrOzM9TsjKsBQAg4ASl"
        val qr = qr(payload)
        val frame = frame(qr)
        assertEquals(payload, OfflineQrDecoder().decode(frame.buffer, qr.width, qr.height, frame.rowStride, 1))
    }
    @Test fun `blank corrupt and overlarge plane shapes produce no result and no exception`() {
        val decoder = OfflineQrDecoder()
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64 * 64) { 0xff.toByte() }), 64, 64, 64, 1))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), 64, 64, 64, 1))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), 0, 1, 64, 1))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), 8, 8, 8, 0))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), 8, 8, 4, 1))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), 4096, 4096, 4096, 1))
        assertNull(decoder.decode(ByteBuffer.wrap(ByteArray(64)), Int.MAX_VALUE, 2, Int.MAX_VALUE, 1))
    }
    @Test fun `decoder does not accept other barcode formats`() {
        val barcode = com.google.zxing.oned.Code128Writer().encode("synthetic-barcode", BarcodeFormat.CODE_128, 320, 320)
        val frame = frame(barcode)
        assertNull(OfflineQrDecoder().decode(frame.buffer, barcode.width, barcode.height, frame.rowStride, 1))
    }
    private fun qr(value: String) = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 320, 320,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    private fun rotate(source: BitMatrix): BitMatrix = BitMatrix(source.height, source.width).also { target ->
        for (y in 0 until source.height) for (x in 0 until source.width) if (source[x, y]) target.set(source.height - y - 1, x)
    }
    private class Frame(val buffer: ByteBuffer, val rowStride: Int)
    private fun frame(qr: BitMatrix, pixelStride: Int = 1, padding: Int = 0, offset: Int = 0): Frame {
        val rowStride = qr.width * pixelStride + padding
        val bytes = ByteArray(offset + rowStride * qr.height) { 0x7f }
        for (y in 0 until qr.height) for (x in 0 until qr.width) {
            bytes[offset + y * rowStride + x * pixelStride] = if (qr[x, y]) 0 else 0xff.toByte()
        }
        return Frame(ByteBuffer.wrap(bytes).apply { position(offset) }, rowStride)
    }
}
