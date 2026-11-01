package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReviewRegressionTest {
    private val snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic", "project", 1, false, true), emptyList(), 1, false, "idle")
    @Test fun `shared rejection invalidates every owner and stale writes cannot resurrect disk`() = runBlocking {
        val disk = MemoryHistoryStore()
        val a = PersistentHistory(disk, { 100 })
        val b = PersistentHistory(disk, { 100 })
        a.restore("binding"); a.put(snapshot); b.restore("binding")
        disk.authority.invalidate("revoked") { disk.clear() }
        assertNull(a.get("chat")); assertNull(b.get("chat"))
        a.put(snapshot); b.flush()
        assertTrue(disk.read().entries.isEmpty())
        a.bindAuthorized("binding", disk.authority.epoch); a.put(snapshot); a.flush()
        val recreated = PersistentHistory(disk, { 100 }); recreated.restore("binding")
        assertEquals(snapshot, recreated.get("chat"))
    }
    @Test fun `snapshot bursts coalesce and flush persists newest empty rollback`() = runTest {
        var writes = 0
        var value = HistoryRecord()
        val disk = object : HistoryStore {
            override val authority = HistoryAuthority()
            override suspend fun read() = value
            override suspend fun write(record: HistoryRecord) { value = record; writes++ }
            override suspend fun clear() { value = HistoryRecord() }
        }
        val cache = PersistentHistory(disk, { testScheduler.currentTime }, flushScope = this)
        cache.restore("binding")
        repeat(20) { cache.put(snapshot.copy(cursor = it.toLong())) }
        assertEquals(0, writes); advanceTimeBy(2001); runCurrent()
        assertEquals(1, writes); assertEquals(19L, value.entries.single().snapshot.cursor)
        cache.put(snapshot.copy(cursor = -1)); cache.flush()
        assertEquals(-1L, value.entries.single().snapshot.cursor)
    }
    @Test fun `clear rebinds only on next authorized snapshot and survives restart`() = runBlocking {
        val disk = MemoryHistoryStore(); val cache = PersistentHistory(disk, { 100 })
        cache.restore("binding"); cache.put(snapshot); cache.clear()
        cache.bindAuthorized("binding", disk.authority.epoch); cache.put(snapshot)
        val next = PersistentHistory(disk, { 100 }); next.restore("binding")
        assertEquals(snapshot, next.get("chat"))
    }
    @Test fun `GET rate limit propagates HTTP date cooldown`() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer(); server.start()
        val date = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setHeader("Retry-After", date)
            .setBody("""{"error":{"code":"rate_limited","message":"Synthetic","retryable":true}}"""))
        val api = HostApi(HostEndpoint(server.url("/").toString().removeSuffix("/")), "synthetic-only-device-token")
        try {
            try { api.snapshot("chat"); fail("Expected rate limit") }
            catch (failure: MobileFailure) { assertEquals("rate_limited", failure.key); assertTrue(failure.retryAfterMs in 110000..120000) }
        } finally { api.closeAndAwait(); server.shutdown() }
    }
    @Test fun `Retry After parser accepts seconds HTTP date malformed past and cap`() {
        val now = 0L
        assertEquals(120000L, retryAfterMillis("120", now))
        assertEquals(120000L, retryAfterMillis("Thu, 1 Jan 1970 00:02:00 GMT", now))
        assertEquals(0L, retryAfterMillis("unknown", now))
        assertEquals(0L, retryAfterMillis("Thu, 1 Jan 1970 00:02:00 GMT", 180000))
        assertEquals(3600000L, retryAfterMillis("999999", now))
    }
    @Test fun `atomic coexistence budget is at most ten MiB`() {
        assertTrue(EncryptedHistoryStore.RECORD_LIMIT * 2 <= 10 * 1024 * 1024)
    }
}
