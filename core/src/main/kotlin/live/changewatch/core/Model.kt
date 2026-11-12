package live.changewatch.core

import java.time.Instant
import java.util.UUID

class InvalidInput(message: String) : RuntimeException(message)
class Missing(message: String = "Resource not found") : RuntimeException(message)
class Conflict(message: String) : RuntimeException(message)
class UpstreamFailure(message: String) : RuntimeException(message)
class Unauthenticated : RuntimeException("Valid Bearer token required")

data class Account(val id: UUID, val name: String)
data class Credentials(val account: Account, val token: String)
data class Source(
    val id: UUID, val spreadsheetId: String, val sheetId: Long, val intervalSeconds: Int,
    val nextCheckAt: Instant, val lastCheckedAt: Instant?, val lastError: String?
)

enum class Rule { ANY_CHANGE, VALUE_APPEARED, INCREASED, DECREASED }
enum class ChangeType { VALUE_APPEARED, VALUE_REMOVED, VALUE_CHANGED }
data class Monitor(
    val id: UUID, val ownerId: UUID, val sourceId: UUID, val name: String,
    val rowKey: String, val rowValue: String, val columns: List<String>, val rule: Rule,
    val active: Boolean = true, val initialized: Boolean = false,
    val values: Map<String, String?> = emptyMap(), val lastError: String? = null
)

data class Change(
    val id: UUID, val monitorId: UUID, val column: String, val oldValue: String?,
    val newValue: String?, val type: ChangeType, val matchesRule: Boolean, val detectedAt: Instant
)

data class Table(val headers: List<String>, val rows: List<List<String>>) {
    fun select(monitor: Monitor): Map<String, String?> {
        val key = headers.indexOf(monitor.rowKey)
        if (key < 0 || !headers.containsAll(monitor.columns))
            throw InvalidInput("Row key or watched column missing in sheet")
        val matching = rows.filter { it.getOrNull(key)?.trim() == monitor.rowValue }
        if (matching.size != 1) throw InvalidInput("Expected exactly one row for rowValue; found ${matching.size}")
        return monitor.columns.associateWith { column ->
            matching.single().getOrNull(headers.indexOf(column))?.trim()
                ?.takeUnless { it in setOf("", "—", "-") }
        }
    }
}

data class CheckResult(val sourceId: UUID, val checkedAt: Instant, val changes: Int, val monitorErrors: Int)

object ChangeDetector {
    fun detect(monitor: Monitor, values: Map<String, String?>, at: Instant): List<Change> {
        if (!monitor.initialized) return emptyList()
        return values.mapNotNull { (column, value) ->
            val old = monitor.values[column]
            if (old == value) return@mapNotNull null
            val type = when {
                old == null -> ChangeType.VALUE_APPEARED
                value == null -> ChangeType.VALUE_REMOVED
                else -> ChangeType.VALUE_CHANGED
            }
            val comparison = if (old != null && value != null)
                value.toBigDecimalOrNull()?.let { new -> old.toBigDecimalOrNull()?.let(new::compareTo) }
            else null
            val matches = when (monitor.rule) {
                Rule.ANY_CHANGE -> true
                Rule.VALUE_APPEARED -> type == ChangeType.VALUE_APPEARED
                Rule.INCREASED -> comparison != null && comparison > 0
                Rule.DECREASED -> comparison != null && comparison < 0
            }
            Change(UUID.randomUUID(), monitor.id, column, old, value, type, matches, at)
        }
    }
}
