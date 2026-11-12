package live.changewatch.server

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import live.changewatch.core.*
import live.changewatch.identity.IdentityService
import live.changewatch.monitoring.CheckService
import live.changewatch.monitoring.MonitoringService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.net.URI
import java.util.UUID

data class RegisterRequest(@field:NotBlank @field:Size(max = 100) val name: String)
data class SourceRequest(
    @field:Pattern(regexp = "[A-Za-z0-9_-]{10,200}") val spreadsheetId: String,
    @field:PositiveOrZero val sheetId: Long = 0,
    @field:Min(30) @field:Max(86400) val intervalSeconds: Int = 60
)

data class MonitorRequest(
    val sourceId: UUID, @field:NotBlank @field:Size(max = 200) val name: String,
    @field:NotBlank @field:Size(max = 200) val rowKey: String,
    @field:NotBlank @field:Size(max = 200) val rowValue: String,
    @field:Size(min = 1, max = 50) val columns: List<String>, val rule: Rule = Rule.ANY_CHANGE
)

data class ActiveRequest(val active: Boolean)

@RestController
@RequestMapping("/api/v1")
class Api(
    private val identity: IdentityService, private val monitoring: MonitoringService,
    private val checks: CheckService
) {
    @PostMapping("/accounts")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<Credentials> =
        ResponseEntity.status(201).header("Cache-Control", "no-store").body(identity.register(request.name))

    @GetMapping("/me")
    fun me(@RequestHeader("Authorization", required = false) authorization: String?) = account(authorization)

    @PostMapping("/sources")
    fun source(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: SourceRequest
    ): ResponseEntity<Source> {
        account(auth)
        val source = monitoring.source(request.spreadsheetId, request.sheetId, request.intervalSeconds)
        return ResponseEntity.ok().location(URI("/api/v1/sources/${source.id}")).body(source)
    }

    @GetMapping("/sources/{id}")
    fun source(@RequestHeader("Authorization", required = false) auth: String?, @PathVariable id: UUID): Source {
        account(auth)
        return monitoring.getSource(id)
    }

    @PostMapping("/monitors")
    fun create(
        @RequestHeader("Authorization", required = false) auth: String?,
        @Valid @RequestBody request: MonitorRequest
    ): ResponseEntity<Monitor> {
        val monitor = monitoring.create(
            account(auth).id,
            request.sourceId,
            request.name,
            request.rowKey,
            request.rowValue,
            request.columns,
            request.rule
        )
        return ResponseEntity.created(URI("/api/v1/monitors/${monitor.id}")).body(monitor)
    }

    @GetMapping("/monitors")
    fun list(
        @RequestHeader("Authorization", required = false) auth: String?, @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ) = monitoring.list(account(auth).id, limit, offset)

    @GetMapping("/monitors/{id}")
    fun get(@RequestHeader("Authorization", required = false) auth: String?, @PathVariable id: UUID) =
        monitoring.get(account(auth).id, id)

    @PatchMapping("/monitors/{id}")
    fun active(
        @RequestHeader("Authorization", required = false) auth: String?, @PathVariable id: UUID,
        @RequestBody request: ActiveRequest
    ) = monitoring.active(account(auth).id, id, request.active)

    @DeleteMapping("/monitors/{id}")
    fun delete(
        @RequestHeader("Authorization", required = false) auth: String?,
        @PathVariable id: UUID
    ): ResponseEntity<Void> {
        monitoring.delete(account(auth).id, id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/monitors/{id}/changes")
    fun history(
        @RequestHeader("Authorization", required = false) auth: String?, @PathVariable id: UUID,
        @RequestParam(defaultValue = "50") limit: Int, @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "false") matchedOnly: Boolean
    ) = monitoring.history(account(auth).id, id, limit, offset, matchedOnly)

    @PostMapping("/monitors/{id}/check")
    fun check(@RequestHeader("Authorization", required = false) auth: String?, @PathVariable id: UUID) =
        checks.checkFor(account(auth).id, id)

    private fun account(header: String?): Account {
        if (header == null || !header.startsWith("Bearer ")) throw Unauthenticated()
        return identity.authenticate(header.removePrefix("Bearer "))
    }

}

@RestControllerAdvice
class Errors {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun error(error: Exception, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = when (error) {
            is InvalidInput, is MethodArgumentNotValidException, is HttpMessageNotReadableException,
            is MethodArgumentTypeMismatchException, is org.springframework.web.bind.MissingServletRequestParameterException -> HttpStatus.BAD_REQUEST

            is Unauthenticated -> HttpStatus.UNAUTHORIZED
            is Missing, is NoResourceFoundException -> HttpStatus.NOT_FOUND
            is Conflict -> HttpStatus.CONFLICT
            is UpstreamFailure -> HttpStatus.BAD_GATEWAY
            is org.springframework.web.HttpRequestMethodNotSupportedException -> HttpStatus.METHOD_NOT_ALLOWED
            is org.springframework.web.HttpMediaTypeNotSupportedException -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        val detail = when (error) {
            is MethodArgumentNotValidException -> error.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
            is HttpMessageNotReadableException -> "Malformed JSON, missing required field, unknown field or invalid enum value"
            is MethodArgumentTypeMismatchException -> "Invalid parameter: ${error.name}"
            else -> if (status.is5xxServerError && error !is UpstreamFailure) "Internal server error" else error.message
                ?: status.reasonPhrase
        }
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) log.error("request_failed path={}", request.requestURI, error)
        else log.info("request_rejected path={} status={}", request.requestURI, status.value())
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.instance = URI(request.requestURI)
        val response = ResponseEntity.status(status)
        if (status == HttpStatus.UNAUTHORIZED) response.header("WWW-Authenticate", "Bearer")
        return response.body(problem)
    }
}
