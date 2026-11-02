package com.autox.admin;

import com.autox.admin.AdminStats.Activity;
import com.autox.admin.AdminStats.DailyActivity;
import com.autox.admin.AdminStats.ListingsByStatus;
import com.autox.admin.AdminStats.MakeCount;
import com.autox.admin.AdminStats.PriceVerdicts;
import com.autox.admin.AdminStats.SearchUsage;
import com.autox.admin.AdminStats.Split;
import com.autox.search.ListingSearchRepository;
import com.autox.search.MarketPrice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Daily figures use Paris days, the site's. The 7- and 30-day figures are rolling, counted back
 * from now, except natural-language search, which is only stored per day.
 */
@Repository
class AdminStatsRepository {

    static final int TOP_MAKES = 10;
    private static final int DAYS = 30;

    private static final String TODAY = "(now() AT TIME ZONE 'Europe/Paris')::date";

    private final JdbcTemplate jdbc;

    AdminStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                        split("l.status = 'EXPIRED'", "expired"))
                + " FROM " + LISTINGS;
        return jdbc.queryForObject(sql, (row, index) -> new ListingsByStatus(
                split(row, "published"), split(row, "pending"), split(row, "rejected"),
                split(row, "sold"), split(row, "expired")));
    }

    /** Activity in the last {@code days} × 24 hours. */
    Activity activity(boolean ledger, int days) {
        String since = "created_at >= now() - make_interval(days => " + days + ")";
        Split listings = jdbc.queryForObject("WITH " + demoSets(ledger) + " SELECT "
                + split(since, "n") + " FROM " + LISTINGS, (row, index) -> split(row, "n"));
        Split users = jdbc.queryForObject("WITH " + demoSets(ledger) + " SELECT "
                + split(since, "n") + """
                 FROM (SELECT u.created_at, EXISTS (SELECT 1 FROM demo_user d WHERE d.id = u.id) AS demo
                         FROM app_user u) t
                """, (row, index) -> split(row, "n"));
        Split messages = jdbc.queryForObject("WITH " + demoSets(ledger) + " SELECT "
                + split(since, "n") + """
                 FROM (SELECT m.created_at, EXISTS (SELECT 1 FROM demo_user d WHERE d.id = m.sender_id) AS demo
                         FROM message m) t
                """, (row, index) -> split(row, "n"));
        return new Activity(listings, users, messages);
    }

    /** Every one of the last 30 Paris days, today included, oldest first, empty days too. */
    List<DailyActivity> daily(boolean ledger) {
        String sql = "WITH " + demoSets(ledger) + """
                , days AS (
                    SELECT generate_series(%1$s - %2$d, %1$s, interval '1 day')::date AS day
                ), new_listings AS (
                    SELECT (x.created_at AT TIME ZONE 'Europe/Paris')::date AS day,
                           count(*) FILTER (WHERE d.id IS NULL) AS real,
                           count(*) FILTER (WHERE d.id IS NOT NULL) AS demo
                      FROM listing x LEFT JOIN demo_listing d ON d.id = x.id
                     WHERE x.deleted_at IS NULL
                       AND x.created_at >= ((%1$s - %2$d)::timestamp AT TIME ZONE 'Europe/Paris')
                     GROUP BY 1
                ), new_users AS (
                    SELECT (u.created_at AT TIME ZONE 'Europe/Paris')::date AS day,
                           count(*) FILTER (WHERE d.id IS NULL) AS real,
                           count(*) FILTER (WHERE d.id IS NOT NULL) AS demo
                      FROM app_user u LEFT JOIN demo_user d ON d.id = u.id
                     WHERE u.created_at >= ((%1$s - %2$d)::timestamp AT TIME ZONE 'Europe/Paris')
                     GROUP BY 1
                )
                SELECT days.day,
                       coalesce(nl.real, 0) AS listings_real, coalesce(nl.demo, 0) AS listings_demo,
                       coalesce(nu.real, 0) AS users_real, coalesce(nu.demo, 0) AS users_demo
                  FROM days
                  LEFT JOIN new_listings nl ON nl.day = days.day
                  LEFT JOIN new_users nu ON nu.day = days.day
                 ORDER BY days.day
                """.formatted(TODAY, DAYS - 1);
        return jdbc.query(sql, (row, index) -> new DailyActivity(
                row.getObject("day", LocalDate.class),
                split(row, "listings"),
                split(row, "users")));
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

    /** The last {@code days} Paris days, today included. */
    SearchUsage searchUsage(int days) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT coalesce(sum(requests), 0) AS requests,
                       coalesce(sum(failed), 0) AS failed,
                       coalesce(sum(with_not_understood), 0) AS with_not_understood
                  FROM search_interpret_usage
                 WHERE day > %s - %d
                """.formatted(TODAY, days));
        return new SearchUsage(
                ((Number) totals.get("requests")).longValue(),
                ((Number) totals.get("failed")).longValue(),
                ((Number) totals.get("with_not_understood")).longValue());
    }
}
