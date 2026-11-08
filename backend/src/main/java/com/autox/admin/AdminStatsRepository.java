package com.autox.admin;

import com.autox.admin.AdminStats.Activity;
import com.autox.admin.AdminStats.Attention;
import com.autox.admin.AdminStats.Comparison;
import com.autox.admin.AdminStats.Count;
import com.autox.admin.AdminStats.Emails;
import com.autox.admin.AdminStats.FilterUse;
import com.autox.admin.AdminStats.ListingsByStatus;
import com.autox.admin.AdminStats.MakeCount;
import com.autox.admin.AdminStats.PendingListing;
import com.autox.admin.AdminStats.Point;
import com.autox.admin.AdminStats.PriceVerdicts;
import com.autox.admin.AdminStats.SearchUsage;
import com.autox.admin.AdminStats.Split;
import com.autox.search.ListingLabel;
import com.autox.search.ListingSearchRepository;
import com.autox.search.MarketPrice;
import com.autox.search.SearchFilterUsage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The aggregates behind the admin dashboard. Each is one pass over its table with {@code FILTER}
 * clauses, so a count of the page costs one scan and not one query per figure.
 *
 * <p><b>Real or demo</b> is decided by the demo ledger and nothing else: a row the seed recorded
 * is demo, every other row is real. The ledger exists only while the demo is seeded (see
 * {@code DemoLedger}), so its absence is asked for first and an empty set stands in for it; a
 * query naming a table that is not there would fail outright.
 *
 * <p>A message is demo when its sender is a demo account. The seed writes no messages, but a
 * visitor can sign in to a demo account and send one, and that is not a real user's activity.
 *
 * <p>A period and the one it is compared with are {@link DashboardPeriod.Window}s, Paris time. Each
 * count reads both windows in one pass, and only rows from the start of the earlier one.
 */
@Repository
class AdminStatsRepository {

