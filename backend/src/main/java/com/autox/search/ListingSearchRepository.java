package com.autox.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Public search, written as SQL.
 *
 * <p>Not JPA. Three reasons, in order of weight:
 * <ul>
 *   <li>The keyset predicate is a <b>row comparison</b> — {@code (published_at, id) < (?, ?)} —
 *       which PostgreSQL turns into a single index-only scan. Expressed as JPQL's
 *       {@code a < ? OR (a = ? AND b < ?)} the planner handles it far less well, and this is
 *       the hot path of the whole product.</li>
 *   <li>Search returns a projection, not entities. Loading managed entities to copy six
 *       columns out of each would be wasted work and a dirty-checking cost on a read path.</li>
 *   <li>Which index is used actually matters here, and SQL is where that is decided.</li>
 * </ul>
 *
 * <p>Every value is bound as a parameter; no filter value is ever concatenated into the
 * statement. The only string interpolation is of sort columns and comparison operators that
 * come from {@link SearchSort}, which is an enum.
 */
@Repository
public class ListingSearchRepository {

    /**
     * The visibility predicate, matching the partial indexes from the schema work so the
     * planner can use them. The expiry check is additional: the sweeper runs every ten
     * minutes, and a listing must not be publicly visible in the gap.
     */
    private static final String VISIBLE = """
            l.status = 'ACTIVE'
              AND l.moderation_status <> 'REJECTED'
              AND l.deleted_at IS NULL
              AND (l.expires_at IS NULL OR l.expires_at > now())
            """;

