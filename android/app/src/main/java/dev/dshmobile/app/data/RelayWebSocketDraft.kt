package dev.dshmobile.app.data

import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.HandshakeState
import org.java_websocket.enums.Opcode
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.InvalidHandshakeException
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.Handshakedata
import org.java_websocket.handshake.ServerHandshake
import java.nio.ByteBuffer

/** Fixed relay wire subset. Guard before the library can accumulate fragmented payloads. */
internal class RelayWebSocketDraft : Draft_6455(emptyList(), MAX_FRAME) {
    override fun copyInstance(): Draft = RelayWebSocketDraft()

    override fun processFrame(socket: WebSocketImpl, frame: Framedata) {
        checkFrame(frame)
        super.processFrame(socket, frame)
    }
    override fun createBinaryFrame(frame: Framedata): ByteBuffer {
        checkFrame(frame)
        return super.createBinaryFrame(frame)
    }
    private fun checkFrame(frame: Framedata) {
        if (!frame.isFin || frame.opcode in setOf(Opcode.CONTINUOUS, Opcode.TEXT) || frame.isRSV1 || frame.isRSV2 || frame.isRSV3 ||
            frame.payloadData.remaining() > (if (frame.opcode == Opcode.BINARY) MAX_FRAME else 125))
            throw InvalidDataException(1002, "invalid relay frame")
    }
    override fun acceptHandshakeAsClient(request: ClientHandshake, response: ServerHandshake): HandshakeState {
        if (response.getFieldValue("Sec-WebSocket-Extensions").isNotEmpty() || response.getFieldValue("Sec-WebSocket-Protocol").isNotEmpty())
            throw InvalidHandshakeException("unexpected relay extension")
        return super.acceptHandshakeAsClient(request, response)
    }
    override fun translateHandshake(buffer: ByteBuffer): Handshakedata {
        val view = buffer.duplicate()
        val start = view.position()
        var tail = 0
        var end = -1
        while (view.hasRemaining() && view.position() - start <= MAX_HEADERS) {
            tail = (tail shl 8) or (view.get().toInt() and 255)
            if (tail == 0x0d0a0d0a) { end = view.position() - start; break }
        }
        if (end > MAX_HEADERS || (end < 0 && buffer.remaining() >= MAX_HEADERS))
            throw InvalidHandshakeException("relay headers exceed limit")
        return super.translateHandshake(buffer)
    }
    companion object { const val MAX_FRAME = 32 * 1024; const val MAX_HEADERS = 8 * 1024 }
}
