package live.changewatch.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import live.changewatch.core.*
import live.changewatch.monitoring.CheckService
import live.changewatch.monitoring.MonitoringService
import live.changewatch.storage.JdbcUnitOfWork
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MutableClock : Clock() {
    @Volatile
    var now: Instant = Instant.parse("2026-10-22T12:00:00Z")
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance() {
        now = now.plusSeconds(61)
    }
}

class ControlledFetcher : SheetFetcher {
    @Volatile
    var table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "—")))
    @Volatile
    var failure: UpstreamFailure? = null
    @Volatile
    var entered: CountDownLatch? = null
    @Volatile
    var release: CountDownLatch? = null
    val calls = AtomicInteger()
    override fun fetch(source: Source): Table {
        calls.incrementAndGet()
        entered?.countDown()
        check(release?.await(10, TimeUnit.SECONDS) != false) { "Test fetch timed out" }
        failure?.let { throw it }
        return table
    }
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["changewatch.scheduler.enabled=false"]
)
@Import(MonitoringIntegrationTest.Fixtures::class)
class MonitoringIntegrationTest {
    companion object {
        private val postgres = PostgreSQLContainer<Nothing>("postgres:17.6-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean
        @Primary
        fun testClock() = MutableClock()
        @Bean
        @Primary
        fun testFetcher() = ControlledFetcher()
    }

    @Autowired
    lateinit var http: TestRestTemplate
    @Autowired
    lateinit var json: ObjectMapper
    @Autowired
    lateinit var jdbc: JdbcTemplate
    @Autowired
    lateinit var clock: MutableClock
    @Autowired
    lateinit var fetcher: ControlledFetcher
    @Autowired
    lateinit var uow: UnitOfWork
    @Autowired
    lateinit var checks: CheckService
    @Autowired
    lateinit var manager: PlatformTransactionManager

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE accounts, sources, monitors, changes CASCADE")
        clock.now = Instant.parse("2026-10-22T12:00:00Z")
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "—")))
        fetcher.failure = null
        fetcher.entered = null
        fetcher.release = null
        fetcher.calls.set(0)
    }

    private fun call(
        method: HttpMethod,
        path: String,
        token: String? = null,
        body: Any? = null
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            if (token != null) setBearerAuth(token)
        }
        return http.exchange("/api/v1$path", method, HttpEntity(body, headers), String::class.java)
    }

    private fun node(response: ResponseEntity<String>, status: Int = 200): JsonNode {
        assertEquals(status, response.statusCode.value(), response.body)
        return json.readTree(response.body)
    }

    private fun register(name: String = "Nikita") =
        node(call(HttpMethod.POST, "/accounts", body = mapOf("name" to name)), 201)["token"].asText()

    private fun source(token: String) = node(
        call(
            HttpMethod.POST, "/sources", token,
            mapOf("spreadsheetId" to "a_public_sheet_identifier", "sheetId" to 0, "intervalSeconds" to 60)
        )
    )["id"].asText()

    private fun monitor(token: String, source: String, rule: String = "ANY_CHANGE", row: String = "Nikita") =
        node(
            call(
                HttpMethod.POST, "/monitors", token, mapOf(
                    "sourceId" to source, "name" to "Labs", "rowKey" to "Student",
                    "rowValue" to row, "columns" to listOf("Lab 1"), "rule" to rule
                )
            ), 201
        )["id"].asText()

    private fun history(token: String, monitor: String, query: String = "") =
        node(call(HttpMethod.GET, "/monitors/$monitor/changes$query", token))

    @Test
    fun `HTTP flow shares fetches persists history applies rules and survives fresh service instance`() {
        val token = register()
        val other = register("Other")
        val source = source(token)
        assertEquals(source, source(other))
        val first = monitor(token, source)
        val second = monitor(other, source, "VALUE_APPEARED")
        assertEquals(0, node(call(HttpMethod.POST, "/monitors/$first/check", token))["changes"].asInt())
        assertEquals(1, fetcher.calls.get())
        assertTrue(node(call(HttpMethod.GET, "/monitors/$second", other))["initialized"].asBoolean())
        assertEquals(409, call(HttpMethod.POST, "/monitors/$second/check", other).statusCode.value())
        clock.advance()
        fetcher.table = Table(listOf("Lab 1", "Student"), listOf(listOf("10", "Someone"), listOf("8", "Nikita")))
        assertEquals(2, node(call(HttpMethod.POST, "/monitors/$first/check", token))["changes"].asInt())
        assertEquals("8", history(token, first).single()["newValue"].asText())
        assertTrue(history(other, second).single()["matchesRule"].asBoolean())
        clock.advance()
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "9")))
        node(call(HttpMethod.POST, "/monitors/$first/check", token))
        assertEquals(2, history(other, second).size())
        assertEquals(1, history(other, second, "?matchedOnly=true").size())
        clock.advance()
        node(call(HttpMethod.POST, "/monitors/$first/check", token))
        assertEquals(2, history(token, first).size())
        val fresh = MonitoringService(JdbcUnitOfWork(jdbc, TransactionTemplate(manager), json), clock)
        val owner = UUID.fromString(node(call(HttpMethod.GET, "/me", token))["id"].asText())
        assertEquals("9", fresh.get(owner, UUID.fromString(first)).values["Lab 1"])
        assertEquals(2, fresh.history(owner, UUID.fromString(first), 50, 0, false).size)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sources WHERE last_checked_at IS NOT NULL", Int::class.java))
        val hashes = jdbc.queryForList("SELECT token_hash FROM accounts", String::class.java)
        assertTrue(hashes.all { it.length == 64 && it != token })
        assertEquals(1, node(call(HttpMethod.GET, "/monitors?limit=1", token)).size())
        node(call(HttpMethod.PATCH, "/monitors/$first", token, mapOf("active" to false)))
        assertFalse(node(call(HttpMethod.GET, "/monitors/$first", token))["active"].asBoolean())
        assertEquals(204, call(HttpMethod.DELETE, "/monitors/$first", token).statusCode.value())
        assertEquals(404, call(HttpMethod.GET, "/monitors/$first", token).statusCode.value())
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM changes WHERE monitor_id = ?",
                Int::class.java,
                UUID.fromString(first)
            )
        )
    }

    @Test
    fun `validation authorization ownership and HTTP errors`() {
        assertEquals(401, call(HttpMethod.GET, "/monitors").statusCode.value())
        val unauthorized = call(HttpMethod.GET, "/monitors", "invalid")
        assertEquals(401, unauthorized.statusCode.value())
        assertEquals("Bearer", unauthorized.headers.getFirst("WWW-Authenticate"))
        assertEquals(400, call(HttpMethod.POST, "/accounts", body = mapOf("name" to " ")).statusCode.value())
        val token = register()
        assertEquals(
            400,
            call(
                HttpMethod.POST,
                "/sources",
                token,
                mapOf("spreadsheetId" to "https://localhost", "intervalSeconds" to 1)
            ).statusCode.value()
        )
        val source = source(token)
        val monitor = monitor(token, source)
        val other = register()
        for (path in listOf("/monitors/$monitor", "/monitors/$monitor/changes"))
            assertEquals(404, call(HttpMethod.GET, path, other).statusCode.value())
        assertEquals(404, call(HttpMethod.DELETE, "/monitors/$monitor", other).statusCode.value())
        assertEquals(404, call(HttpMethod.POST, "/monitors/$monitor/check", other).statusCode.value())
        assertEquals(400, call(HttpMethod.GET, "/monitors/not-a-uuid", token).statusCode.value())
        assertEquals(400, call(HttpMethod.GET, "/monitors?limit=0", token).statusCode.value())
        assertEquals(400, call(HttpMethod.PATCH, "/monitors/$monitor", token, "{}").statusCode.value())
        assertEquals(400, call(HttpMethod.PATCH, "/monitors/$monitor", token, "{\"active\":null}").statusCode.value())
        assertEquals(
            400,
            call(HttpMethod.PATCH, "/monitors/$monitor", token, "{\"active\":true,\"typo\":true}").statusCode.value()
        )
        assertEquals(405, call(HttpMethod.PUT, "/monitors/$monitor", token).statusCode.value())
        assertEquals(200, http.getForEntity("/actuator/health", String::class.java).statusCode.value())
    }

    @Test
    fun `upstream errors and missing rows keep last good state and recover without false removals`() {
        val token = register()
        val source = source(token)
        val monitor = monitor(token, source)
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "8")))
        node(call(HttpMethod.POST, "/monitors/$monitor/check", token))
        clock.advance()
        fetcher.failure = UpstreamFailure("Google Sheets returned HTTP 503")
        assertEquals(502, call(HttpMethod.POST, "/monitors/$monitor/check", token).statusCode.value())
        assertTrue(node(call(HttpMethod.GET, "/sources/$source", token))["lastError"].asText().contains("503"))
        assertEquals("8", node(call(HttpMethod.GET, "/monitors/$monitor", token))["values"]["Lab 1"].asText())
        assertEquals(0, history(token, monitor).size())
        clock.advance()
        fetcher.failure = null
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Other", "9")))
        assertEquals(1, node(call(HttpMethod.POST, "/monitors/$monitor/check", token))["monitorErrors"].asInt())
        assertEquals(0, history(token, monitor).size())
        clock.advance()
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "9")))
        node(call(HttpMethod.POST, "/monitors/$monitor/check", token))
        val change = history(token, monitor).single()
        assertEquals("8", change["oldValue"].asText())
        assertEquals("9", change["newValue"].asText())
        assertTrue(node(call(HttpMethod.GET, "/monitors/$monitor", token))["lastError"].isNull)
    }

    @Test
    fun `concurrent source checks fetch once and reject second transaction`() {
        val token = register()
        val source = source(token)
        val monitor = monitor(token, source)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fetcher.entered = entered
        fetcher.release = release
        Executors.newSingleThreadExecutor().use { executor ->
            val first = executor.submit<CheckResult> { checks.check(UUID.fromString(source)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFailsWith<Conflict> { checks.check(UUID.fromString(source)) }
                assertEquals(409, call(HttpMethod.POST, "/monitors/$monitor/check", token).statusCode.value())
                assertEquals(1, fetcher.calls.get())
            } finally {
                release.countDown()
            }
            assertEquals(0, first.get(5, TimeUnit.SECONDS).changes)
        }
    }

    @Test
    fun `paused monitors are not scheduled and resume compares with last saved baseline`() {
        val token = register()
        val source = source(token)
        val monitor = monitor(token, source)
        checks.check(UUID.fromString(source))
        node(call(HttpMethod.PATCH, "/monitors/$monitor", token, mapOf("active" to false)))
        clock.advance()
        assertTrue(checks.due().isEmpty())
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "8")))
        node(call(HttpMethod.PATCH, "/monitors/$monitor", token, mapOf("active" to true)))
        assertEquals(listOf(UUID.fromString(source)), checks.due())
        assertEquals(1, checks.check(UUID.fromString(source)).changes)
        assertEquals("8", history(token, monitor).single()["newValue"].asText())
    }

    @Test
    fun `history and baseline are rolled back together and retry creates one event`() {
        val token = register()
        val source = source(token)
        val monitor = monitor(token, source)
        checks.check(UUID.fromString(source))
        clock.advance()
        fetcher.table = Table(listOf("Student", "Lab 1"), listOf(listOf("Nikita", "8")))
        val failing = object : UnitOfWork {
            override fun <T : Any> transaction(block: (Repositories) -> T): T = uow.transaction { repos ->
                block(object : Repositories by repos {
                    override val monitors = object : Monitors by repos.monitors {
                        override fun update(monitor: Monitor) {
                            repos.monitors.update(monitor)
                            throw IllegalStateException("Injected failure after history and baseline writes")
                        }
                    }
                })
            }
        }
        assertFailsWith<IllegalStateException> { CheckService(failing, fetcher, clock).check(UUID.fromString(source)) }
        assertEquals(0, history(token, monitor).size())
        assertTrue(node(call(HttpMethod.GET, "/monitors/$monitor", token))["values"]["Lab 1"].isNull)
        assertEquals(1, checks.check(UUID.fromString(source)).changes)
        assertEquals(1, history(token, monitor).size())
    }
}
