package com.autox.dealer;

import com.autox.listing.OfferType;
import com.autox.search.ListingSearchRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The professionals the public can see, and what their page says. SQL, like the search, and for
 * the same reason: one statement does the counting and the filtering.
 *
 * <h2>Who is listed</h2>
 *
 * <p>A professional account that is neither suspended nor deleted, whose page an administrator
 * has not hidden, with at least one listing the public search shows. The page itself answers for
 * any such dealer, listings or not; the directory only lists the ones with something to show.
 *
 * <h2>What is counted</h2>
 *
 * <p>Exactly what the search lists on the dealer's page before a visitor filters anything:
 * {@link ListingSearchRepository#unfilteredMatch}, the search's own visibility rule and defaults,
 * for sales and for leasing. So the header, the Acheter and Louer tabs and the directory card agree:
 * a damaged vehicle, which the search leaves out unless asked for, is not counted either.
 *
 * <h2>Where a dealer is</h2>
 *
 * <p>The place on their company profile if they gave one, otherwise where most of their listings
 * online are. The département is read from that place's postal code: two digits, three overseas,
 * 2A and 2B for Corsica.
 */
@Repository
public class DealerRepository {

    /** Not suspended, not deleted, a professional: an account that may have a public page. */
    static final String PUBLIC_ACCOUNT = """
            u.role = 'PROFESSIONNEL' AND u.status NOT IN ('SUSPENDED', 'DELETED') AND u.deleted_at IS NULL""";

    private static final String DEPARTEMENT = """
            CASE WHEN %1$s IS NULL THEN NULL
                 WHEN %1$s ~ '^9[78]' THEN left(%1$s, 3)
                 WHEN %1$s ~ '^20' THEN CASE WHEN %1$s < '20200' THEN '2A' ELSE '2B' END
                 ELSE left(%1$s, 2) END""";

    /**
     * Every dealer with a public page, with their listings online counted by offer and their place.
     * One row per dealer; the directory filters it, the page reads one row of it.
     */
    private static final String DEALERS = """
            SELECT p.user_id, p.slug, u.display_name AS name, p.company_name, p.logo_storage_key,
                   u.created_at AS member_since, p.hidden_at, p.hidden_reason,
                   p.description, p.opening_hours, p.website_url, p.show_phone, p.contact_phone_e164,
                   COALESCE(pg.city, loc.city) AS city,
                   COALESCE(pg.postal_code, loc.postal_code) AS postal_code,
                   counts.sale, counts.leasing,
                   (%3$s) AS public_account
              FROM pro_profile p
              JOIN app_user u ON u.id = p.user_id
              LEFT JOIN geo_place pg ON pg.id = p.geo_place_id
             CROSS JOIN LATERAL (
                   SELECT count(*) FILTER (WHERE %1$s) AS sale,
                          count(*) FILTER (WHERE %2$s) AS leasing
                     FROM listing l
                    WHERE l.seller_id = u.id AND (%1$s OR %2$s)) counts
              LEFT JOIN LATERAL (
                   SELECT COALESCE(gp.city, l.city) AS city, COALESCE(gp.postal_code, l.postal_code) AS postal_code
                     FROM listing l
                     LEFT JOIN geo_place gp ON gp.id = l.geo_place_id
                    WHERE l.seller_id = u.id AND (%1$s OR %2$s)
                    GROUP BY 1, 2
                    ORDER BY count(*) DESC, 1, 2
                    LIMIT 1) loc ON true
            """.formatted(ListingSearchRepository.unfilteredMatch(OfferType.SALE),
                    ListingSearchRepository.unfilteredMatch(OfferType.LEASING), PUBLIC_ACCOUNT);

    /** The directory's rows: a public account, a page not hidden, something online. */
    private static final String LISTED = """
            WITH d AS (%s)
            SELECT d.*, (%s) AS departement
              FROM d
             WHERE d.public_account AND d.hidden_at IS NULL AND d.sale + d.leasing > 0
            """.formatted(DEALERS, DEPARTEMENT.formatted("d.postal_code"));

    private final NamedParameterJdbcTemplate jdbc;

    DealerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the directory can be narrowed by. Each is optional. */
    public record Filter(String name, String departement, String city) {
    }

    public List<DealerRow> directory(Filter filter, DealerSort sort, int limit, long offset) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String sql = "SELECT * FROM (" + LISTED + ") listed WHERE true" + where(filter, parameters)
                + " ORDER BY " + orderBy(sort) + " LIMIT :limit OFFSET :offset";
        parameters.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql, parameters, DealerRepository::row);
    }

    public long count(Filter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM (" + LISTED + ") listed WHERE true"
                + where(filter, parameters), parameters, Long.class);
        return count == null ? 0 : count;
    }

    /** The départements the directory has dealers in, with how many, for the filter's choices. */
    public List<DealerDirectory.DepartementCount> departements() {
        return jdbc.query("""
                SELECT departement, count(*) AS dealers FROM (%s) listed
                 WHERE departement IS NOT NULL
                 GROUP BY departement ORDER BY departement""".formatted(LISTED),
                new MapSqlParameterSource(),
                (rs, n) -> new DealerDirectory.DepartementCount(rs.getString("departement"), rs.getInt("dealers")));
    }

    /** A dealer's row by its slug, whoever it is: whether the public may see it is the service's to say. */
    public Optional<DealerRow> bySlug(String slug) {
        return jdbc.query("""
                WITH d AS (%s)
                SELECT d.*, (%s) AS departement FROM d WHERE d.slug = :slug""".formatted(
                        DEALERS, DEPARTEMENT.formatted("d.postal_code")),
                new MapSqlParameterSource("slug", slug), DealerRepository::row).stream().findFirst();
    }

    /** The slug of a professional's page, for a link from their listing; empty when the page is not public. */
    public Optional<String> publicSlugOf(UUID sellerId) {
        return jdbc.queryForList("""
                SELECT p.slug FROM pro_profile p JOIN app_user u ON u.id = p.user_id
                 WHERE p.user_id = :id AND p.hidden_at IS NULL AND %s""".formatted(PUBLIC_ACCOUNT),
                new MapSqlParameterSource("id", sellerId), String.class).stream().findFirst();
    }

    private static String where(Filter filter, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder();
        if (hasText(filter.name())) {
            // Accent- and case-insensitive, on the name shown and the company's registered name.
            sql.append(" AND (public.catalogue_search_key(listed.name) LIKE :name"
                    + " OR public.catalogue_search_key(listed.company_name) LIKE :name)");
            parameters.addValue("name", "%" + likeEscape(searchKey(filter.name())) + "%");
        }
        if (hasText(filter.departement())) {
            sql.append(" AND listed.departement = :departement");
            parameters.addValue("departement", filter.departement().trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (hasText(filter.city())) {
            sql.append(" AND (public.catalogue_search_key(listed.city) LIKE :city OR listed.postal_code LIKE :postal)");
            parameters.addValue("city", "%" + likeEscape(searchKey(filter.city())) + "%");
            parameters.addValue("postal", likeEscape(filter.city().trim()) + "%");
        }
        return sql.toString();
    }

    private static String orderBy(DealerSort sort) {
        return switch (sort) {
            case LISTINGS -> "listed.sale + listed.leasing DESC, public.catalogue_search_key(listed.name), listed.slug";
            case NAME -> "public.catalogue_search_key(listed.name), listed.slug";
        };
    }

    /** What catalogue_search_key does to the stored names, done to what was typed. */
    private static String searchKey(String text) {
        String folded = java.text.Normalizer.normalize(text.trim().toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return folded.replace("œ", "oe").replace("æ", "ae").replace("ß", "ss").replaceAll("\\s+", " ");
    }

    /** % and _ typed by a visitor are letters, not wildcards. */
    private static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static DealerRow row(ResultSet rs, int n) throws SQLException {
        return new DealerRow(
                rs.getObject("user_id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("company_name"),
                rs.getString("logo_storage_key"),
                rs.getTimestamp("member_since").toInstant(),
                rs.getBoolean("public_account"),
                rs.getTimestamp("hidden_at") == null ? null : rs.getTimestamp("hidden_at").toInstant(),
                rs.getString("hidden_reason"),
                rs.getString("description"),
                rs.getString("opening_hours"),
                rs.getString("website_url"),
                rs.getBoolean("show_phone"),
                rs.getString("contact_phone_e164"),
                rs.getString("city"),
                rs.getString("postal_code"),
                rs.getString("departement"),
                rs.getInt("sale"),
                rs.getInt("leasing"));
    }

    /** One dealer, everything the directory and the page may need; never returned as it is. */
    public record DealerRow(UUID userId, String slug, String name, String companyName, String logoKey,
                            Instant memberSince, boolean publicAccount, Instant hiddenAt, String hiddenReason,
                            String description, String openingHours, String websiteUrl, boolean showPhone,
                            String contactPhone, String city, String postalCode, String departement,
                            int saleListings, int leasingListings) {
    }
}
