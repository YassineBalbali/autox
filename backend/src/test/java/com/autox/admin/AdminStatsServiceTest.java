package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The minute's cache, on a clock the test moves. */
class AdminStatsServiceTest {

    private Instant now = Instant.parse("2026-11-02T10:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Test
    @DisplayName("figures are counted once a minute per period, however many requests ask")
    void cachedForTheTtl() {
        AdminStatsRepository repository = mock(AdminStatsRepository.class);
        when(repository.databaseNow()).thenAnswer(call -> now);
        AdminStatsService service = new AdminStatsService(repository, Duration.ofMinutes(1), clock);

        AdminStats first = service.stats(DashboardPeriod.WEEK);
        now = now.plusSeconds(59);
        assertThat(service.stats(DashboardPeriod.WEEK)).isSameAs(first);
        verify(repository, times(1)).demoLedgerExists();

        // Another period is its own figures.
        AdminStats today = service.stats(DashboardPeriod.TODAY);
        assertThat(today).isNotSameAs(first);
        assertThat(today.period().key()).isEqualTo(DashboardPeriod.TODAY);
        verify(repository, times(2)).demoLedgerExists();

        now = now.plusSeconds(1);
        AdminStats second = service.stats(DashboardPeriod.WEEK);
        assertThat(second).isNotSameAs(first);
        assertThat(second.generatedAt()).isEqualTo(now);
        verify(repository, times(3)).demoLedgerExists();
    }

    @Test
    @DisplayName("the window is worked out in Paris time, from the database's clock, not the application's")
    void windowFromTheDatabaseClock() {
        AdminStatsRepository repository = mock(AdminStatsRepository.class);
        when(repository.databaseNow()).thenReturn(now);
        AdminStatsService service = new AdminStatsService(repository, Duration.ofMinutes(1),
                Clock.offset(clock, Duration.ofMinutes(-7)));

        AdminStats.PeriodWindow window = service.stats(DashboardPeriod.TODAY).period();

        // 10:00 UTC on 2 November is 11:00 in Paris; the day began at 23:00 UTC the day before.
        assertThat(window.from()).isEqualTo(Instant.parse("2026-11-01T23:00:00Z"));
        assertThat(window.to()).isEqualTo(now);
        assertThat(window.previousFrom()).isEqualTo(Instant.parse("2026-10-31T23:00:00Z"));
        assertThat(window.previousTo()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    }
}
