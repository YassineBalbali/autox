-- AutoX V28: dealer pages and the directory of professionals (/professionnels).
--
-- A dealer's public page is their pro_profile row, under a slug. What the page shows that the
-- profile did not hold: opening hours, whether to show the contact phone, a logo. An
-- administrator can hide a page; the reason goes in admin_user_action like other actions on
-- accounts. A visitor can write to a dealer from the page: a conversation about no listing.

-- ---- the slug -------------------------------------------------------------------------
-- Made once, from the name the dealer shows, when the profile is created, and never changed
-- after: a renamed garage keeps its address, so links and bookmarks keep working. Made in the
-- database, so that registration, a role change, the demo seed and a test's INSERT all get one.

CREATE FUNCTION public.dealer_slug_base(name TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT COALESCE(NULLIF(left(btrim(regexp_replace(
               lower(public.unaccent('public.unaccent'::regdictionary, name)),
               '[^a-z0-9]+', '-', 'g'), '-'), 60), ''), 'professionnel')
$$;

ALTER TABLE pro_profile ADD COLUMN slug VARCHAR(72);

CREATE FUNCTION pro_profile_assign_slug() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    base      TEXT;
    candidate TEXT;
    n         INT := 1;
BEGIN
    IF NEW.slug IS NOT NULL THEN
        RETURN NEW;
    END IF;
    base := public.dealer_slug_base(COALESCE(
        (SELECT display_name FROM app_user WHERE id = NEW.user_id), NEW.company_name));
    candidate := base;
    WHILE EXISTS (SELECT 1 FROM pro_profile WHERE slug = candidate) LOOP
        n := n + 1;
        candidate := base || '-' || n;
    END LOOP;
    NEW.slug := candidate;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_pro_profile_slug BEFORE INSERT ON pro_profile
    FOR EACH ROW EXECUTE FUNCTION pro_profile_assign_slug();

-- The dealers already here, oldest first, so the first of two namesakes keeps the plain slug.
DO $$
DECLARE
    profile RECORD;
    base    TEXT;
    candidate TEXT;
    n       INT;
BEGIN
    FOR profile IN SELECT p.user_id, COALESCE(u.display_name, p.company_name) AS name
                     FROM pro_profile p JOIN app_user u ON u.id = p.user_id
                    ORDER BY p.created_at, p.user_id LOOP
        base := public.dealer_slug_base(profile.name);
        candidate := base;
        n := 1;
        WHILE EXISTS (SELECT 1 FROM pro_profile WHERE slug = candidate) LOOP
            n := n + 1;
            candidate := base || '-' || n;
        END LOOP;
        UPDATE pro_profile SET slug = candidate WHERE user_id = profile.user_id;
    END LOOP;
END;
$$;

ALTER TABLE pro_profile ALTER COLUMN slug SET NOT NULL;
ALTER TABLE pro_profile ADD CONSTRAINT ux_pro_profile_slug UNIQUE (slug);
ALTER TABLE pro_profile ADD CONSTRAINT ck_pro_profile_slug CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

-- Stable: an UPDATE cannot move it.
CREATE FUNCTION pro_profile_slug_is_stable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.slug IS DISTINCT FROM OLD.slug THEN
        RAISE EXCEPTION 'a dealer page''s slug does not change';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tr_pro_profile_slug_stable BEFORE UPDATE ON pro_profile
    FOR EACH ROW EXECUTE FUNCTION pro_profile_slug_is_stable();

-- ---- what the page shows --------------------------------------------------------------
ALTER TABLE pro_profile ADD COLUMN opening_hours TEXT;
ALTER TABLE pro_profile ADD CONSTRAINT ck_pro_profile_opening_hours
    CHECK (opening_hours IS NULL OR length(opening_hours) <= 1000);
-- Off unless the dealer turns it on: a number on a public page is a choice, not a default.
ALTER TABLE pro_profile ADD COLUMN show_phone BOOLEAN NOT NULL DEFAULT false;
-- The website, if given, is http(s) and nothing else: no javascript: link on a public page.
-- What was typed before the rule: "www.garage.fr" gains its https://, anything else that is not
-- an address is dropped rather than shown.
UPDATE pro_profile
   SET website_url = CASE
           WHEN website_url ~* '^[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$' THEN 'https://' || website_url
           ELSE NULL END
 WHERE website_url IS NOT NULL AND website_url !~* '^https?://[^\s/$.?#][^\s]*$';
ALTER TABLE pro_profile ADD CONSTRAINT ck_pro_profile_website
    CHECK (website_url IS NULL OR website_url ~* '^https?://[^\s/$.?#][^\s]*$');

-- ---- hidden by an administrator ---------------------------------------------------------
ALTER TABLE pro_profile ADD COLUMN hidden_at TIMESTAMPTZ;
ALTER TABLE pro_profile ADD COLUMN hidden_reason TEXT;
ALTER TABLE pro_profile ADD CONSTRAINT ck_pro_profile_hidden
    CHECK ((hidden_at IS NULL) = (hidden_reason IS NULL) AND (hidden_reason IS NULL OR length(hidden_reason) <= 2000));

ALTER TABLE admin_user_action DROP CONSTRAINT ck_admin_user_action;
ALTER TABLE admin_user_action ADD CONSTRAINT ck_admin_user_action
    CHECK (action IN ('SUSPEND', 'REACTIVATE', 'CHANGE_ROLE', 'HIDE_DEALER_PAGE', 'SHOW_DEALER_PAGE'));
-- A reason for hiding a page, as for a suspension; cleared only when the account is deleted (V26).
ALTER TABLE admin_user_action DROP CONSTRAINT ck_admin_user_action_reason;
ALTER TABLE admin_user_action ADD CONSTRAINT ck_admin_user_action_reason
    CHECK (action NOT IN ('SUSPEND', 'HIDE_DEALER_PAGE') OR reason IS NOT NULL OR target_email IS NULL);

-- ---- a conversation started from a dealer's page -----------------------------------------
-- About no listing: listing_id is null, as it already is once a listing is erased. The flag
-- tells the two apart, so the inbox can say "depuis sa page" rather than "annonce supprimée".
ALTER TABLE conversation ADD COLUMN via_dealer_page BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE conversation ADD CONSTRAINT ck_conversation_dealer_page
    CHECK (NOT via_dealer_page OR listing_id IS NULL);
-- One per visitor and dealer, as there is one per visitor and listing.
CREATE UNIQUE INDEX ux_conversation_dealer_page_buyer ON conversation (seller_id, buyer_id)
    WHERE via_dealer_page;

COMMENT ON COLUMN pro_profile.slug IS 'The dealer page''s address, /professionnels/<slug>. Set once at creation, never changed.';
COMMENT ON COLUMN pro_profile.show_phone IS 'Whether contact_phone_e164 appears on the public dealer page. The e-mail never does.';
