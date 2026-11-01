package dev.dshmobile.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import javax.crypto.spec.SecretKeySpec

class PromptOutcomesIntegrationTest {
    private val id = "abcdefab-0000-4000-8000-000000000001"
    private val other = "abcdefab-0000-4000-8000-000000000002"
    private class Harness(val api: HostApi, var local: NotificationLocal, val clock: () -> Long = { 101 }) {
        val posts = mutableListOf<Pair<WatchAlert, Boolean>>()
        val visible = mutableMapOf<String, WatchAlert>()
        suspend fun update(change: (NotificationLocal) -> NotificationLocal) = change(local).also { local = it }
        val coordinator = WatchCoordinator({ local }, ::update, api::promptOutcomes, { alert, replay ->
            posts += alert to replay; visible[watchNotificationTag("device", alert.sessionId)] = alert
        }, clock)
    }
    private fun watch() = NotificationLocal().registerWatch("device", "chat", id, 100)
    @Test fun completedAndDuplicatePollsProduceOneAlert() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "completed", "turn")
            val api = fake.api()
            try {
                val h = Harness(api, watch()); h.coordinator.resync(); h.coordinator.resync()
                assertEquals("answer", h.posts.single().first.kind); assertFalse(h.posts.single().second)
                assertTrue(h.local.watches.isEmpty()); assertTrue(h.local.outbox.isEmpty())
                assertEquals(1, fake.server.requestCount); assertTrue(fake.errors.isEmpty())
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun failedSummaryIncludingAstralCharactersReachesAlertUnchanged() = runBlocking {
        PromptOutcomesServer().use { fake ->
            val summary = "Ошибка 😀: файл é и é\nстрока"
            fake.admit("chat", id, "failed", "turn", summary)
            val api = fake.api()
            try { val h = Harness(api, watch()); h.coordinator.resync()
                assertEquals("error", h.posts.single().first.kind); assertEquals(summary, h.posts.single().first.summary)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun waitingThenCompletedPostsTwoAlertsSecondReplacesFirst() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "waiting", "turn")
            val api = fake.api()
            try {
                val h = Harness(api, watch()); h.coordinator.resync(); h.coordinator.resync()
                assertEquals(1, h.posts.size); assertEquals(1, h.local.watches.size)
                fake.admit("chat", id, "completed", "turn"); h.coordinator.resync()
                assertEquals(listOf("attention", "answer"), h.posts.map { it.first.kind })
                assertEquals(listOf(false, false), h.posts.map { it.second })
                assertEquals("answer", h.visible.values.single().kind); assertTrue(h.local.watches.isEmpty())
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun rejectedSettlesWithoutAlert() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "rejected"); val api = fake.api()
            try { val h = Harness(api, watch()); h.coordinator.resync(); assertTrue(h.posts.isEmpty()); assertTrue(h.local.watches.isEmpty()) }
            finally { api.closeAndAwait() }
        }
    }
    @Test fun unknownThenCompletedAndDesktopPromptInSameSessionNeverAlertsForDesktop() = runBlocking {
        PromptOutcomesServer().use { fake ->
            // Desktop has no device receipt; the fixture therefore cannot resolve its ID.
            val api = fake.api()
            try {
                assertEquals("unknown", api.promptOutcomes("chat", listOf(other)).outcomes.single().status)
                val h = Harness(api, watch()); h.coordinator.resync()
                assertTrue(h.posts.isEmpty()); assertEquals(1, h.local.watches.size)
                fake.admit("chat", id, "completed", "phone-turn"); h.coordinator.resync()
                assertEquals("phone-turn", h.posts.single().first.turnId)
                assertEquals("unknown", api.promptOutcomes("chat", listOf(other)).outcomes.single().status)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun longPollTimeoutReturnsSameTokenAndChangeReleasesNewToken() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "running", "turn"); val api = fake.api()
            try {
                val first = api.promptOutcomes("chat", listOf(id)); fake.next()
                val begin = System.nanoTime()
                val same = api.promptOutcomes("chat", listOf(id), first.token, 1)
                assertTrue((System.nanoTime() - begin) / 1_000_000 >= 900)
                assertEquals(first.token, same.token); fake.next(); fake.held()
                val changed = async(Dispatchers.Default) { api.promptOutcomes("chat", listOf(id), same.token, 25) }
                fake.held(); fake.admit("chat", id, "completed", "turn")
                val done = withTimeout(5000) { changed.await() }
                assertNotEquals(same.token, done.token); assertEquals("completed", done.outcomes.single().status)
                assertEquals(1, fake.maxHeld)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun longPollOutlivesOrdinaryFifteenSecondGetBudget() = runBlocking {
        PromptOutcomesServer().use { fake ->
            val api = fake.api()
            try {
                val first = api.promptOutcomes("chat", listOf(id))
                val start = System.nanoTime()
                val second = api.promptOutcomes("chat", listOf(id), first.token, 16)
                assertEquals(first.token, second.token)
                assertTrue((System.nanoTime() - start) / 1_000_000 >= 15_900)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun serverRestartMidPollKeepsWatchAndImmediateResyncFindsDurableOutcome() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.admit("chat", id, "running", "turn"); val api = fake.api()
            try {
                val h = Harness(api, watch()); h.coordinator.resync()
                supervisorScope {
                    val held = async(Dispatchers.Default) { h.coordinator.round() }
                    fake.held(); fake.restart()
                    try { held.await(); fail("shutdown must release poll with error") } catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
                }
                assertEquals(1, h.local.watches.size); assertTrue(h.posts.isEmpty())
                fake.admit("chat", id, "completed", "turn"); h.coordinator.resync()
                assertEquals(1, h.posts.size)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun concurrentResyncAndManySessionsHaveAtMostOneHeldLongPoll() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.timeoutSliceMillis = 10
            var local = watch().registerWatch("device", "second", other, 100)
            fake.admit("chat", id, "running", "turn"); fake.admit("second", other, "running", "second-turn")
            val api = fake.api()
            try {
                val h = Harness(api, local); h.coordinator.resync()
                coroutineScope { repeat(4) { launch(Dispatchers.Default) { h.coordinator.round() } } }
                assertEquals(1, fake.maxHeld)
                assertEquals(2, h.local.watches.size)
                val requests = generateSequence { fake.entered.poll() }.toList()
                assertEquals(listOf("0", "0"), requests.take(2).map { it.requestUrl!!.queryParameter("waitSeconds") })
                assertTrue(requests.drop(2).all { it.requestUrl!!.queryParameter("waitSeconds") == "1" })
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun processRecreationRestoresEncryptedWatchAndOutboxReplayPostsOnce() = runBlocking {
        PromptOutcomesServer().use { fake ->
            val record = MemoryWatchRecord()
            val cipher = StateCipher { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
            var store = NotificationStore(record, cipher)
            store.update { it.registerWatch("device", "chat", id, 100) }
            assertFalse(record.bytes!!.toString(Charsets.UTF_8).contains(id))
            store = NotificationStore(record, cipher)
            fake.admit("chat", id, "completed", "turn")
            val api = fake.api(); val visible = mutableSetOf<String>(); var audible = 0; var crash = true
            fun coordinator() = WatchCoordinator(store::read, store::update, api::promptOutcomes, { alert, replay ->
                visible += watchNotificationTag("device", alert.sessionId); if (!replay) audible++
                if (crash) throw IOException("Synthetic crash after notify")
            }, { 101 })
            try {
                try { coordinator().resync(); fail("expected crash") } catch (_: IOException) {}
                store = NotificationStore(record, cipher); assertEquals(1, store.read().outbox.size)
                crash = false; coordinator().drain(); coordinator().resync()
                assertEquals(1, visible.size); assertEquals(1, audible)
                assertTrue(store.read().outbox.isEmpty()); assertTrue(store.read().watches.isEmpty())
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun capabilityAbsentDiscardsRestoredWatchesWithoutOutcomeHttpOrPosts() = runBlocking {
        PromptOutcomesServer().use { fake ->
            fake.capability = null; val api = fake.api()
            try {
                val h = Harness(api, watch())
                WatchPollLoop(h::update, h.coordinator, { api.capabilities().capabilities.promptOutcomes }, { true }, { 101 }).run()
                assertTrue(h.local.watches.isEmpty()); assertTrue(h.posts.isEmpty())
                assertEquals(1, fake.server.requestCount); assertEquals("/v1/capabilities", fake.next().path)
            } finally { api.closeAndAwait() }
        }
    }
    @Test fun watch33And24HourExpiryBoundRequestedIdsAndNeverPostExpiredOutcome() = runBlocking {
        PromptOutcomesServer().use { fake ->
            var local = NotificationLocal()
            repeat(33) { local = local.registerWatch("device", "chat", "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}", 100) }
            val api = fake.api()
            try {
                val h = Harness(api, local); h.coordinator.resync()
                val ids = fake.next().requestUrl!!.queryParameterValues("requestId")
                assertEquals(32, ids.size); assertFalse(ids.contains("00000000-0000-4000-8000-000000000000"))
                val expired = Harness(api, h.local) { 100 + WATCH_TTL_MS }; expired.coordinator.resync()
                assertTrue(expired.local.watches.isEmpty()); assertTrue(expired.posts.isEmpty()); assertEquals(1, fake.server.requestCount)
            } finally { api.closeAndAwait() }
        }
    }
}
internal class MemoryWatchRecord : AtomicStateFile {
    var bytes: ByteArray? = null
    override val identity = "synthetic/outcomes-${java.util.UUID.randomUUID()}.enc"
    override fun openRead() = ByteArrayInputStream(bytes ?: throw FileNotFoundException())
    override fun startWrite() = ByteArrayOutputStream()
    override fun finishWrite(stream: OutputStream) { bytes = (stream as ByteArrayOutputStream).toByteArray() }
    override fun failWrite(stream: OutputStream?) = Unit
    override fun delete() { bytes = null }
}
