package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSummary
import dev.dshmobile.app.model.SessionSnapshot
import dev.dshmobile.app.ui.home.sessionWorkLabel
import org.junit.Assert.*
import org.junit.Test

class SubagentActivityTest {
    private val base = """{"id":"chat","title":"Synthetic","workspaceId":"project","updatedAt":1,"running":false,"canExecute":true}"""
    @Test fun `additive counter is absent zero positive or tolerantly ignored`() {
        for ((value, count) in listOf(null to 0, "0" to 0, "3" to 3, "-2" to 0, "null" to 0, "\"unknown\"" to 0, "{}" to 0)) {
            val json = if (value == null) base else base.dropLast(1) + ",\"runningSubagents\":$value}"
            assertEquals(count, decodeWire<SessionSummary>(json).checked().runningSubagents)
            assertEquals(count, decodeWire<SessionSnapshot>("""{"session":$json,"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}""").checked("chat").session.runningSubagents)
            assertEquals(count, decodeWire<SessionSnapshot>("""{"session":$base,"messages":[],"cursor":1,"hasMore":false,"activity":"idle","runningSubagents":${value ?: "0"}}""").checked("chat").session.runningSubagents)
        }
    }
    @Test fun `list work label distinguishes main and subagent only activity`() {
        val session = decodeWire<SessionSummary>(base)
        fun label(s: SessionSummary) = sessionWorkLabel(s, "работает", "субагенты работают · ${s.runningSubagents}", "субагентов: ${s.runningSubagents}")
        assertNull(label(session))
        assertEquals("работает", label(session.copy(running = true)))
        assertEquals("субагенты работают · 3", label(session.copy(runningSubagents = 3)))
        assertEquals("работает · субагентов: 3", label(session.copy(running = true, runningSubagents = 3)))
    }
    @Test fun `question waiting takes precedence over main and delegated work labels`() {
        val waiting = decodeWire<SessionSummary>(base).copy(running = true, runningSubagents = 2, waitingForAnswer = true)
        assertEquals("Ждёт ответа", sessionWorkLabel(waiting, "Работает", "Субагенты", "2", "Ждёт ответа"))
    }

}
