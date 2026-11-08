package com.autox.listing;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Which listings a moderation list holds, searched, filtered and sorted. Plain SQL: the search
 * reaches into the catalogue and the seller's account, and the demo ledger may not exist.
 *
 * <p>It answers ids and a count; {@link ListingModerationService} loads the listings themselves.
 *
 * <p><b>The search.</b> Each word must be found in one of the listing's title, make, model and
 * version, or its seller's display name and e-mail, anywhere in them: "bellevue 308" is the
 * Garage Bellevue's 308s. Both sides go through {@code catalogue_search_key} (V16), the search
 * bar's normalisation, so case and accents do not matter. Every branch is an index (V25): the
 * listing's words by a trigram index, and a catalogue make or model, or a seller, by an id list
 * the planner computes once ({@code = ANY (ARRAY(...))}) and looks up in an index on listing.
 *
 * <p>Every value is bound. What is written into the statement comes from enums.
 */
@Repository
class ModerationSearchRepository {

    /** Matches V25's {@code ix_listing_moderation_text} exactly, or that index is not used. */
    static final String LISTING_TEXT = "public.catalogue_search_key(l.title || ' ' || coalesce(l.make_text, '') || ' '"
            + " || coalesce(l.model_text, '') || ' ' || coalesce(l.version_text, ''))";

    /** Matches V25's {@code ix_app_user_moderation_text}. */
    static final String SELLER_TEXT = "public.catalogue_search_key(s.display_name || ' ' || s.email)";

    /** Words beyond these are ignored: nobody types more, and each is four index lookups. */
    static final int MAX_WORDS = 6;

    private final NamedParameterJdbcTemplate jdbc;

    ModerationSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Found(List<UUID> ids, long total) {
    }

    Found find(ModerationView view, ModerationFilter filter, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String where = where(view, filter, parameters);

        Long total = jdbc.queryForObject("SELECT count(*) FROM listing l JOIN app_user u ON u.id = l.seller_id" + where,
                parameters, Long.class);
        List<UUID> ids = jdbc.query("SELECT l.id FROM listing l JOIN app_user u ON u.id = l.seller_id" + where
                        + " ORDER BY " + order(view, filter.sort()) + " LIMIT :limit OFFSET :offset",
                parameters, (rs, row) -> rs.getObject("id", UUID.class));
        return new Found(ids, total == null ? 0 : total);
    }

    /** Which of these listings the demo seed created. */
    Set<UUID> demo(Collection<UUID> ids) {
        if (ids.isEmpty() || !ledgerExists()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.query("""
                SELECT entity_id FROM demo_seed_ledger
                 WHERE entity_type = 'LISTING' AND entity_id IN (:ids)""",
                new MapSqlParameterSource("ids", ids), (rs, row) -> rs.getObject("entity_id", UUID.class)));
    }

    private String where(ModerationView view, ModerationFilter filter, MapSqlParameterSource parameters) {
        StringBuilder where = new StringBuilder(" WHERE l.deleted_at IS NULL AND ").append(switch (view) {
            case PENDING -> "l.status = 'ACTIVE' AND l.moderation_status = 'NOT_REVIEWED'";
            case LIVE -> "l.status = 'ACTIVE'";
            case REMOVED -> "l.status = 'REMOVED'";
        });

        List<String> words = words(filter.query());
        for (int i = 0; i < words.size(); i++) {
            String pattern = ":w" + i;
            // The pattern is the word's key, with its own % and _ taken literally.
            String like = " LIKE '%' || public.catalogue_search_key(" + pattern + ") || '%' ESCAPE '\\'";
            where.append(" AND (").append(LISTING_TEXT).append(like)
                    .append(" OR l.make_id = ANY (ARRAY(SELECT mk.id FROM vehicle_make mk WHERE public.catalogue_search_key(mk.name)")
                    .append(like).append("))")
                    .append(" OR l.model_id = ANY (ARRAY(SELECT md.id FROM vehicle_model md WHERE public.catalogue_search_key(md.name)")
                    .append(like).append("))")
                    .append(" OR l.seller_id = ANY (ARRAY(SELECT s.id FROM app_user s WHERE ").append(SELLER_TEXT)
                    .append(like).append(")))");
            parameters.addValue("w" + i, escapeLike(words.get(i)));
        }

        if (filter.make() != null && !filter.make().isBlank()) {
            // The catalogue's make, or the same name typed by a seller whose make it lacks.
            where.append(" AND (l.make_id = ANY (ARRAY(SELECT mk.id FROM vehicle_make mk")
                    .append(" WHERE public.catalogue_search_key(mk.name) = public.catalogue_search_key(:make)))")
                    .append(" OR (l.make_id IS NULL AND public.catalogue_search_key(l.make_text) = public.catalogue_search_key(:make)))");
            parameters.addValue("make", filter.make().trim());
        }
        if (filter.sellerRole() != null) {
            where.append(" AND u.role = :role");
            parameters.addValue("role", filter.sellerRole().name());
        }
        if (filter.damaged() != null) {
            where.append(filter.damaged() ? " AND l.damaged" : " AND NOT l.damaged");
        }
        if (filter.offer() != null) {
            where.append(" AND l.offer_type = :offer");
            parameters.addValue("offer", filter.offer().name());
        }
        if (filter.demo() != null) {
            String demo = ledgerExists()
                    ? "EXISTS (SELECT 1 FROM demo_seed_ledger d WHERE d.entity_type = 'LISTING' AND d.entity_id = l.id)"
                    : "FALSE";
            where.append(filter.demo() ? " AND " : " AND NOT ").append(demo);
        }
        if (filter.priceMin() != null) {
            where.append(" AND l.price_amount >= :priceMin");
            parameters.addValue("priceMin", filter.priceMin());
        }
        if (filter.priceMax() != null) {
            where.append(" AND l.price_amount <= :priceMax");
            parameters.addValue("priceMax", filter.priceMax());
        }
        return where.toString();
    }

    private static String order(ModerationView view, ModerationSort asked) {
        ModerationSort sort = asked == null ? ModerationSort.defaultFor(view) : asked;
        String date = switch (view) {
            case PENDING -> "l.created_at";
            case LIVE -> "l.published_at";
            case REMOVED -> "l.updated_at";
        };
        return switch (sort) {
            case OLDEST -> date + " ASC NULLS LAST, l.id ASC";
            case NEWEST -> date + " DESC NULLS LAST, l.id DESC";
            case PRICE_ASC -> "l.price_amount ASC, l.id ASC";
            case PRICE_DESC -> "l.price_amount DESC, l.id DESC";
        };
    }

    /** The words of a search, at most {@link #MAX_WORDS}. */
    static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.trim().split("\\s+")).limit(MAX_WORDS).toList();
    }

    /** So that a search for "50%" or "a_b" means those characters. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Whether the demo ledger exists. Asked first, because a query naming a table that does not
     * exist fails to plan, whatever branch of it would have run.
     */
    private boolean ledgerExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('public.demo_seed_ledger') IS NOT NULL",
                new MapSqlParameterSource(), Boolean.class));
    }
}
