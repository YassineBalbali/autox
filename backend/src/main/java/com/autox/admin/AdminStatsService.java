package com.autox.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin dashboard's figures, computed for one period at a time and kept for {@code ttl},
 * period by period: a dashboard left open, or several administrators, cost one set of queries a
 * minute, not one per refresh.
 */
@Service
public class AdminStatsService {

    private final AdminStatsRepository repository;
    private final Duration ttl;
    private final Clock clock;

    private record Cached(AdminStats stats, Instant computedAt) {
    }

    private final Map<DashboardPeriod, Cached> cache = new EnumMap<>(DashboardPeriod.class);

    @org.springframework.beans.factory.annotation.Autowired
    AdminStatsService(AdminStatsRepository repository, @Value("${autox.admin.stats-ttl:PT1M}") Duration ttl) {
        this(repository, ttl, Clock.systemUTC());
    }

    AdminStatsService(AdminStatsRepository repository, Duration ttl, Clock clock) {
        this.repository = repository;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public synchronized AdminStats stats(DashboardPeriod period) {
        Instant now = clock.instant();
        Cached cached = cache.get(period);
        if (cached != null && now.isBefore(cached.computedAt().plus(ttl))) {
            return cached.stats();
        }
        DashboardPeriod.Window window = period.window(ZonedDateTime.ofInstant(now, DashboardPeriod.PARIS));
        boolean ledger = repository.demoLedgerExists();
        AdminStats stats = new AdminStats(
                now,
                ledger,
                new AdminStats.PeriodWindow(period, window.from().toInstant(), window.to().toInstant(),
                        window.previousFrom().toInstant(), window.previousTo().toInstant(), period.bucket()),
                repository.attention(ledger),
                repository.activity(ledger, window),
                repository.series(ledger, window),
                repository.listingsByStatus(ledger),
                repository.topMakes(ledger),
                repository.priceVerdicts(ledger),
                repository.search(window),
                repository.emails(window));
        cache.put(period, new Cached(stats, now));
        return stats;
    }
}
