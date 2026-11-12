package live.changewatch.server

import live.changewatch.core.*
import org.apache.commons.csv.CSVFormat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class GoogleSheetsFetcher(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build(),
    private val timeout: Duration = Duration.ofSeconds(15),
    private val maxBytes: Int = 2_000_000
) : SheetFetcher {
    override fun fetch(source: Source): Table {
        val uri =
            URI("https://docs.google.com/spreadsheets/d/${source.spreadsheetId}/export?format=csv&gid=${source.sheetId}")
        return try {
            val response = download(uri)
            parse(response)
        } catch (e: UpstreamFailure) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw UpstreamFailure("Google Sheets request interrupted")
        } catch (_: Exception) {
            throw UpstreamFailure("Unable to download or parse public Google sheet")
        }
    }

    private fun download(initial: URI): String {
        var uri = initial
        repeat(4) {
            if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443) ||
                !(uri.host == "docs.google.com" || uri.host?.endsWith(".googleusercontent.com") == true)
            )
                throw UpstreamFailure("Google export redirected to an unsupported host")
            val request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "text/csv").GET().build()

            val response = client.send(request, limitedBody())
            if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                uri = uri.resolve(
                    response.headers().firstValue("Location")
                        .orElseThrow { UpstreamFailure("Missing redirect location") })
            } else {
                if (response.statusCode() != 200) throw UpstreamFailure("Google Sheets returned HTTP ${response.statusCode()}; sheet must allow public reading")
                val contentType = response.headers().firstValue("Content-Type").orElse("")
                if (!contentType.startsWith(
                        "text/csv",
                        ignoreCase = true
                    )
                ) throw UpstreamFailure("Google Sheets did not return CSV")
                return String(response.body(), StandardCharsets.UTF_8)
            }
        }
        throw UpstreamFailure("Too many Google export redirects")
    }

    private fun limitedBody() = HttpResponse.BodyHandler<ByteArray> {
        object : HttpResponse.BodySubscriber<ByteArray> {
            private val delegate = HttpResponse.BodySubscribers.ofByteArray()
            private var received = 0L
            private lateinit var subscription: java.util.concurrent.Flow.Subscription
            override fun getBody() = delegate.body
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) {
                subscription = s; delegate.onSubscribe(s)
            }

            override fun onNext(items: List<java.nio.ByteBuffer>) {
                received += items.sumOf { it.remaining().toLong() }
                if (received > maxBytes) {
                    subscription.cancel()
                    delegate.onError(UpstreamFailure("Google sheet exceeds $maxBytes bytes"))
                } else delegate.onNext(items)
            }

            override fun onError(t: Throwable) = delegate.onError(t)
            override fun onComplete() = delegate.onComplete()
        }
    }

    internal fun parse(csv: String): Table {
        try {
            CSVFormat.DEFAULT.parse(csv.removePrefix("\uFEFF").reader()).use { parser ->
                val records = parser.records
                if (records.isEmpty()) throw UpstreamFailure("Empty sheet")
                val headers = records.first().map { it.trim() }
                if (headers.any { it.isEmpty() || it.length > 200 } || headers.distinct().size != headers.size)
                    throw UpstreamFailure("First row must contain unique non-empty column names of up to 200 characters")
                if (headers.size > 200 || records.size > 10001) throw UpstreamFailure("Sheet exceeds 200 columns or 10000 rows")
                val rows = records.drop(1).map { record ->
                    if (record.size() > headers.size) throw UpstreamFailure("Sheet row contains more cells than header")
                    record.toList()
                }
                return Table(headers, rows)
            }
        } catch (e: UpstreamFailure) {
            throw e
        } catch (_: Exception) {
            throw UpstreamFailure("Malformed CSV sheet")
        }
    }
}
