package com.autox.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The dashboard's figures, computed at most once in {@code autox.admin.stats-ttl} (a minute).
 *
 * <p>Kept here rather than in an HTTP cache alone: the figures are the same for every
 * administrator, and two of them with the page open should not each cost the database a full
 * set of aggregates. {@code synchronized}, so requests arriving while it is being computed wait
 * for that answer rather than start their own. On several instances each would hold its own
 * copy, which for a minute-old dashboard does not matter.
 */
@Service
public class AdminStatsService {

    private final AdminStatsRepository repository;
    private final Duration ttl;
    private final Clock clock;

    private AdminStats cached;
    private Instant computedAt;

    @Autowired
    AdminStatsService(AdminStatsRepository repository, @Value("${autox.admin.stats-ttl:PT1M}") Duration ttl) {
        this(repository, ttl, Clock.systemUTC());
    }

    AdminStatsService(AdminStatsRepository repository, Duration ttl, Clock clock) {
        this.repository = repository;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized AdminStats stats() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(computedAt.plus(ttl))) {
            return cached;
        }
        boolean ledger = repository.demoLedgerExists();
        cached = new AdminStats(
                now,
                ledger,
                repository.listingsByStatus(ledger),
                repository.activity(ledger, 7),
                repository.activity(ledger, 30),
                repository.daily(ledger),
                repository.topMakes(ledger),
                repository.priceVerdicts(ledger),
                repository.searchUsage(7),
                repository.searchUsage(30));
        computedAt = now;
        return cached;
    }
}
