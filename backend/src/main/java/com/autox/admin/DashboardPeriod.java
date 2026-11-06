package com.autox.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The dashboard's periods, in Paris time, the site's.
 *
 * <p>A period is whole days ending today, today so far included: "7 jours" is the six days before
 * today and today until now. It is compared with the same span just before it, ending at the same
 * time of day, so the two are the same length and "today until 10:00" is set against "yesterday
 * until 10:00", not against all of yesterday. Days are counted on the calendar, so a period that
 * crosses a change of clocks is an hour shorter or longer, and so is the one it is compared with.
 */
public enum DashboardPeriod {

    TODAY(1, Bucket.HOUR),
    WEEK(7, Bucket.DAY),
    MONTH(30, Bucket.DAY),
    QUARTER(90, Bucket.DAY);

    public static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    /** How a chart divides a period. */
    public enum Bucket {
        HOUR, DAY
    }

    private final int days;
    private final Bucket bucket;

    DashboardPeriod(int days, Bucket bucket) {
        this.days = days;
        this.bucket = bucket;
    }

    public int days() {
        return days;
    }

    public Bucket bucket() {
        return bucket;
    }

    /** This period and the one before it, as of {@code now}. */
    public Window window(ZonedDateTime now) {
        ZonedDateTime paris = now.withZoneSameInstant(PARIS);
        ZonedDateTime from = paris.toLocalDate().minusDays(days - 1L).atStartOfDay(PARIS);
        return new Window(this, from, paris, from.minusDays(days), paris.minusDays(days));
    }

    /**
     * The window, in Paris time.
     *
     * @param from         the first instant counted
     * @param to           now: the end, excluded
     * @param previousFrom the start of the period compared with
     * @param previousTo   its end, excluded: the same time of day, {@code days} earlier
     */
    public record Window(DashboardPeriod period, ZonedDateTime from, ZonedDateTime to,
                         ZonedDateTime previousFrom, ZonedDateTime previousTo) {

        /**
         * The start of every bucket a chart shows, in Paris local time, oldest first: each hour of
         * today until now, or each day. An hour that the clocks skip is not there; one they repeat
         * is there once, and holds both.
         */
        public List<LocalDateTime> buckets() {
            Set<LocalDateTime> starts = new LinkedHashSet<>();
            if (period.bucket == Bucket.HOUR) {
                for (ZonedDateTime hour = from; !hour.isAfter(to); hour = hour.plusHours(1)) {
                    starts.add(hour.toLocalDateTime().truncatedTo(ChronoUnit.HOURS));
                }
            } else {
                for (LocalDate day = from.toLocalDate(); !day.isAfter(to.toLocalDate()); day = day.plusDays(1)) {
                    starts.add(day.atStartOfDay());
                }
            }
            return new ArrayList<>(starts);
        }
    }
}
