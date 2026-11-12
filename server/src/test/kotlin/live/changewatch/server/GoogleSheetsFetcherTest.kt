package live.changewatch.server

import live.changewatch.core.UpstreamFailure
import org.junit.jupiter.api.Test
import kotlin.test.*

class GoogleSheetsFetcherTest {
    private val fetcher = GoogleSheetsFetcher()
    @Test
    fun `CSV handles BOM quoted commas newlines and Unicode`() {
        val table = fetcher.parse("\uFEFFСтудент,Lab 1\r\n\"Аксенов, Никита\",\"8\nиз 10\"\r\n")
        assertEquals(listOf("Студент", "Lab 1"), table.headers)
        assertEquals(listOf("Аксенов, Никита", "8\nиз 10"), table.rows.single())
    }

    @Test
    fun `duplicate headers empty CSV and inconsistent rows fail`() {
        listOf("", "Student,Student\nNikita,8", "Student,\nNikita,8", "Student\nNikita,8").forEach {
            assertFailsWith<UpstreamFailure> { fetcher.parse(it) }
        }
    }
}
