package com.autox.catalog.suggest;

import com.autox.search.ListingSearchRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The query behind the search bar's suggestions.
 *
 * <p><b>Matching.</b> Both sides go through {@code catalogue_search_key} (V16): lower case,
 * accents folded, single spaces. A make matches when its name starts with what was typed;
 * a model when its own name does ("a3"), or when what was typed is a make's name, a space,
 * and the start of the model's ("audi a").
 *
 * <p><b>The prefix test is a range, not {@code LIKE}.</b> {@code key ~>=~ q AND key ~<~ q ||
 * U+10FFFF} selects exactly the keys starting with {@code q}, and the {@code text_pattern_ops}
 * indexes answer it whatever the plan. {@code LIKE ? || '%'} is turned into that range only
 * when the pattern is known while planning, which a reused prepared statement's generic plan
 * does not know. It also needs no escaping of a typed {@code %} or {@code _}.
 *
 * <p><b>No dead ends.</b> The counts join the live listings, with the same visibility rule
 * as search, so an entry with nothing online has no row and is not suggested.
 */
@Repository
class CatalogueSuggestionRepository {

    static final String SQL = """
            WITH typed AS (
                SELECT public.catalogue_search_key(?) AS k
            ), bounds AS (
                SELECT k, k || chr(1114111) AS k_end FROM typed
            ), make_hits AS (
                SELECT mk.id
                  FROM vehicle_make mk, bounds b
                 WHERE mk.active
                   AND public.catalogue_search_key(mk.name) ~>=~ b.k
                   AND public.catalogue_search_key(mk.name) ~<~ b.k_end
            ), model_hits AS (
                SELECT md.id
                  FROM vehicle_model md, bounds b
                 WHERE md.active
                   AND public.catalogue_search_key(md.name) ~>=~ b.k
                   AND public.catalogue_search_key(md.name) ~<~ b.k_end
                UNION
                SELECT md.id
                  FROM bounds b
                  JOIN vehicle_make mk
                    ON mk.active AND starts_with(b.k, public.catalogue_search_key(mk.name) || ' ')
                 CROSS JOIN LATERAL (
                       SELECT substr(b.k, length(public.catalogue_search_key(mk.name)) + 2) AS rest) r
                  JOIN vehicle_model md
                    ON md.make_id = mk.id AND md.active
                   AND public.catalogue_search_key(md.name) ~>=~ r.rest
                   AND public.catalogue_search_key(md.name) ~<~ (r.rest || chr(1114111))
            )
            SELECT * FROM (
                SELECT 'MAKE' AS type, mk.id AS make_id, mk.slug AS make_slug, mk.name AS make_name,
                       NULL::uuid AS model_id, NULL AS model_slug, NULL AS model_name,
                       mk.name AS label, count(*) AS listing_count
                  FROM make_hits h
                  JOIN vehicle_make mk ON mk.id = h.id
                  JOIN listing l ON l.make_id = mk.id AND %1$s
                 GROUP BY mk.id, mk.slug, mk.name
                UNION ALL
                SELECT 'MODEL', mk.id, mk.slug, mk.name, md.id, md.slug, md.name,
                       mk.name || ' ' || md.name, count(*)
                  FROM model_hits h
                  JOIN vehicle_model md ON md.id = h.id
                  JOIN vehicle_make mk ON mk.id = md.make_id AND mk.active
                  JOIN listing l ON l.model_id = md.id AND %1$s
                 GROUP BY mk.id, mk.slug, mk.name, md.id, md.slug, md.name
            ) suggestions
             ORDER BY type = 'MAKE' DESC, listing_count DESC, label, model_id NULLS FIRST
             LIMIT ?
            """.formatted(ListingSearchRepository.visible("l"));

    private final JdbcTemplate jdbc;

    CatalogueSuggestionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CatalogueSuggestion> find(String typed, int limit) {
        return jdbc.query(SQL, (row, index) -> new CatalogueSuggestion(
                CatalogueSuggestion.Type.valueOf(row.getString("type")),
                row.getObject("make_id", UUID.class),
                row.getString("make_slug"),
                row.getString("make_name"),
                row.getObject("model_id", UUID.class),
                row.getString("model_slug"),
                row.getString("model_name"),
                row.getString("label"),
                row.getLong("listing_count")), typed, limit);
    }
}
