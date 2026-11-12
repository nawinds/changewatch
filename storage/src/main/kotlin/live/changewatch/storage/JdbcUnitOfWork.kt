package live.changewatch.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import live.changewatch.core.*
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class JdbcUnitOfWork(
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val json: ObjectMapper
) : UnitOfWork {
    override fun <T : Any> transaction(block: (Repositories) -> T): T =
        transactions.execute { block(JdbcRepositories(jdbc, json)) }!!
}

private class JdbcRepositories(private val jdbc: JdbcTemplate, private val json: ObjectMapper) : Repositories {
    override val accounts = object : Accounts {
        override fun create(account: Account, tokenHash: String) {
            jdbc.update(
                "INSERT INTO accounts(id, name, token_hash) VALUES (?, ?, ?)",
                account.id,
                account.name,
                tokenHash
            )
        }

        override fun byTokenHash(hash: String): Account? = jdbc.query(
            "SELECT id, name FROM accounts WHERE token_hash = ?",
            { rs, _ -> Account(rs.uuid("id"), rs.getString("name")) },
            hash
        ).firstOrNull()
    }
    override val sources = object : Sources {
        override fun createOrGet(source: Source): Source {
            jdbc.update(
                """INSERT INTO sources(id, spreadsheet_id, sheet_id, interval_seconds, next_check_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (spreadsheet_id, sheet_id) DO NOTHING""",
                source.id,
                source.spreadsheetId,
                source.sheetId,
                source.intervalSeconds,
                Timestamp.from(source.nextCheckAt)
            )
            return jdbc.query(
                "SELECT * FROM sources WHERE spreadsheet_id = ? AND sheet_id = ?",
                { rs, _ -> source(rs) }, source.spreadsheetId, source.sheetId
            ).single()
        }

        override fun get(id: UUID) =
            jdbc.query("SELECT * FROM sources WHERE id = ?", { rs, _ -> source(rs) }, id).firstOrNull()

        override fun lock(id: UUID): Source? = try {
            jdbc.query("SELECT * FROM sources WHERE id = ? FOR UPDATE NOWAIT", { rs, _ -> source(rs) }, id)
                .firstOrNull()
        } catch (e: DataAccessException) {
            val sqlState = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
            if (e is CannotAcquireLockException || sqlState == "55P03")
                throw Conflict("Source is busy; retry later")
            throw e
        }

        override fun due(now: Instant, limit: Int): List<UUID> = jdbc.query(
            """
            SELECT s.id FROM sources s WHERE s.next_check_at <= ?
            AND EXISTS (SELECT 1 FROM monitors m WHERE m.source_id = s.id AND m.active)
            ORDER BY s.next_check_at, s.id LIMIT ?
        """, { rs, _ -> rs.uuid("id") }, Timestamp.from(now), limit
        )

        override fun success(id: UUID, checkedAt: Instant, nextCheck: Instant) {
            jdbc.update(
                """UPDATE sources SET last_checked_at = ?, next_check_at = ?,
                last_error = NULL WHERE id = ?""",
                Timestamp.from(checkedAt),
                Timestamp.from(nextCheck),
                id
            )
        }

        override fun failure(id: UUID, error: String, nextCheck: Instant) {
            jdbc.update(
                "UPDATE sources SET last_error = ?, next_check_at = ? WHERE id = ?",
                error,
                Timestamp.from(nextCheck),
                id
            )
        }
    }
    override val monitors = object : Monitors {
        override fun create(monitor: Monitor) {
            jdbc.update(
                """INSERT INTO monitors(id, owner_id, source_id, name, row_key, row_value, columns, rule)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
                monitor.id,
                monitor.ownerId,
                monitor.sourceId,
                monitor.name,
                monitor.rowKey,
                monitor.rowValue,
                json.writeValueAsString(monitor.columns),
                monitor.rule.name
            )
        }

        override fun get(owner: UUID, id: UUID) = jdbc.query(
            "SELECT * FROM monitors WHERE id = ? AND owner_id = ?",
            { rs, _ -> monitor(rs) }, id, owner
        ).firstOrNull()

        override fun list(owner: UUID, limit: Int, offset: Int): List<Monitor> = jdbc.query(
            "SELECT * FROM monitors WHERE owner_id = ? ORDER BY created_at, id LIMIT ? OFFSET ?",
            { rs, _ -> monitor(rs) }, owner, limit, offset
        )

        override fun forSource(source: UUID): List<Monitor> = jdbc.query(
            "SELECT * FROM monitors WHERE source_id = ? AND active ORDER BY id", { rs, _ -> monitor(rs) }, source
        )

        override fun update(monitor: Monitor) {
            jdbc.update(
                "UPDATE monitors SET active = ?, initialized = ?, values = ?::jsonb, last_error = ? WHERE id = ?",
                monitor.active,
                monitor.initialized,
                json.writeValueAsString(monitor.values),
                monitor.lastError,
                monitor.id
            )
        }

        override fun delete(owner: UUID, id: UUID) =
            jdbc.update("DELETE FROM monitors WHERE id = ? AND owner_id = ?", id, owner) == 1

        override fun append(changes: List<Change>) {
            changes.forEach { change ->
                jdbc.update(
                    """INSERT INTO changes(id, monitor_id, column_name, old_value, new_value, type, matches_rule, detected_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    change.id,
                    change.monitorId,
                    change.column,
                    change.oldValue,
                    change.newValue,
                    change.type.name,
                    change.matchesRule,
                    Timestamp.from(change.detectedAt)
                )
            }
        }

        override fun history(monitor: UUID, limit: Int, offset: Int, matchedOnly: Boolean): List<Change> = jdbc.query(
            """SELECT * FROM changes WHERE monitor_id = ? AND (NOT ? OR matches_rule)
                ORDER BY detected_at DESC, id DESC LIMIT ? OFFSET ?""", { rs, _ ->
                Change(
                    rs.uuid("id"),
                    rs.uuid("monitor_id"),
                    rs.getString("column_name"),
                    rs.getString("old_value"),
                    rs.getString("new_value"),
                    ChangeType.valueOf(rs.getString("type")),
                    rs.getBoolean("matches_rule"),
                    rs.instant("detected_at")!!
                )
            }, monitor, matchedOnly, limit, offset
        )
    }

    private fun source(rs: ResultSet) = Source(
        rs.uuid("id"),
        rs.getString("spreadsheet_id"),
        rs.getLong("sheet_id"),
        rs.getInt("interval_seconds"),
        rs.instant("next_check_at")!!,
        rs.instant("last_checked_at"),
        rs.getString("last_error")
    )

    private fun monitor(rs: ResultSet) = Monitor(
        rs.uuid("id"),
        rs.uuid("owner_id"),
        rs.uuid("source_id"),
        rs.getString("name"),
        rs.getString("row_key"),
        rs.getString("row_value"),
        json.readValue(rs.getString("columns"), object : TypeReference<List<String>>() {}),
        Rule.valueOf(rs.getString("rule")),
        rs.getBoolean("active"),
        rs.getBoolean("initialized"),
        json.readValue(rs.getString("values"), object : TypeReference<Map<String, String?>>() {}),
        rs.getString("last_error")
    )

    private fun ResultSet.uuid(name: String) = getObject(name, UUID::class.java)
    private fun ResultSet.instant(name: String): Instant? = getTimestamp(name)?.toInstant()
}
