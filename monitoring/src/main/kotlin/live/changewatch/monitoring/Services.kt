package live.changewatch.monitoring

import live.changewatch.core.*
import java.time.Clock
import java.util.UUID

class MonitoringService(private val uow: UnitOfWork, private val clock: Clock) {
    fun getSource(id: UUID): Source = uow.transaction { it.sources.get(id) ?: throw Missing("Source not found") }
    fun source(spreadsheetId: String, sheetId: Long, interval: Int): Source {
        if (!spreadsheetId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) throw InvalidInput("Invalid spreadsheetId")
        if (sheetId < 0) throw InvalidInput("sheetId must be non-negative")
        if (interval !in 30..86400) throw InvalidInput("intervalSeconds must be 30..86400")
        return uow.transaction {
            it.sources.createOrGet(
                Source(
                    UUID.randomUUID(),
                    spreadsheetId,
                    sheetId,
                    interval,
                    clock.instant(),
                    null,
                    null
                )
            )
        }
    }

    fun create(
        owner: UUID,
        source: UUID,
        name: String,
        key: String,
        value: String,
        columns: List<String>,
        rule: Rule
    ): Monitor {
        if (listOf(name, key, value).any { it.isBlank() || it.length > 200 })
            throw InvalidInput("name, rowKey, rowValue must contain 1..200 characters")
        if (columns.size !in 1..50 || columns.distinct().size != columns.size || columns.any { it.isBlank() || it.length > 200 || it != it.trim() })
            throw InvalidInput("columns must contain 1..50 unique trimmed non-empty names")
        val monitor = Monitor(UUID.randomUUID(), owner, source, name.trim(), key.trim(), value.trim(), columns, rule)
        return uow.transaction { repos ->
            repos.sources.lock(source) ?: throw Missing("Source not found")
            repos.monitors.create(monitor)
            monitor
        }
    }

    fun get(owner: UUID, id: UUID) = uow.transaction { it.monitors.get(owner, id) ?: throw Missing() }
    fun list(owner: UUID, limit: Int, offset: Int) = page(limit, offset) {
        uow.transaction { it.monitors.list(owner, limit, offset) }
    }

    fun active(owner: UUID, id: UUID, active: Boolean): Monitor = uow.transaction { repos ->
        val previous = repos.monitors.get(owner, id) ?: throw Missing()
        repos.sources.lock(previous.sourceId) ?: throw Missing()
        val current = repos.monitors.get(owner, id) ?: throw Missing()
        val updated = current.copy(active = active)
        repos.monitors.update(updated)
        updated
    }

    fun delete(owner: UUID, id: UUID) = uow.transaction { repos ->
        val monitor = repos.monitors.get(owner, id) ?: throw Missing()
        repos.sources.lock(monitor.sourceId) ?: throw Missing()
        if (!repos.monitors.delete(owner, id)) throw Missing()
        true
    }

    fun history(owner: UUID, id: UUID, limit: Int, offset: Int, matchedOnly: Boolean) = page(limit, offset) {
        uow.transaction { repos ->
            repos.monitors.get(owner, id) ?: throw Missing()
            repos.monitors.history(id, limit, offset, matchedOnly)
        }
    }

    private fun <T> page(limit: Int, offset: Int, block: () -> T): T {
        if (limit !in 1..100 || offset !in 0..1_000_000) throw InvalidInput("limit must be 1..100; offset 0..1000000")
        return block()
    }
}

class CheckService(private val uow: UnitOfWork, private val fetcher: SheetFetcher, private val clock: Clock) {
    fun due(): List<UUID> = uow.transaction { it.sources.due(clock.instant(), 20) }
    fun checkFor(owner: UUID, monitor: UUID): CheckResult {
        val source = uow.transaction { it.monitors.get(owner, monitor)?.sourceId ?: throw Missing() }
        return check(source)
    }

    fun check(id: UUID): CheckResult {
        val outcome = uow.transaction { repos ->
            val source = repos.sources.lock(id) ?: throw Missing("Source not found")
            val now = clock.instant()
            if (source.nextCheckAt > now) throw Conflict("Source is not due; retry after ${source.nextCheckAt}")
            val table = try {
                fetcher.fetch(source)
            } catch (e: UpstreamFailure) {
                repos.sources.failure(id, e.message ?: "Fetch failed", now.plusSeconds(source.intervalSeconds.toLong()))
                return@transaction Result.failure<CheckResult>(e)
            }
            val at = clock.instant()
            var changes = 0
            var errors = 0
            repos.monitors.forSource(id).forEach { monitor ->
                val values = try {
                    table.select(monitor)
                } catch (e: InvalidInput) {
                    repos.monitors.update(monitor.copy(lastError = e.message))
                    errors++
                    return@forEach
                }
                val detected = ChangeDetector.detect(monitor, values, at)
                repos.monitors.append(detected)
                repos.monitors.update(monitor.copy(initialized = true, values = values, lastError = null))
                changes += detected.size
            }
            repos.sources.success(id, at, at.plusSeconds(source.intervalSeconds.toLong()))
            Result.success(CheckResult(id, at, changes, errors))
        }
        return outcome.getOrThrow()
    }
}
