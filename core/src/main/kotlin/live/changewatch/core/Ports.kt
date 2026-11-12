package live.changewatch.core

import java.time.Instant
import java.util.UUID

interface UnitOfWork {
    fun <T : Any> transaction(block: (Repositories) -> T): T
}

interface Repositories {
    val accounts: Accounts
    val sources: Sources
    val monitors: Monitors
}

interface Accounts {
    fun create(account: Account, tokenHash: String)
    fun byTokenHash(hash: String): Account?
}

interface Sources {
    fun createOrGet(source: Source): Source
    fun get(id: UUID): Source?

    fun lock(id: UUID): Source?
    fun due(now: Instant, limit: Int): List<UUID>
    fun success(id: UUID, checkedAt: Instant, nextCheck: Instant)
    fun failure(id: UUID, error: String, nextCheck: Instant)
}

interface Monitors {
    fun create(monitor: Monitor)
    fun get(owner: UUID, id: UUID): Monitor?
    fun list(owner: UUID, limit: Int, offset: Int): List<Monitor>
    fun forSource(source: UUID): List<Monitor>
    fun update(monitor: Monitor)
    fun delete(owner: UUID, id: UUID): Boolean
    fun append(changes: List<Change>)
    fun history(monitor: UUID, limit: Int, offset: Int, matchedOnly: Boolean): List<Change>
}

fun interface SheetFetcher {
    fun fetch(source: Source): Table
}
