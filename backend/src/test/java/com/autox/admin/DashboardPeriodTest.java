package com.autox.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The periods' arithmetic, in Paris time, across a change of clocks too. */
class DashboardPeriodTest {

    private static ZonedDateTime paris(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(DashboardPeriod.PARIS);
    }

    @Test
    @DisplayName("today: from midnight to now, against yesterday from midnight to the same time")
    void today() {
        DashboardPeriod.Window window = DashboardPeriod.TODAY.window(paris("2026-11-05T14:37:00"));

        assertThat(window.from()).isEqualTo(paris("2026-11-05T00:00:00"));
        assertThat(window.to()).isEqualTo(paris("2026-11-05T14:37:00"));
        assertThat(window.previousFrom()).isEqualTo(paris("2026-11-04T00:00:00"));
        assertThat(window.previousTo()).isEqualTo(paris("2026-11-04T14:37:00"));
    }

    @Test
    @DisplayName("7, 30 and 90 days: whole days ending today, each against the same span just before")
    void days() {
        ZonedDateTime now = paris("2026-11-05T14:37:00");
        for (DashboardPeriod period : List.of(DashboardPeriod.WEEK, DashboardPeriod.MONTH, DashboardPeriod.QUARTER)) {
            DashboardPeriod.Window window = period.window(now);

            assertThat(window.from()).isEqualTo(paris("2026-11-05T00:00:00").minusDays(period.days() - 1L));
            assertThat(window.to()).isEqualTo(now);
            assertThat(window.previousTo()).isEqualTo(now.minusDays(period.days()));
            assertThat(window.previousFrom()).isEqualTo(window.from().minusDays(period.days()));
            // Back to back: the previous period ends where this one would, a period earlier.
            assertThat(window.previousFrom().plusDays(period.days())).isEqualTo(window.from());
        }
        assertThat(DashboardPeriod.WEEK.window(now).from()).isEqualTo(paris("2026-10-30T00:00:00"));
        assertThat(DashboardPeriod.QUARTER.window(now).from()).isEqualTo(paris("2026-08-08T00:00:00"));
    }

    @Test
    @DisplayName("an instant in another zone is read as the Paris time it is")
    void otherZones() {
        // 23:30 UTC on 4 November is already 5 November in Paris.
        ZonedDateTime utc = ZonedDateTime.parse("2026-11-04T23:30:00Z");

        assertThat(DashboardPeriod.TODAY.window(utc).from()).isEqualTo(paris("2026-11-05T00:00:00"));
    }

    @Test
    @DisplayName("across the change to winter time, days stay calendar days: midnight to midnight")
    void daylightSaving() {
        // Clocks went back at 03:00 on 25 October 2026.
        DashboardPeriod.Window window = DashboardPeriod.WEEK.window(paris("2026-10-27T12:00:00"));

        assertThat(window.from()).isEqualTo(paris("2026-10-21T00:00:00"));
        assertThat(window.previousFrom()).isEqualTo(paris("2026-10-14T00:00:00"));
        // The week holding the change has an extra hour, and is compared as it is.
        assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofDays(6).plusHours(13));
        assertThat(window.buckets()).hasSize(7);
    }

    @Test
    @DisplayName("today's buckets are its hours until now; a repeated hour is one bucket")
    void hours() {
        assertThat(DashboardPeriod.TODAY.window(paris("2026-11-05T14:37:00")).buckets())
                .hasSize(15)
                .startsWith(LocalDateTime.parse("2026-11-05T00:00:00"))
                .endsWith(LocalDateTime.parse("2026-11-05T14:00:00"));
        // 25 October: 02:00 to 03:00 happened twice.
        assertThat(DashboardPeriod.TODAY.window(paris("2026-10-25T05:10:00")).buckets())
                .containsExactly(LocalDateTime.parse("2026-10-25T00:00:00"), LocalDateTime.parse("2026-10-25T01:00:00"),
                        LocalDateTime.parse("2026-10-25T02:00:00"), LocalDateTime.parse("2026-10-25T03:00:00"),
                        LocalDateTime.parse("2026-10-25T04:00:00"), LocalDateTime.parse("2026-10-25T05:00:00"));
        // 29 March 2026: 02:00 to 03:00 never happened.
        assertThat(DashboardPeriod.TODAY.window(paris("2026-03-29T04:10:00")).buckets())
                .doesNotContain(LocalDateTime.parse("2026-03-29T02:00:00"))
                .hasSize(4);
    }

    @Test
    @DisplayName("the days' buckets are each day of the period, today included")
    void dayBuckets() {
        List<LocalDateTime> buckets = DashboardPeriod.MONTH.window(paris("2026-11-05T00:10:00")).buckets();

        assertThat(buckets).hasSize(30);
        assertThat(buckets.getFirst()).isEqualTo(LocalDateTime.parse("2026-10-07T00:00:00"));
        assertThat(buckets.getLast()).isEqualTo(LocalDateTime.parse("2026-11-05T00:00:00"));
    }
}
