package dev.dshmobile.app.ui.chat

import dev.dshmobile.app.data.decodeWire
import dev.dshmobile.app.model.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChatPresentationTest {
    @Serializable private data class Vector(val name: String, val text: String, val kind: String, val cleanText: String? = null)
    private fun message(id: String, text: String, kind: String? = null, role: String = "user") = ChatMessage(id, role, text, 100, kind = kind)

    @Serializable private data class Adversarial(val name: String, val prefix: String, val fragment: String, val ending: String)

    @Test fun `job delimiter failure finishes within budget`() {
        val text = "background job j (" + ") finished ".repeat(200_000 / 11)
        val start = System.nanoTime(); val result = presentMessage(message("job", text))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("job delimiter took ${millis}ms", millis < 500)
        assertEquals(text, result.text)
    }

    @Test(timeout = 20000) fun `every classifier family handles two MiB repeated literal tokens within budget`() {
        val vectors = Json.decodeFromString<List<Adversarial>>(File("../../fixtures/classifier-adversarial.json").readText())
        vectors.forEach { vector ->
            val text = vector.prefix + vector.fragment.repeat((2 * 1024 * 1024 - vector.prefix.length - vector.ending.length) / vector.fragment.length) + vector.ending
            val start = System.nanoTime(); val result = presentMessage(message(vector.name, text))
            val millis = (System.nanoTime() - start) / 1_000_000.0
            println("${vector.name}: ${millis}ms")
            assertTrue("${vector.name} exceeded one second", millis < 1000)
            assertEquals(text, result.text + result.serviceText.orEmpty())
        }
    }

    @Test fun `legacy host fallback matches shared redacted journal shapes`() {
        val vectors = Json.decodeFromString<List<Vector>>(File("../../fixtures/message-noise.json").readText())
        vectors.forEach { vector ->
            val result = presentMessage(message(vector.name, vector.text))
            assertEquals(vector.name, vector.kind, result.kind ?: "message")
            assertEquals(vector.name, vector.cleanText ?: vector.text, result.text)
        }
    }

    @Test(timeout = 5000) fun `two MiB adversarial messages preserve original and finish in one second`() {
        for (unit in listOf("\n\n<system-reminder>", "\n\n<system-reminder>x</system-reminder>")) {
            val text = "Human" + unit.repeat((2 * 1024 * 1024 - 5) / unit.length)
            val started = System.nanoTime()
            val result = presentMessage(message("adversarial", text))
            assertTrue("Linear classification budget", (System.nanoTime() - started) / 1_000_000 < 1000)
            assertEquals(text, result.text + result.serviceText.orEmpty())
        }
    }

    @Test fun `timeline never publishes old or equal-copy inputs during replacement and clearing`() {
        val old = listOf(message("old", "Obsolete answer", role = "assistant"))
        val projection = TimelineProjection(old, chatItems(old))
        assertSame(projection.items, visibleTimeline(old, projection))
        assertNull(visibleTimeline(listOf(message("new", "Replacement", role = "assistant")), projection))
        assertNull(visibleTimeline(old.toList(), projection))
        assertEquals(emptyList<ChatItem>(), visibleTimeline(emptyList(), projection))
        assertNull(visibleTimeline(old, null))
    }

    @Test fun `mixed legacy suffix stays available and explicit host human XML stays whole`() {
        val text = "Please review XML:\n\n<system-reminder>Human example</system-reminder>"
        val legacy = presentMessage(message("mixed", text))
        assertEquals(text, legacy.text + legacy.serviceText.orEmpty())
        assertEquals(text, presentMessage(message("human", text, "message")).text)
        assertTrue(chatItems(listOf(message("mixed", text))).any { it is ChatItem.Activity })
    }

    @Test fun `group identity survives append and overlapping window truncation`() {
        val first = chatItems(listOf(message("u", "Human"), message("e0", "Event", "agent_event"), message("e1", "Event", "agent_event")))
        val appended = chatItems(listOf(message("u", "Human"), message("e0", "Event", "agent_event"), message("e1", "Event", "agent_event"), message("e2", "Event", "agent_event")), first)
        val truncated = chatItems(listOf(message("e1", "Event", "agent_event"), message("e2", "Event", "agent_event")), appended)
        assertEquals(first.last().id, appended.last().id)
        assertEquals(first.last().id, truncated.single().id)
        assertEquals(truncated.single().id, chatItems(listOf(message("e2", "Event", "agent_event")), truncated).single().id)
        val leading = chatItems(listOf(message("e0", "Event", "agent_event"), message("e1", "Event", "agent_event")))
        assertEquals(leading.single().id, chatItems(listOf(message("e1", "Event", "agent_event"))).single().id)
    }

    @Test fun `large legacy service blocks stay bounded and do not overflow regex stack`() {
        val text = "Question.\n\n<system-reminder>" + "x".repeat(100_000) + "</system-reminder>"
        assertEquals("Question.", presentMessage(message("large", text)).text)
    }

    @Test fun `host classification wins and assistant quotations stay intact`() {
        val text = "Agent worker-1 sent a message: example"
        assertEquals("message", presentMessage(message("1", text, "message")).kind)
        assertEquals(text, presentMessage(message("2", text, role = "assistant")).text)
        assertNull(presentMessage(message("2", text, role = "assistant")).kind)
        assertEquals("future-kind", presentMessage(message("3", text, "future-kind")).kind)
    }

    @Test fun `consecutive service messages collapse without crossing conversation boundaries`() {
        val items = chatItems(listOf(message("u", "Question"), message("e1", "event", "agent_event"),
            message("c", "private context", "context"), message("e2", "event", "agent_event"), message("a", "Answer", role = "assistant"),
            message("c2", "context", "context"), message("u2", "Next")))
        assertEquals(5, items.size)
        val group = items[1] as ChatItem.Activity
        assertEquals("after:u", group.id)
        assertEquals(listOf("e1", "c", "e2"), group.messages.map { it.id })
        assertEquals("a", (items[2] as ChatItem.Message).message.id)
        assertEquals("after:a", items[3].id)
    }

    @Test fun `activity start prefers host turn and legacy fallback ignores synthetic user times`() {
        val session = dev.dshmobile.app.model.SessionSummary("s", "Title", "w", 100, true, true)
        val snapshot = dev.dshmobile.app.model.SessionSnapshot(session, listOf(message("human", "Question"),
            message("noise", "Agent worker-1 sent a message: result").copy(createdAt = 200)), 1, false, "running")
        assertEquals(100L, activityStartedAt(snapshot))
        assertEquals(50L, activityStartedAt(snapshot.copy(activityDetail = dev.dshmobile.app.model.ActivityDetail(50, "read"))))
    }

    @Test fun `wire extensions are optional and unknown fields remain compatible`() {
        val old = decodeWire<ChatMessage>("""{"id":"1","role":"user","text":"Human","createdAt":1}""")
        assertNull(old.kind)
        val new = decodeWire<ChatMessage>("""{"id":"1","role":"user","text":"Context","createdAt":1,"kind":"context","future":true}""")
        assertEquals("context", new.kind)
        val mixed = decodeWire<ChatMessage>("""{"id":"1","role":"user","text":"Human","createdAt":1,"kind":"message","serviceText":"Full suffix"}""")
        assertEquals("Full suffix", mixed.serviceText)
        assertEquals("Full suffix", (chatItems(listOf(mixed)).last() as ChatItem.Activity).messages.single().text)
    }
}
