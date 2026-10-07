package de.makibytes.registerwerk.registerstatement.internal;

import de.makibytes.registerwerk.deployment.api.AssetHolder;
import de.makibytes.registerwerk.deployment.api.AssetHolderRepository;
import de.makibytes.registerwerk.registerstatement.api.StatementTrigger;
import de.makibytes.registerwerk.shared.RegisterNotReconciledException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues the §19(2) no. 3 annual register statements.
 *
 * <p>Runs daily and picks up every single-entry consumer holder whose last
 * statement is older than one year (or who has none yet), in keyset-paginated
 * batches. Running daily rather than annually means a holder onboarded at any
 * time of year gets their anniversary statement on time, and a missed run
 * (deployment, downtime) self-heals on the next day rather than slipping a
 * whole year — the obligation is "at least once a year".
 */
@Component
class AnnualRegisterStatementJob {

    private static final Logger log = LoggerFactory.getLogger(AnnualRegisterStatementJob.class);

    private final AssetHolderRepository holderRepository;
    private final RegisterStatementService statementService;
    private final MeterRegistry meters;
    private final int batchSize;

    AnnualRegisterStatementJob(
            AssetHolderRepository holderRepository,
            RegisterStatementService statementService,
            MeterRegistry meters,
            @Value("${registerwerk.register-statement.batch-size:200}") int batchSize) {
        this.holderRepository = holderRepository;
        this.statementService = statementService;
        this.meters = meters;
        this.batchSize = batchSize;
    }

    /** Daily at 03:00 UTC. */
    @SchedulerLock(name = "annualRegisterStatement", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${registerwerk.register-statement.annual-cron:0 0 3 * * *}")
    public void issueAnnualStatements() {
        // A statement is "annual" once a year has elapsed since the last one.
        Instant cutoff = Instant.now().minus(365, ChronoUnit.DAYS);
        UUID lastId = null;
        int issued = 0;
        Set<UUID> warnedUnreconciled = new HashSet<>();

        while (true) {
            List<AssetHolder> batch = (lastId == null)
                    ? holderRepository.findAnnualStatementDueFirst(cutoff, PageRequest.of(0, batchSize))
                    : holderRepository.findAnnualStatementDueAfter(cutoff, lastId, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            for (AssetHolder holder : batch) {
                lastId = holder.getId();
                try {
                    if (statementService.issueForHolder(holder.getId(), StatementTrigger.ANNUAL)
                            .isPresent()) {
                        issued++;
                    }
                } catch (RegisterNotReconciledException e) {
                    // 9A-05: refused on purpose while the asset's holder sync is BLOCKED. lastStatementAt stays
                    // untouched, so tomorrow's run issues the statement once the register is reconciled again.
                    StatementRefusalMetrics.unreconciled(meters).increment();
                    if (warnedUnreconciled.add(e.getAssetId())) {
                        log.warn("Annual register statements for asset {} are refused until its holder sync is reconciled: {}",
                                e.getAssetId(), e.getMessage());
                    }
                } catch (Exception e) {
                    // One holder's failure must not stop the batch.
                    log.error("Annual statement failed for holder {}: {}", holder.getId(), e.getMessage());
                }
            }
            if (batch.size() < batchSize) {
                break;
            }
        }

        if (issued > 0) {
            log.info("Annual register statements issued: {}", issued);
        }
    }

    /** Hourly retry of failed deliveries. */
    @SchedulerLock(name = "annualRegisterStatementRetry", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${registerwerk.register-statement.retry-cron:0 30 * * * *}")
    public void retryFailedDeliveries() {
        try {
            statementService.retryFailedDeliveries();
        } catch (Exception e) {
            log.error("Register statement delivery retry failed: {}", e.getMessage());
        }
    }
}
