package live.changewatch.server

import com.fasterxml.jackson.databind.ObjectMapper
import live.changewatch.core.*
import live.changewatch.identity.IdentityService
import live.changewatch.monitoring.CheckService
import live.changewatch.monitoring.MonitoringService
import live.changewatch.storage.JdbcUnitOfWork
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
    @Bean
    fun unitOfWork(jdbc: JdbcTemplate, manager: PlatformTransactionManager, json: ObjectMapper): UnitOfWork =
        JdbcUnitOfWork(jdbc, TransactionTemplate(manager).apply { timeout = 90 }, json)

    @Bean
    fun fetcher(): SheetFetcher = GoogleSheetsFetcher()
    @Bean
    fun identity(uow: UnitOfWork) = IdentityService(uow)
    @Bean
    fun monitoring(uow: UnitOfWork, clock: Clock) = MonitoringService(uow, clock)
    @Bean
    fun checks(uow: UnitOfWork, fetcher: SheetFetcher, clock: Clock) = CheckService(uow, fetcher, clock)
}

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}

@Component
@ConditionalOnProperty(name = ["changewatch.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class CheckScheduler(private val checks: CheckService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${changewatch.scheduler.delay-ms:5000}")
    fun tick() {
        try {
            checks.due().forEach { id ->
                try {
                    val result = checks.check(id)
                    log.info(
                        "source_check sourceId={} changes={} monitorErrors={}",
                        id,
                        result.changes,
                        result.monitorErrors
                    )
                } catch (_: Conflict) {
                } catch (e: Exception) {
                    log.warn("source_check_failed sourceId={} reason={}", id, e.message)
                }
            }
        } catch (e: Exception) {
            log.error("scheduler_failed", e)
        }
    }
}
