package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

/** Boundary checks for the proposed additive field, not proof of host implementation. */
class PhoneOutcomeWireTest {
    private val request = "00000000-0000-4000-8000-000000000001"
    private fun cut(outcomes: List<PromptOutcome> = emptyList()) = SessionSnapshot(
        SessionSummary("чат 😀", "Synthetic", "w", 0, false, true), emptyList(), -1, false, "idle", promptOutcomes = outcomes)
    private fun checked(snapshot: SessionSnapshot) = decodeWire<SessionSnapshot>(mobileJson.encodeToString(SessionSnapshot.serializer(), snapshot)).checked("чат 😀")
    @Test fun emptyAndOneElementUnicodeOutcomesRoundTrip() {
        assertTrue(checked(cut()).promptOutcomes.isEmpty())
        val outcome = PromptOutcome(request, "ход 😀 é", "failed", "Ошибка 😀")
        assertEquals(listOf(outcome), checked(cut(listOf(outcome))).promptOutcomes)
        assertEquals("E".repeat(2048), checked(cut(listOf(outcome.copy(summary = "E".repeat(2048))))).promptOutcomes.single().summary)
    }
    @Test fun duplicateWrongTypeAndInvalidBoundaryValuesFailClosed() {
        val valid = PromptOutcome(request, "turn", "completed")
        val malformed = listOf(listOf(valid, valid), listOf(valid.copy(requestId = "１２")),
            listOf(valid.copy(turnId = "")), listOf(valid.copy(summary = "E".repeat(2049))),
            listOf(valid.copy(status = "unknown")))
        for (items in malformed) try { checked(cut(items)); fail("must reject malformed outcome") } catch (_: MobileFailure) {}
        for (control in listOf("\n", "\r\n", "\r", "\u0000")) try { checked(cut(listOf(valid.copy(summary = "Synthetic${control}value")))); fail("must reject controls") } catch (_: MobileFailure) {}
        try { decodeWire<SessionSnapshot>("""{"promptOutcomes":3}"""); fail("wrong type") } catch (_: MobileFailure) {}
    }
}
