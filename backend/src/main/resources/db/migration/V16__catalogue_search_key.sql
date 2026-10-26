-- AutoX V16 — accent- and case-insensitive prefix search on the catalogue, for the search
-- bar's suggestions ("citro" finds Citroën, "SKODA" finds Škoda, "renault sce" the Scénic).

-- unaccent is a contrib extension shipped with PostgreSQL and marked trusted since 13, so
-- the database owner Flyway runs as may create it without being a superuser.
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

-- The one normalisation, used on both sides of every comparison: the stored names in the
-- indexes below, and what the visitor typed in the query. Lower case, accents and
-- ligatures folded (é → e, œ → oe, ß → ss), runs of white space made one space.
--
-- unaccent() itself is only STABLE — it reads its dictionary through the search path — and
-- an index expression must be IMMUTABLE. Naming the dictionary and the function by schema
-- removes that dependency, which is what makes declaring this wrapper IMMUTABLE honest.
CREATE FUNCTION public.catalogue_search_key(name TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT btrim(regexp_replace(lower(public.unaccent('public.unaccent'::regdictionary, name)), '\s+', ' ', 'g'))
$$;

COMMENT ON FUNCTION public.catalogue_search_key(TEXT) IS
    'Search form of a make or model name: lower case, unaccented, single-spaced. Indexed; change it only with a migration that rebuilds the indexes using it.';

-- text_pattern_ops: the prefix match is a byte-wise range (key ~>=~ q AND key ~<~ q || max),
-- which a btree with the default collation cannot answer and this operator class can.
CREATE INDEX ix_vehicle_make_search_key ON vehicle_make (public.catalogue_search_key(name) text_pattern_ops)
    WHERE active;

-- "a3": a model by its own name, whatever the make.
CREATE INDEX ix_vehicle_model_search_key ON vehicle_model (public.catalogue_search_key(name) text_pattern_ops)
    WHERE active;

-- "audi a": the models of one make whose name starts with the rest.
CREATE INDEX ix_vehicle_model_make_search_key
    ON vehicle_model (make_id, public.catalogue_search_key(name) text_pattern_ops)
    WHERE active;
