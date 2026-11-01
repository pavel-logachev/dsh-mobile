package dev.dshmobile.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PhoneOutcomeWireTest {
    private val id = "abcdefab-0000-4000-8000-000000000001"
    private fun body(status: String = "unknown", extra: String = "") =
        """{"token":"opaque +/=😀","outcomes":[{"requestId":"$id","status":"$status","updatedAt":"2026-11-01T12:00:00.000Z"$extra}]}"""
    @Test fun authenticatedEndpointCanonicalizesIdsAndEncodesOpaqueToken() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(json(body()))
            val api = HostApi(HostEndpoint(server.url("/").toString()), "synthetic-token")
            try {
                val result = api.promptOutcomes("чат 😀", listOf(id.uppercase(), id), "old +/=😀", 25)
                assertEquals("unknown", result.outcomes.single().status)
                val req = server.takeRequest()
                assertEquals(listOf("v1", "sessions", "чат 😀", "prompt-outcomes"), req.requestUrl!!.pathSegments)
                assertEquals(listOf(id), req.requestUrl!!.queryParameterValues("requestId"))
                assertEquals("old +/=😀", req.requestUrl!!.queryParameter("since"))
                assertEquals("25", req.requestUrl!!.queryParameter("waitSeconds"))
                assertEquals("Bearer synthetic-token", req.getHeader("Authorization"))
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun optionalTurnUnknownAndUnicodeFailureSummaryFollowContract() {
        assertNull(decode(body()).outcomes.single().turnId)
        val summary = "Ошибка 😀 é é\nстрока\r\nещё\r\uFEFF"
        val extra = ",\"turnId\":\"ход 😀\",\"summary\":" + mobileJson.encodeToString(kotlinx.serialization.serializer<String>(), summary)
        assertEquals(summary, decode(body("failed", extra)).outcomes.single().summary)
        assertEquals("😀".repeat(512), decode(body("failed", ",\"summary\":\"${"😀".repeat(512)}\"")).outcomes.single().summary)
    }
    @Test fun malformedWireFailsClosedWithoutInventedStatusesOrSnapshotFields() {
        val invalid = listOf(body("future"), body().replace(id, id.uppercase()), body().replace("unknown", "12"),
            body().replace("\"unknown\"", "12"), body().replace("2026-11-01T12:00:00.000Z", "yesterday"),
            body().replace("2026-11-01T12:00:00.000Z", "2026-11-01T12:00:00+03:00"),
            body("completed", ",\"summary\":\"not failed\""), body("failed", ",\"summary\":\"${"😀".repeat(513)}\""),
            body("failed", ",\"summary\":\"\\uD800\""), body().replace("\"token\":", "\"token\":12,\"other\":"),
            body().replace("\"outcomes\":[", "\"outcomes\":[${body().substringAfter("\"outcomes\":[").substringBeforeLast("]")},"),
            body().replace("\"outcomes\":[", "\"outcomes\":[] ,\"other\":["), "\uFEFF" + body())
        for (text in invalid) try { decode(text); fail("Accepted malformed response: $text") } catch (_: MobileFailure) {}
        for (text in listOf(body().replace(",\"outcomes\"", ",\n\"outcomes\""), body().replace(",\"outcomes\"", ",\r\n\"outcomes\""), body().replace(",\"outcomes\"", ",\r\"outcomes\""))) assertEquals(1, decode(text).outcomes.size)
    }
    @Test fun queryBoundsAndResponseOrderAndMissingRequiredFields() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = HostApi(HostEndpoint(server.url("/").toString()))
            try {
                val tooMany = (0..32).map { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }
                for (ids in listOf(emptyList(), listOf(""), listOf("１２"), tooMany)) {
                    try { api.promptOutcomes("chat", ids); fail("bad ids") } catch (failure: MobileFailure) { assertEquals("invalid_selection", failure.key) }
                }
                for (wait in listOf(-1, 26, Int.MAX_VALUE)) try { api.promptOutcomes("chat", listOf(id), waitSeconds = wait); fail("bad wait") }
                    catch (failure: MobileFailure) { assertEquals("invalid_selection", failure.key) }
                assertEquals(0, server.requestCount)
            } finally { api.closeAndAwait() }
        }
        val malformed = listOf("{}", "[]", "", body().replace("\"token\":\"opaque +/=😀\",", ""),
            body().replace("\"updatedAt\":\"2026-11-01T12:00:00.000Z\"", "\"other\":0"), body().replace("unknown", ""),
            body().replace("\"unknown\"", "null"), body().replace("\"unknown\"", "true"))
        for (text in malformed) try { decode(text); fail("required field") } catch (_: MobileFailure) {}
        val one = body().substringAfter("\"outcomes\":[").substringBeforeLast("]")
        val other = "abcdefab-0000-4000-8000-000000000002"
        val two = "{\"token\":\"\",\"outcomes\":[$one,${one.replace(id, other)}]}"
        assertEquals(2, decodePromptOutcomes(two, listOf(id, other)).outcomes.size)
        try { decodePromptOutcomes(two, listOf(other, id)); fail("request order") } catch (_: MobileFailure) {}
    }
    private fun decode(text: String) = decodePromptOutcomes(text, listOf(id))
    private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
}