    private static final String SELECT = """
            SELECT l.id,
                   l.title,
                   l.price_amount,
                   l.price_currency_code,
                   l.price_negotiable,
                   l.first_registration_year,
                   l.first_registration_month,
                   l.mileage_km,
                   l.fuel_type,
                   l.gearbox,
                   l.body_type,
                   l.drivetrain,
                   l.power_hp,
                   l.vehicle_condition,
                   l.published_at,
                   l.country_code,
                   COALESCE(gp.city, l.city)               AS city,
                   COALESCE(gp.postal_code, l.postal_code) AS postal_code,
                   COALESCE(mk.name, l.make_text)          AS make_name,
                   COALESCE(mo.name, l.model_text)         AS model_name,
                   l.version_text,
                   u.id                                    AS seller_id,
                   u.display_name                          AS seller_name,
                   u.role                                  AS seller_role
              FROM listing l
              JOIN app_user u ON u.id = l.seller_id
              LEFT JOIN vehicle_make mk  ON mk.id = l.make_id
              LEFT JOIN vehicle_model mo ON mo.id = l.model_id
              LEFT JOIN geo_place gp     ON gp.id = l.geo_place_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ListingSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchRow> search(ListingSearchCriteria criteria, SearchSort sort, SearchCursor cursor, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ").append(VISIBLE);

        appendFilters(criteria, sql, parameters);
        appendKeyset(sort, cursor, sql, parameters);

        sql.append(" ORDER BY ").append(sort.orderByClause())
                .append(" LIMIT :limit");
        parameters.addValue("limit", limit);

        return jdbc.query(sql.toString(), parameters, ListingSearchRepository::mapRow);
    }

    /**
     * How many listings match, counted no further than {@code cap + 1}.
     *
     * <p>The bound is the point. An unbounded {@code COUNT(*)} over an arbitrary filter
     * combination scans every matching row, so its cost grows with the catalogue and the
     * broadest searches — no filters at all — are the most expensive. Counting inside a
     * {@code LIMIT} stops the scan at the cap: the work is bounded by a constant however
     * large the table becomes, and a buyer is no worse off being told "more than 10 000"
     * than "37 412".
     *
     * <p>One past the cap is fetched so the caller can tell "exactly the cap" from "more".
     * The seller join is omitted — nothing filters on it — but the catalogue and place
     * joins stay, because the make, model and location filters read through them.
     */
    public int countUpTo(ListingSearchCriteria criteria, int cap) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("""
                SELECT count(*) FROM (
                    SELECT 1
                      FROM listing l
                      LEFT JOIN vehicle_make mk  ON mk.id = l.make_id
                      LEFT JOIN vehicle_model mo ON mo.id = l.model_id
                      LEFT JOIN geo_place gp     ON gp.id = l.geo_place_id
                     WHERE""").append(' ').append(VISIBLE);

        appendFilters(criteria, sql, parameters);
        sql.append(" LIMIT :limit) bounded");
        parameters.addValue("limit", cap + 1);

        Integer count = jdbc.queryForObject(sql.toString(), parameters, Integer.class);
        return count == null ? 0 : count;
    }

    /** The statement the search would run, for diagnostics and plan checks. */
    public String explain(ListingSearchCriteria criteria, SearchSort sort, SearchCursor cursor, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ").append(VISIBLE);
        appendFilters(criteria, sql, parameters);
        appendKeyset(sort, cursor, sql, parameters);
        sql.append(" ORDER BY ").append(sort.orderByClause()).append(" LIMIT :limit");
        parameters.addValue("limit", limit);

        return jdbc.query("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) " + sql, parameters,
                (ResultSet rs) -> {
                    StringBuilder plan = new StringBuilder();
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                    return plan.toString();
                });
    }

    private void appendFilters(ListingSearchCriteria criteria, StringBuilder sql, MapSqlParameterSource parameters) {
        if (criteria.makeId() != null) {
            sql.append(" AND l.make_id = :makeId");
            parameters.addValue("makeId", criteria.makeId());
        }
        if (criteria.modelId() != null) {
            sql.append(" AND l.model_id = :modelId");
            parameters.addValue("modelId", criteria.modelId());
        }
        // Matches the displayed name whether it came from the catalogue or from free text.
        if (hasText(criteria.make())) {
            sql.append(" AND COALESCE(mk.name, l.make_text) ILIKE :make");
            parameters.addValue("make", criteria.make().trim());
        }
        if (hasText(criteria.model())) {
            sql.append(" AND COALESCE(mo.name, l.model_text) ILIKE :model");
            parameters.addValue("model", criteria.model().trim());
        }

        appendFreeText(criteria.q(), sql, parameters);

        if (criteria.yearFrom() != null) {
            sql.append(" AND l.first_registration_year >= :yearFrom");
            parameters.addValue("yearFrom", criteria.yearFrom());
        }
        if (criteria.yearTo() != null) {
            sql.append(" AND l.first_registration_year <= :yearTo");
            parameters.addValue("yearTo", criteria.yearTo());
        }

        if (criteria.priceMin() != null) {
            sql.append(" AND l.price_amount >= :priceMin");
            parameters.addValue("priceMin", criteria.priceMin());
        }
        if (criteria.priceMax() != null) {
            sql.append(" AND l.price_amount <= :priceMax");
            parameters.addValue("priceMax", criteria.priceMax());
        }
        if (hasText(criteria.currency())) {
            sql.append(" AND l.price_currency_code = :currency");
            parameters.addValue("currency", criteria.currency().toUpperCase(Locale.ROOT));
        }

        if (criteria.mileageMin() != null) {
            sql.append(" AND l.mileage_km >= :mileageMin");
            parameters.addValue("mileageMin", criteria.mileageMin());
        }
        if (criteria.mileageMax() != null) {
            sql.append(" AND l.mileage_km <= :mileageMax");
            parameters.addValue("mileageMax", criteria.mileageMax());
        }

        appendEnumFilter(sql, parameters, "fuel_type", "fuelType", criteria.fuelType());
        appendEnumFilter(sql, parameters, "gearbox", "gearbox", criteria.gearbox());
        appendEnumFilter(sql, parameters, "body_type", "bodyType", criteria.bodyType());
        appendEnumFilter(sql, parameters, "drivetrain", "drivetrain", criteria.drivetrain());
        appendEnumFilter(sql, parameters, "vehicle_condition", "condition", criteria.condition());

        if (hasText(criteria.countryCode())) {
            sql.append(" AND l.country_code = :countryCode");
            parameters.addValue("countryCode", criteria.countryCode().toUpperCase(Locale.ROOT));
        }
        if (hasText(criteria.location())) {
            // Unaccent is not installed, so this matches what the seller typed. Exact-prefix
            // on the postal code, contains on the city.
            sql.append(" AND (COALESCE(gp.city, l.city) ILIKE :locationLike"
                    + " OR COALESCE(gp.postal_code, l.postal_code) LIKE :locationPrefix)");
            parameters.addValue("locationLike", "%" + criteria.location().trim() + "%");
            parameters.addValue("locationPrefix", criteria.location().trim() + "%");
        }

        if (criteria.hasBoundingBox()) {
            sql.append(" AND l.latitude BETWEEN :latMin AND :latMax"
                    + " AND l.longitude BETWEEN :lonMin AND :lonMax");
            parameters.addValue("latMin", criteria.latMin());
            parameters.addValue("latMax", criteria.latMax());
            parameters.addValue("lonMin", criteria.lonMin());
            parameters.addValue("lonMax", criteria.lonMax());
        }
    }

    /** More words than this are ignored: each one is another four-column comparison per row. */
    private static final int MAX_TERMS = 6;

    /**
     * One AND-ed clause per word, each matching the title, make, model or version.
     *
     * <p>The user's text is bound, never concatenated — the only thing interpolated is
     * the parameter name, {@code q0}…{@code q5}, which is built here from a counter. And
     * the LIKE wildcards in it are escaped: unescaped, a search for {@code %} would match
     * every listing and one for {@code 100%} would match "1000 km", because to LIKE a
     * percent sign means "anything".
     *
     * <p>This is a substring scan, not an index lookup. It runs over the rows the other
     * filters and the visibility predicate have already narrowed, which is fine at the
     * size of catalogue this serves today; a trigram index, or a search engine, is the
     * answer when it is not, and nothing here would have to change shape for either.
     */
    private static void appendFreeText(String q, StringBuilder sql, MapSqlParameterSource parameters) {
        if (!hasText(q)) {
            return;
        }
        String[] words = q.trim().split("\\s+");
        for (int index = 0; index < Math.min(words.length, MAX_TERMS); index++) {
            String name = "q" + index;
            sql.append(" AND (l.title ILIKE :").append(name).append(" ESCAPE '\\'")
                    .append(" OR COALESCE(mk.name, l.make_text) ILIKE :").append(name).append(" ESCAPE '\\'")
                    .append(" OR COALESCE(mo.name, l.model_text) ILIKE :").append(name).append(" ESCAPE '\\'")
                    .append(" OR l.version_text ILIKE :").append(name).append(" ESCAPE '\\')");
            parameters.addValue(name, "%" + escapeLike(words[index]) + "%");
        }
    }

    /** Makes {@code %}, {@code _} and the escape character itself match literally. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static <E extends Enum<E>> void appendEnumFilter(StringBuilder sql, MapSqlParameterSource parameters,
                                                             String column, String name, List<E> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(values.size());
        values.forEach(value -> names.add(value.name()));
        sql.append(" AND l.").append(column).append(" IN (:").append(name).append(")");
        parameters.addValue(name, names);
    }

    /**
     * The keyset predicate, as a row comparison so PostgreSQL can satisfy it from the index
     * rather than filtering. This is what makes page 500 cost the same as page 1.
     */
    private static void appendKeyset(SearchSort sort, SearchCursor cursor,
                                     StringBuilder sql, MapSqlParameterSource parameters) {
        if (cursor == null) {
            return;
        }
        sql.append(" AND (").append(sort.column()).append(", l.id) ")
                .append(sort.keysetComparator())
                .append(" (:cursorValue, :cursorId)");
        parameters.addValue("cursorValue", cursor.typedSortValue());
        parameters.addValue("cursorId", cursor.id());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static SearchRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new SearchRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getLong("price_amount"),
                rs.getString("price_currency_code"),
                rs.getBoolean("price_negotiable"),
                rs.getShort("first_registration_year"),
                (Integer) rs.getObject("first_registration_month"),
                rs.getInt("mileage_km"),
                rs.getString("fuel_type"),
                rs.getString("gearbox"),
                rs.getString("body_type"),
                rs.getString("drivetrain"),
                (Integer) rs.getObject("power_hp"),
                rs.getString("vehicle_condition"),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getString("country_code"),
                rs.getString("city"),
                rs.getString("postal_code"),
                rs.getString("make_name"),
                rs.getString("model_name"),
                rs.getString("version_text"),
                rs.getObject("seller_id", UUID.class),
                rs.getString("seller_name"),
                rs.getString("seller_role"));
    }

    /** One search result, straight from the projection. */
    public record SearchRow(
            UUID id,
            String title,
            long priceAmount,
            String currencyCode,
            boolean priceNegotiable,
            short firstRegistrationYear,
            Integer firstRegistrationMonth,
            int mileageKm,
            String fuelType,
            String gearbox,
            String bodyType,
            String drivetrain,
            Integer powerHp,
            String condition,
            Instant publishedAt,
            String countryCode,
            String city,
            String postalCode,
            String make,
            String model,
            String version,
            UUID sellerId,
            String sellerName,
            String sellerRole) {
    }

    /** Cover images for a page of results, in one query rather than one per row. */
    public Map<UUID, Map<String, CoverVariant>> findCoverImages(List<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
                SELECT i.listing_id, v.variant, v.storage_key, v.width, v.height
                  FROM listing_image i
                  JOIN listing_image_variant v ON v.image_id = i.id
                 WHERE i.listing_id IN (:ids)
                   AND i.status = 'READY'
                   AND i.position = 0
                """;

        Map<UUID, Map<String, CoverVariant>> covers = new java.util.HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", listingIds), rs -> {
            UUID listingId = rs.getObject("listing_id", UUID.class);
            covers.computeIfAbsent(listingId, key -> new java.util.LinkedHashMap<>())
                    .put(rs.getString("variant"), new CoverVariant(
                            rs.getString("storage_key"), rs.getInt("width"), rs.getInt("height")));
        });
        return covers;
    }

    public record CoverVariant(String storageKey, int width, int height) {
    }

    /**
     * How many confirmed photos each listing on a page has, in one query. A listing with none
     * is absent from the map. Served by {@code ix_listing_image_listing}.
     */
    public Map<UUID, Integer> countPhotos(List<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }

        String sql = """
                SELECT listing_id, count(*) AS photos
                  FROM listing_image
                 WHERE listing_id IN (:ids)
                   AND status = 'READY'
                 GROUP BY listing_id
                """;

        Map<UUID, Integer> counts = new java.util.HashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", listingIds), rs -> {
            counts.put(rs.getObject("listing_id", UUID.class), rs.getInt("photos"));
        });
        return counts;
    }
}
