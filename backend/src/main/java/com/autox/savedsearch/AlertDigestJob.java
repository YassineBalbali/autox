package com.autox.savedsearch;

import com.autox.scheduling.ScheduledTaskCoordinator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The morning's alert e-mails, once a day, in Paris time.
 *
 * <p>Nothing runs in between, so it wakes Neon once a day (docs/saved-searches.md). If the process
 * is not up at that moment the day is missed and the next run covers it, since each search keeps
 * its own date; the e-mail then shows at most ten of what is new per search, like any other.
 *
 * <p>The outbox's poller then sends what this wrote, in batches of twenty every 30 seconds.
 */
@Component
public class AlertDigestJob {

    private static final Logger log = LoggerFactory.getLogger(AlertDigestJob.class);

    private final SavedSearchRepository repository;
    private final AlertDigest digest;
    private final ScheduledTaskCoordinator coordinator;

    public AlertDigestJob(SavedSearchRepository repository, AlertDigest digest, ScheduledTaskCoordinator coordinator) {
        this.repository = repository;
        this.digest = digest;
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${autox.scheduling.alert-digest-cron}", zone = "Europe/Paris")
    public void run() {
        coordinator.runExclusively("alert-digest", this::runNow);
    }

    /** One account's failure is logged and does not cost the others their e-mail. */
    void runNow() {
        int accounts = 0;
        int written = 0;
        for (UUID account : repository.accountsWithActiveSearches()) {
            accounts++;
            try {
                if (digest.digestFor(account, true)) {
                    written++;
                }
            } catch (RuntimeException failure) {
                log.error("The alert digest failed for account {}", account, failure);
            }
        }
        log.info("Alert digest: {} e-mail(s) written for {} account(s) with active searches", written, accounts);
    }
}
