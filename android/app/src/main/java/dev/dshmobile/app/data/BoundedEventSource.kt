package dev.dshmobile.app.data

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

internal class EventLimitExceeded : IOException("invalid_response")

/**
 * An APPLICATION interceptor sees decompressed bytes. The ceiling is enforced in 8 KiB reads
 * BEFORE OkHttp's SSE reader buffers a line/data event, even for unknown or unterminated events.
 * Blank SSE lines reset the record budget; heartbeat comments are bounded too. CR/LF/CRLF work.
 */
internal class BoundedEventSource(private val limit: Long = 2L * 1024 * 1024) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (!response.isSuccessful || body.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true) return response
        val guarded = object : ForwardingSource(body.source()) {
            private val scratch = Buffer()
            private var eventBytes = 0L
            private var lineBytes = 0L
            private var afterCr = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(scratch, minOf(byteCount, 8192L))
                if (read <= 0) return read
                for (index in 0 until read) {
                    val byte = scratch[index].toInt() and 0xff
                    if (afterCr && byte == 10) { afterCr = false; continue }
                    afterCr = false
                    eventBytes++
                    if (eventBytes > limit) throw EventLimitExceeded()
                    if (byte == 10 || byte == 13) {
                        if (lineBytes == 0L) eventBytes = 0
                        lineBytes = 0
                        afterCr = byte == 13
                    } else lineBytes++
                }
                sink.write(scratch, read)
                return read
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType(): MediaType? = body.contentType()
            override fun contentLength(): Long = body.contentLength()
            override fun source(): BufferedSource = guarded
        }).build()
    }
}
