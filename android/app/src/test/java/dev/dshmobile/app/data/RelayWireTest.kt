package dev.dshmobile.app.data

import org.java_websocket.WebSocketImpl
import org.java_websocket.WebSocketAdapter
import org.java_websocket.enums.Role
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.BinaryFrame
import org.java_websocket.framing.ContinuousFrame
import org.java_websocket.handshake.HandshakeImpl1Client
import org.java_websocket.handshake.HandshakeImpl1Server
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RelayWireTest {
    private val listener = object : WebSocketAdapter() {
        override fun onWebsocketMessage(conn: org.java_websocket.WebSocket, message: String) {}
        override fun onWebsocketMessage(conn: org.java_websocket.WebSocket, blob: ByteBuffer) {}
        override fun onWebsocketOpen(conn: org.java_websocket.WebSocket, handshake: org.java_websocket.handshake.Handshakedata) {}
        override fun onWebsocketClose(conn: org.java_websocket.WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onWebsocketError(conn: org.java_websocket.WebSocket, ex: Exception) {}
        override fun onWriteDemand(conn: org.java_websocket.WebSocket) {}
        override fun onWebsocketClosing(conn: org.java_websocket.WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onWebsocketCloseInitiated(conn: org.java_websocket.WebSocket, code: Int, reason: String) {}
        override fun getLocalSocketAddress(conn: org.java_websocket.WebSocket) = null
        override fun getRemoteSocketAddress(conn: org.java_websocket.WebSocket) = null
    }
    @Test fun `unfragmented protocol rejects first fragment before any accumulation`() {
        val draft = RelayWebSocketDraft()
        val socket = WebSocketImpl(listener, draft)
        val first = BinaryFrame().apply { setFin(false); setPayload(ByteBuffer.wrap(byteArrayOf(1))) }
        assertThrows(InvalidDataException::class.java) { draft.processFrame(socket, first) }
        assertThrows(InvalidDataException::class.java) { draft.processFrame(socket, ContinuousFrame()) }
        assertTrue(draft.copyInstance() is RelayWebSocketDraft)
    }
    @Test fun `raw huge frame text compression and zero continuation are rejected without body assembly`() {
        val frames = listOf(
            byteArrayOf(0x82.toByte(), 127, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()),
            byteArrayOf(0x80.toByte(), 0), byteArrayOf(0x02, 0), byteArrayOf(0x81.toByte(), 0), byteArrayOf(0xc2.toByte(), 0)
        )
        for (raw in frames) {
            val draft = RelayWebSocketDraft().apply { setParseMode(Role.CLIENT) }
            val socket = WebSocketImpl(listener, draft)
            assertThrows(InvalidDataException::class.java) {
                draft.translateFrame(ByteBuffer.wrap(raw)).forEach { draft.processFrame(socket, it) }
            }
        }
    }
    @Test fun `header cap permits coalesced first data but extension negotiation is rejected`() {
        val draft = RelayWebSocketDraft().apply { setParseMode(Role.CLIENT) }
        val request = HandshakeImpl1Client().apply { resourceDescriptor = "/v1/mobile" }
        draft.postProcessHandshakeRequestAsClient(request)
        val response = HandshakeImpl1Server().apply {
            setHttpStatus(101); httpStatusMessage = "Switching Protocols"
            put("Upgrade", "websocket"); put("Connection", "Upgrade")
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest((request.getFieldValue("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
            put("Sec-WebSocket-Accept", java.util.Base64.getEncoder().encodeToString(digest))
        }
        assertEquals(org.java_websocket.enums.HandshakeState.MATCHED, draft.acceptHandshakeAsClient(request, response))
        response.put("Sec-WebSocket-Extensions", "permessage-deflate")
        assertThrows(org.java_websocket.exceptions.InvalidHandshakeException::class.java) { draft.acceptHandshakeAsClient(request, response) }
        val buffer = ByteBuffer.wrap(("HTTP/1.1 101 Switching Protocols\r\nX: ok\r\n\r\n".toByteArray() + ByteArray(32768)))
        assertNotNull(draft.translateHandshake(buffer))
    }
    @Test fun `oversized upgrade headers are rejected before HTTP parser assembly`() {
        val draft = RelayWebSocketDraft().apply { setParseMode(Role.CLIENT) }
        assertThrows(org.java_websocket.exceptions.InvalidHandshakeException::class.java) {
            draft.translateHandshake(ByteBuffer.wrap(("HTTP/1.1 101 Switching Protocols\r\nX: " + "a".repeat(8192)).toByteArray()))
        }
    }
}
