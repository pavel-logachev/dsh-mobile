package dev.dshmobile.app.data

import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Contract fixture: durable device/session receipts, ordered projection, opaque token,
 * equal-token hold/timeout, restart error, one held poll/device. No session snapshot/SSE. */
internal class PromptOutcomesServer : AutoCloseable {
    val server = MockWebServer()
    private val gate = Object()
    private val records = mutableMapOf<Pair<String, String>, PromptOutcome>()
    private var generation = 0
    private var restart = 0
    private var held = 0
    private val tokens = mutableMapOf<List<PromptOutcome>, String>()
    var maxHeld = 0; private set
    val entered = LinkedBlockingQueue<RecordedRequest>()
    val holding = LinkedBlockingQueue<RecordedRequest>()
    var capability: Boolean? = true
    var timeoutSliceMillis: Long? = null // Only shorten advertised holds in tests that do not test timing.
    val errors = mutableListOf<String>()
    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.offer(request)
                if (request.getHeader("Authorization") != "Bearer synthetic-device-token") return error(401, "unauthorized", false)
                val url = request.requestUrl!!
                if (url.encodedPath == "/v1/capabilities") return json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false,"notifications":false${capability?.let { ",\"promptOutcomes\":$it" }.orEmpty()}},"notificationCapabilities":{"feed":false}}""")
                val segments = url.pathSegments
                if (segments.size != 4 || segments[0] != "v1" || segments[1] != "sessions" || segments[3] != "prompt-outcomes") {
                    synchronized(gate) { errors += "unexpected endpoint" }; return error(404, "not_found", false)
                }
                val ids = url.queryParameterValues("requestId").map { it.orEmpty().lowercase() }.distinct()
                val wait = url.queryParameter("waitSeconds")?.toIntOrNull() ?: 0
                if (ids.size !in 1..32 || ids.any { !validRequestId(it) } || wait !in 0..25) return error(400, "invalid_request", false)
                val session = segments[2]
                synchronized(gate) {
                    fun outcomes() = ids.map { records[session to it] ?: PromptOutcome(it, "unknown", "2026-11-01T12:00:00Z") }
                    // Token depends on the exact returned projection, not unrelated chat updates.
                    fun token() = tokens.getOrPut(outcomes()) { "opaque:${tokens.size} +/=" }
                    val startRestart = restart
                    val ticket = ++generation
                    gate.notifyAll() // A new held request releases the prior one per contract.
                    if (wait > 0 && url.queryParameter("since") == token()) {
                        held++; maxHeld = maxOf(maxHeld, held); holding.offer(request)
                        try {
                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutSliceMillis ?: wait * 1000L)
                            while (url.queryParameter("since") == token() && startRestart == restart && ticket == generation) {
                                val remaining = deadline - System.nanoTime()
                                if (remaining <= 0) break
                                TimeUnit.NANOSECONDS.timedWait(gate, remaining)
                            }
                        } finally { held-- }
                    }
                    if (startRestart != restart) return error(503, "unavailable", true)
                    return json(mobileJson.encodeToString(PromptOutcomes(token(), outcomes())))
                }
            }
        }
        server.start()
    }
    fun admit(session: String, id: String, status: String = "queued", turn: String? = null, summary: String? = null) = synchronized(gate) {
        records[session to id] = PromptOutcome(id, status, "2026-11-01T12:00:00Z", turn, summary); gate.notifyAll()
    }
    fun restart() = synchronized(gate) { restart++; gate.notifyAll() } // receipts/journal survive.
    fun api() = HostApi(HostEndpoint(server.url("/").toString()), "synthetic-device-token")
    fun next() = entered.poll(5, TimeUnit.SECONDS) ?: error("No HTTP request")
    fun held() = holding.poll(5, TimeUnit.SECONDS) ?: error("No held HTTP request")
    override fun close() { restart(); server.close() }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun error(code: Int, key: String, retryable: Boolean) = json("""{"error":{"code":"$key","message":"Synthetic","retryable":$retryable}}""").setResponseCode(code)
}