    static final int TOP_MAKES = 10;
    static final int TOP_FILTERS = 8;
    /** The queue's first listings on the dashboard. */
    static final int OLDEST_PENDING = 5;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    AdminStatsRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    boolean demoLedgerExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('public.demo_seed_ledger') IS NOT NULL", Boolean.class));
    }

    /** Two CTEs, {@code demo_listing(id)} and {@code demo_user(id)}, empty without a ledger. */
    private static String demoSets(boolean ledger) {
        return ledger
                ? """
                  demo_listing AS (SELECT entity_id AS id FROM demo_seed_ledger WHERE entity_type = 'LISTING'),
                  demo_user AS (SELECT entity_id AS id FROM demo_seed_ledger WHERE entity_type = 'USER')
                  """
                : """
                  demo_listing AS (SELECT NULL::uuid AS id WHERE false),
                  demo_user AS (SELECT NULL::uuid AS id WHERE false)
                  """;
    }

    /** Every listing not deleted, aliased {@code l}, with {@code demo} true for the seed's. */
    private static final String LISTINGS = """
            (SELECT x.*, EXISTS (SELECT 1 FROM demo_listing d WHERE d.id = x.id) AS demo
               FROM listing x
              WHERE x.deleted_at IS NULL) l
            """;

    private static String split(String condition, String name) {
        return "count(*) FILTER (WHERE (" + condition + ") AND NOT demo) AS " + name + "_real, "
                + "count(*) FILTER (WHERE (" + condition + ") AND demo) AS " + name + "_demo";
    }

    private static Split split(ResultSet row, String name) throws SQLException {
        return new Split(row.getLong(name + "_real"), row.getLong(name + "_demo"));
    }

    ListingsByStatus listingsByStatus(boolean ledger) {
        String sql = "WITH " + demoSets(ledger) + " SELECT "
                + String.join(", ",
                        split(ListingSearchRepository.visible("l"), "published"),
                        // The queue's own predicate: ListingRepository.findAwaitingReview.
                        split("l.status = 'ACTIVE' AND l.moderation_status = 'NOT_REVIEWED'", "pending"),
                        split("l.moderation_status = 'REJECTED'", "rejected"),
                        split("l.status = 'SOLD'", "sold"),
                        split("l.status = 'EXPIRED'", "expired"),
                        split(ListingSearchRepository.visible("l") + " AND l.damaged", "damaged"),
                        split(ListingSearchRepository.visible("l") + " AND l.offer_type = 'SALE'", "sale"),
                        split(ListingSearchRepository.visible("l") + " AND l.offer_type = 'LEASING'", "leasing"))
                + " FROM " + LISTINGS;
        return jdbc.queryForObject(sql, (row, index) -> new ListingsByStatus(
                split(row, "published"), split(row, "pending"), split(row, "rejected"),
                split(row, "sold"), split(row, "expired"), split(row, "damaged"),
                split(row, "sale"), split(row, "leasing")));
    }

    List<MakeCount> topMakes(boolean ledger) {
        String sql = "WITH " + demoSets(ledger) + " SELECT coalesce(mk.name, l.make_text) AS make, "
                + split("true", "n")
                + " FROM " + LISTINGS
                + " LEFT JOIN vehicle_make mk ON mk.id = l.make_id WHERE "
                + ListingSearchRepository.visible("l")
                + " GROUP BY 1 ORDER BY n_real DESC, n_demo DESC, make LIMIT " + TOP_MAKES;
        return jdbc.query(sql, (row, index) -> new MakeCount(row.getString("make"), split(row, "n")));
    }

    /**
     * Published listings by price indicator, through {@link MarketPrice}'s own fragments, so this
     * cannot disagree with the badge. One index range scan per published listing: fine at this
     * size and, on a large catalogue, the first thing here to outgrow a minute's cache.
     */
    PriceVerdicts priceVerdicts(boolean ledger) {
        String sql = "WITH " + demoSets(ledger) + ", verdicts AS (SELECT " + MarketPrice.LEVEL
                + " AS level, l.demo FROM " + LISTINGS + MarketPrice.LATERAL
                + " WHERE " + ListingSearchRepository.visible("l") + ") SELECT "
                + String.join(", ",
                        split("level = 'GOOD_DEAL'", "good"),
                        split("level = 'MARKET'", "market"),
                        split("level = 'ABOVE_MARKET'", "above"),
                        split("level IS NULL", "none"))
                + " FROM verdicts";
        return jdbc.queryForObject(sql, (row, index) -> new PriceVerdicts(
                split(row, "good"), split(row, "market"), split(row, "above"), split(row, "none")));
    }



    // ---- what is waiting ----------------------------------------------------------------

    /** The moderation queue's own predicate: ListingRepository.findAwaitingReview, and its order. */
    private static final String QUEUE = "l.status = 'ACTIVE' AND l.moderation_status = 'NOT_REVIEWED'";

    Attention attention(boolean ledger) {
        Map<String, Object> queue = jdbc.queryForMap("WITH " + demoSets(ledger) + " SELECT "
                + split(QUEUE, "pending")
                + ", min(l.created_at) FILTER (WHERE (" + QUEUE + ") AND NOT demo) AS oldest_real"
                + ", min(l.created_at) FILTER (WHERE (" + QUEUE + ") AND demo) AS oldest_demo"
                + " FROM " + LISTINGS);
        Map<String, Object> reports = jdbc.queryForMap(
                "SELECT count(*) AS open, min(created_at) AS oldest FROM message_report WHERE resolution IS NULL");
        Map<String, Object> listingReports = jdbc.queryForMap(
                "SELECT count(*) AS open, min(created_at) AS oldest FROM listing_report WHERE resolution IS NULL");
        return new Attention(
                new Split(number(queue, "pending_real"), number(queue, "pending_demo")),
                instant(queue.get("oldest_real")),
                instant(queue.get("oldest_demo")),
                number(reports, "open"),
                instant(reports.get("oldest")),
                number(listingReports, "open"),
                instant(listingReports.get("oldest")),
                oldestPending(ledger));
    }

    /** The five oldest in the queue, and the five oldest real ones: the page shows one or the other. */
    private List<PendingListing> oldestPending(boolean ledger) {
        String queue = """
                SELECT l.id, l.title, l.created_at, l.price_amount, l.price_currency_code, l.offer_type, l.damaged,
                       coalesce(mk.name, l.make_text) AS make, coalesce(mo.name, l.model_text) AS model,
                       l.version_text, u.display_name, u.role, l.demo
                  FROM %s
                  JOIN app_user u ON u.id = l.seller_id
                  LEFT JOIN vehicle_make mk ON mk.id = l.make_id
                  LEFT JOIN vehicle_model mo ON mo.id = l.model_id
                 WHERE %s
                """.formatted(LISTINGS, QUEUE);
        String sql = "WITH " + demoSets(ledger) + ", queue AS (" + queue + ")"
                + " (SELECT * FROM queue ORDER BY created_at, id LIMIT " + OLDEST_PENDING + ")"
                + " UNION (SELECT * FROM queue WHERE NOT demo ORDER BY created_at, id LIMIT " + OLDEST_PENDING + ")"
                + " ORDER BY created_at, id";
        return jdbc.query(sql, (row, index) -> new PendingListing(
                row.getObject("id", UUID.class),
                ListingLabel.of(row.getString("make"), row.getString("model"), row.getString("version_text"),
                        row.getString("title")),
                row.getString("title"),
                row.getString("display_name"),
                row.getString("role"),
                row.getTimestamp("created_at").toInstant(),
                row.getLong("price_amount"),
                row.getString("price_currency_code"),
                "LEASING".equals(row.getString("offer_type")),
                row.getBoolean("damaged"),
                row.getBoolean("demo")));
    }

    // ---- what was created ---------------------------------------------------------------

    /** What a period is counted from, aliased {@code t(at, demo)}: rows from the earlier window on. */
    private static final String LISTING_ROWS = """
            SELECT x.created_at AS at, EXISTS (SELECT 1 FROM demo_listing d WHERE d.id = x.id) AS demo
              FROM listing x WHERE x.deleted_at IS NULL AND x.created_at >= :previousFrom
            """;
    private static final String USER_ROWS = """
            SELECT u.created_at AS at, EXISTS (SELECT 1 FROM demo_user d WHERE d.id = u.id) AS demo
              FROM app_user u WHERE u.created_at >= :previousFrom
            """;
    private static final String MESSAGE_ROWS = """
            SELECT m.created_at AS at, EXISTS (SELECT 1 FROM demo_user d WHERE d.id = m.sender_id) AS demo
              FROM message m WHERE m.created_at >= :previousFrom
            """;
    private static final String SAVED_SEARCH_ROWS = """
            SELECT s.created_at AS at, EXISTS (SELECT 1 FROM demo_user d WHERE d.id = s.user_id) AS demo
              FROM saved_search s WHERE s.created_at >= :previousFrom
            """;
    private static final String SENT_EMAIL_ROWS = """
            SELECT o.sent_at AS at, false AS demo
              FROM outbox_email o WHERE o.status = 'SENT' AND o.sent_at >= :previousFrom
            """;

    Activity activity(boolean ledger, DashboardPeriod.Window window) {
        return new Activity(
                compare(ledger, window, LISTING_ROWS),
                compare(ledger, window, USER_ROWS),
                compare(ledger, window, MESSAGE_ROWS),
                compare(ledger, window, SAVED_SEARCH_ROWS));
    }

    private Comparison compare(boolean ledger, DashboardPeriod.Window window, String rows) {
        String sql = "WITH " + demoSets(ledger) + " SELECT "
                + split("at >= :from AND at < :to", "current") + ", "
                + split("at >= :previousFrom AND at < :previousTo", "previous")
                + " FROM (" + rows + ") t";
        return named.queryForObject(sql, parameters(window),
                (row, index) -> new Comparison(split(row, "current"), split(row, "previous")));
    }

    /** Every bucket of the period, empty ones too, oldest first. */
    List<Point> series(boolean ledger, DashboardPeriod.Window window) {
        Map<LocalDateTime, Split> listings = perBucket(ledger, window, LISTING_ROWS);
        Map<LocalDateTime, Split> users = perBucket(ledger, window, USER_ROWS);
        Map<LocalDateTime, Split> messages = perBucket(ledger, window, MESSAGE_ROWS);
        Map<LocalDateTime, Split> searches = perBucket(ledger, window, SAVED_SEARCH_ROWS);
        Map<LocalDateTime, Split> emails = perBucket(ledger, window, SENT_EMAIL_ROWS);
        List<Point> points = new ArrayList<>();
        for (LocalDateTime start : window.buckets()) {
            points.add(new Point(start,
                    listings.getOrDefault(start, Split.ZERO),
                    users.getOrDefault(start, Split.ZERO),
                    messages.getOrDefault(start, Split.ZERO),
                    searches.getOrDefault(start, Split.ZERO),
                    emails.getOrDefault(start, Split.ZERO).real()));
        }
        return points;
    }

    private Map<LocalDateTime, Split> perBucket(boolean ledger, DashboardPeriod.Window window, String rows) {
        String unit = window.period().bucket() == DashboardPeriod.Bucket.HOUR ? "hour" : "day";
        String sql = "WITH " + demoSets(ledger) + " SELECT date_trunc('" + unit + "', at AT TIME ZONE 'Europe/Paris') AS bucket, "
                + split("true", "n")
                + " FROM (" + rows + ") t WHERE at >= :from AND at < :to GROUP BY 1";
        Map<LocalDateTime, Split> counts = new HashMap<>();
        named.query(sql, parameters(window), (ResultSet row) -> {
            counts.put(row.getTimestamp("bucket").toLocalDateTime(), split(row, "n"));
        });
        return counts;
    }

    // ---- search and e-mail --------------------------------------------------------------

    SearchUsage search(DashboardPeriod.Window window) {
        // Stored per Paris day: the days the period touches, today included.
        MapSqlParameterSource days = parameters(window)
                .addValue("fromDay", window.from().toLocalDate())
                .addValue("toDay", window.to().toLocalDate())
                .addValue("previousFromDay", window.previousFrom().toLocalDate())
                .addValue("previousToDay", window.previousTo().toLocalDate());
        Map<String, Object> interpret = named.queryForMap("""
                SELECT coalesce(sum(requests) FILTER (WHERE day BETWEEN :fromDay AND :toDay), 0) AS requests,
                       coalesce(sum(requests) FILTER (WHERE day BETWEEN :previousFromDay AND :previousToDay), 0) AS requests_before,
                       coalesce(sum(failed) FILTER (WHERE day BETWEEN :fromDay AND :toDay), 0) AS failed,
                       coalesce(sum(failed) FILTER (WHERE day BETWEEN :previousFromDay AND :previousToDay), 0) AS failed_before,
                       coalesce(sum(with_not_understood) FILTER (WHERE day BETWEEN :fromDay AND :toDay), 0) AS partly,
                       coalesce(sum(with_not_understood) FILTER (WHERE day BETWEEN :previousFromDay AND :previousToDay), 0) AS partly_before
                  FROM search_interpret_usage
                 WHERE day >= :previousFromDay
                """, days);

        List<FilterUse> filters = new ArrayList<>();
        Count[] searches = {new Count(0, 0)};
        named.query("""
                SELECT filter,
                       coalesce(sum(searches) FILTER (WHERE hour >= :from AND hour < :to), 0) AS current,
                       coalesce(sum(searches) FILTER (WHERE hour >= :previousFrom AND hour < :previousTo), 0) AS previous
                  FROM search_filter_usage
                 WHERE hour >= :previousFrom
                 GROUP BY filter
                """, parameters(window), (ResultSet row) -> {
            FilterUse use = new FilterUse(row.getString("filter"), row.getLong("current"), row.getLong("previous"));
            if (SearchFilterUsage.ALL.equals(use.filter())) {
                searches[0] = new Count(use.current(), use.previous());
            } else if (use.current() > 0) {
                filters.add(use);
            }
        });
        filters.sort(java.util.Comparator.comparingLong(FilterUse::current).reversed()
                .thenComparing(FilterUse::filter));

        return new SearchUsage(
                new Count(number(interpret, "requests"), number(interpret, "requests_before")),
                new Count(number(interpret, "failed"), number(interpret, "failed_before")),
                new Count(number(interpret, "partly"), number(interpret, "partly_before")),
                searches[0],
                filters.size() > TOP_FILTERS ? List.copyOf(filters.subList(0, TOP_FILTERS)) : List.copyOf(filters),
                activeSavedSearches());
    }

    Emails emails(DashboardPeriod.Window window) {
        return named.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'SENT' AND sent_at >= :from AND sent_at < :to) AS sent,
                       count(*) FILTER (WHERE status = 'SENT' AND sent_at >= :previousFrom AND sent_at < :previousTo) AS sent_before,
                       count(*) FILTER (WHERE status = 'FAILED' AND created_at >= :from AND created_at < :to) AS failed,
                       count(*) FILTER (WHERE status = 'FAILED' AND created_at >= :previousFrom AND created_at < :previousTo) AS failed_before,
                       count(*) FILTER (WHERE status = 'SENT' AND email_type = 'SAVED_SEARCH_DIGEST'
                                          AND sent_at >= :from AND sent_at < :to) AS alerts,
                       count(*) FILTER (WHERE status = 'SENT' AND email_type = 'SAVED_SEARCH_DIGEST'
                                          AND sent_at >= :previousFrom AND sent_at < :previousTo) AS alerts_before
                  FROM outbox_email
                 WHERE created_at >= :previousFrom OR sent_at >= :previousFrom
                """, parameters(window), (row, index) -> new Emails(
                new Count(row.getLong("sent"), row.getLong("sent_before")),
                new Count(row.getLong("failed"), row.getLong("failed_before")),
                new Count(row.getLong("alerts"), row.getLong("alerts_before"))));
    }

    long activeSavedSearches() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM saved_search WHERE paused_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    // ---- helpers ------------------------------------------------------------------------

    private static MapSqlParameterSource parameters(DashboardPeriod.Window window) {
        return new MapSqlParameterSource()
                .addValue("from", Timestamp.from(window.from().toInstant()))
                .addValue("to", Timestamp.from(window.to().toInstant()))
                .addValue("previousFrom", Timestamp.from(window.previousFrom().toInstant()))
                .addValue("previousTo", Timestamp.from(window.previousTo().toInstant()));
    }

    private static long number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return null;
    }
}
