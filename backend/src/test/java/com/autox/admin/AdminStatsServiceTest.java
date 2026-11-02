package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
    @DisplayName("figures are counted once a minute, however many requests ask")
    void cachedForTheTtl() {
        AdminStatsRepository repository = mock(AdminStatsRepository.class);
        AdminStatsService service = new AdminStatsService(repository, Duration.ofMinutes(1), clock);

        AdminStats first = service.stats();
        now = now.plusSeconds(59);
        assertThat(service.stats()).isSameAs(first);
        verify(repository, times(1)).demoLedgerExists();

        now = now.plusSeconds(1);
        AdminStats second = service.stats();
        assertThat(second).isNotSameAs(first);
        assertThat(second.generatedAt()).isEqualTo(now);
        verify(repository, times(2)).demoLedgerExists();
    }
}
