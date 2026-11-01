package com.autox.search.interpret;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resolves a make or model name the model wrote to a catalogue entry, or to nothing.
 *
 * <p>Both sides go through {@code catalogue_search_key} (V16), the search bar's normalisation:
 * case, accents and spacing never decide a match ("Megane" is the Mégane, "SKODA" Škoda). Then,
 * strictest first:
 * <ol>
 *   <li>the keys are equal;</li>
 *   <li>they are equal once everything but letters and digits is removed ("mercedes benz",
 *       "serie3");</li>
 *   <li>for a make, exactly one make's key starts with what was written, of at least three
 *       characters ("mercedes"); for a model within a known make, the longest model whose name
 *       starts the written one at a word boundary ("golf gti" is the Golf).</li>
 * </ol>
 * Anything else is no match, and the caller reports the name as not understood. Ambiguity is
 * no match too: a filter on the wrong make is worse than none.
 *
 * <p>Inactive entries are never matched: the search cannot be filtered on them either.
 */
@Repository
class CatalogueMatcher {

    record Make(UUID id, String slug, String name) {
    }

    record Model(UUID id, String slug, String name, Make make) {
    }

    private record Keyed<T>(T entry, String key) {
    }

    private final JdbcTemplate jdbc;

    CatalogueMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Make> make(String written) {
        String key = key(written);
        if (compact(key).isEmpty()) {
            return Optional.empty();
        }
        List<Keyed<Make>> makes = jdbc.query("""
                SELECT id, slug, name, public.catalogue_search_key(name) AS key
                  FROM vehicle_make
                 WHERE active
                """, (row, index) -> new Keyed<>(
                new Make(row.getObject("id", UUID.class), row.getString("slug"), row.getString("name")),
                row.getString("key")));

        Optional<Make> exact = unique(makes.stream().filter(make -> make.key().equals(key)).toList());
        if (exact.isPresent()) {
            return exact;
        }
        Optional<Make> compact = unique(makes.stream()
                .filter(make -> compact(make.key()).equals(compact(key))).toList());
        if (compact.isPresent() || key.length() < 3) {
            return compact;
        }
        return unique(makes.stream().filter(make -> make.key().startsWith(key)).toList());
    }

    /** A model of {@code make}. */
    Optional<Model> model(Make make, String written) {
        String key = key(written);
        if (compact(key).isEmpty()) {
            return Optional.empty();
        }
        List<Keyed<Model>> models = jdbc.query("""
                SELECT id, slug, name, public.catalogue_search_key(name) AS key
                  FROM vehicle_model
                 WHERE active AND make_id = ?
                """, (row, index) -> new Keyed<>(
                new Model(row.getObject("id", UUID.class), row.getString("slug"), row.getString("name"), make),
                row.getString("key")), make.id());

        Optional<Model> exact = unique(models.stream().filter(model -> model.key().equals(key)).toList());
        if (exact.isPresent()) {
            return exact;
        }
        Optional<Model> compact = unique(models.stream()
                .filter(model -> compact(model.key()).equals(compact(key))).toList());
        if (compact.isPresent()) {
            return compact;
        }
        // "golf gti": the Golf. The longest, so "série 3 gt" is the Série 3 GT if the
        // catalogue has one, and the Série 3 if it does not.
        return models.stream()
                .filter(model -> key.startsWith(model.key() + " "))
                .max(Comparator.comparingInt(model -> model.key().length()))
                .map(Keyed::entry);
    }

    /**
     * A model named without its make ("une clio"), which then supplies the make. Exact or
     * compact equality only, and only when one model in the whole catalogue has that name: two
     * makes' "Série 1"-alikes are a question, not an answer.
     */
    Optional<Model> modelAlone(String written) {
        String key = key(written);
        if (compact(key).isEmpty()) {
            return Optional.empty();
        }
        List<Model> models = jdbc.query("""
                SELECT md.id, md.slug, md.name, mk.id AS make_id, mk.slug AS make_slug, mk.name AS make_name
                  FROM vehicle_model md
                  JOIN vehicle_make mk ON mk.id = md.make_id AND mk.active
                 WHERE md.active
                   AND regexp_replace(public.catalogue_search_key(md.name), '[^a-z0-9]', '', 'g')
                     = regexp_replace(public.catalogue_search_key(?), '[^a-z0-9]', '', 'g')
                """, (row, index) -> new Model(
                row.getObject("id", UUID.class), row.getString("slug"), row.getString("name"),
                new Make(row.getObject("make_id", UUID.class), row.getString("make_slug"),
                        row.getString("make_name"))), written);
        return models.size() == 1 ? Optional.of(models.getFirst()) : Optional.empty();
    }

    private String key(String written) {
        if (written == null || written.isBlank()) {
            return "";
        }
        String key = jdbc.queryForObject("SELECT public.catalogue_search_key(?)", String.class, written);
        return key == null ? "" : key;
    }

    private static String compact(String key) {
        return key.replaceAll("[^a-z0-9]", "");
    }

    private static <T> Optional<T> unique(List<Keyed<T>> matches) {
        return matches.size() == 1 ? Optional.of(matches.getFirst().entry()) : Optional.empty();
    }
}
