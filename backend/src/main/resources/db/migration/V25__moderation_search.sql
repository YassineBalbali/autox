-- AutoX V25 — finding a listing on the moderation page: a search over the listing's names and
-- its seller, and the columns its filters and sorts read.
--
-- The search matches a word anywhere in the text ("308", "bellevue", "gmail"), so the indexes
-- are trigram indexes, which answer LIKE '%word%'. pg_trgm is a contrib extension shipped with
-- PostgreSQL and trusted since 13, like unaccent (V16).
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- Both sides go through catalogue_search_key (V16), the search bar's normalisation, so "citroen"
-- finds "Citroën" and "GARAGE" finds "Garage". The expression is repeated word for word in
-- ModerationSearchRepository: an index is used only for the expression it was built on.
--
-- The listing's own words. A catalogue make or model is not in these columns (make_text and
-- model_text are for vehicles the catalogue lacks); the query finds those through the catalogue.
CREATE INDEX ix_listing_moderation_text ON listing USING gin (
    public.catalogue_search_key(
        title || ' ' || coalesce(make_text, '') || ' ' || coalesce(model_text, '') || ' ' || coalesce(version_text, ''))
    public.gin_trgm_ops)
    WHERE deleted_at IS NULL;

-- The seller: display name and e-mail.
CREATE INDEX ix_app_user_moderation_text ON app_user USING gin (
    public.catalogue_search_key(display_name || ' ' || email) public.gin_trgm_ops);

-- A make or model found in the catalogue, or chosen as a filter, narrows the listings by id.
-- The indexes from V4 on these columns cover live listings only; moderation reads every state.
CREATE INDEX ix_listing_moderation_make ON listing (make_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_listing_moderation_model ON listing (model_id) WHERE deleted_at IS NULL;

-- The lists' orders: by date within a status, and by price.
CREATE INDEX ix_listing_moderation_published ON listing (status, published_at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_listing_moderation_updated ON listing (status, updated_at, id) WHERE deleted_at IS NULL;
CREATE INDEX ix_listing_moderation_price ON listing (status, price_amount, id) WHERE deleted_at IS NULL;
