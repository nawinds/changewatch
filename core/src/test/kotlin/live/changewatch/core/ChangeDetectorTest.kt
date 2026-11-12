package live.changewatch.core

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ChangeDetectorTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val monitor = Monitor(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Labs", "Student", "Nikita",
        listOf("Lab 1"), Rule.ANY_CHANGE, initialized = true, values = mapOf("Lab 1" to null)
    )

    @Test
    fun `first snapshot establishes baseline without events`() {
        assertTrue(ChangeDetector.detect(monitor.copy(initialized = false), mapOf("Lab 1" to "8"), now).isEmpty())
    }

    @Test
    fun `unchanged snapshot has no events`() {
        assertTrue(ChangeDetector.detect(monitor, monitor.values, now).isEmpty())
    }

    @Test
    fun `appeared changed and removed values keep before after and time`() {
        val appeared = ChangeDetector.detect(monitor, mapOf("Lab 1" to "8"), now).single()
        assertEquals(ChangeType.VALUE_APPEARED, appeared.type)
        assertNull(appeared.oldValue)
        assertEquals("8", appeared.newValue)
        assertEquals(now, appeared.detectedAt)
        val baseline = monitor.copy(values = mapOf("Lab 1" to "8"))
        assertEquals(
            ChangeType.VALUE_CHANGED,
            ChangeDetector.detect(baseline, mapOf("Lab 1" to "9"), now).single().type
        )
        assertEquals(
            ChangeType.VALUE_REMOVED,
            ChangeDetector.detect(baseline, mapOf("Lab 1" to null), now).single().type
        )
    }

    @Test
    fun `numeric rules compare decimals instead of strings`() {
        val baseline = monitor.copy(rule = Rule.INCREASED, values = mapOf("Lab 1" to "9.5"))
        assertTrue(ChangeDetector.detect(baseline, mapOf("Lab 1" to "10"), now).single().matchesRule)
        assertFalse(ChangeDetector.detect(baseline, mapOf("Lab 1" to "8"), now).single().matchesRule)
        assertFalse(ChangeDetector.detect(baseline, mapOf("Lab 1" to "10 of 10"), now).single().matchesRule)
        assertTrue(
            ChangeDetector.detect(baseline.copy(rule = Rule.DECREASED), mapOf("Lab 1" to "8"), now).single().matchesRule
        )
    }

    @Test
    fun `appeared rule keeps history even when rule does not match`() {
        val baseline = monitor.copy(rule = Rule.VALUE_APPEARED, values = mapOf("Lab 1" to "8"))
        assertFalse(ChangeDetector.detect(baseline, mapOf("Lab 1" to "9"), now).single().matchesRule)
    }

    @Test
    fun `logical key survives row and column reordering and normalizes empty cells`() {
        val table = Table(listOf("Lab 1", "Student"), listOf(listOf("10", "Other"), listOf(" — ", "Nikita")))
        assertEquals(mapOf("Lab 1" to null), table.select(monitor))
        assertEquals(
            mapOf("Lab 1" to "8"),
            table.copy(rows = listOf(listOf("8", "Nikita"), listOf("10", "Other"))).select(monitor)
        )
    }

    @Test
    fun `ambiguous missing row or missing header is an error not a removal`() {
        assertFailsWith<InvalidInput> { Table(listOf("Student", "Lab 1"), emptyList()).select(monitor) }
        assertFailsWith<InvalidInput> {
            Table(
                listOf("Student", "Lab 1"),
                listOf(listOf("Nikita", "8"), listOf("Nikita", "9"))
            ).select(monitor)
        }
        assertFailsWith<InvalidInput> { Table(listOf("Student"), listOf(listOf("Nikita"))).select(monitor) }
    }
}
