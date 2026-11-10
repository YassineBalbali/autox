# Dealer pages and the directory of professionals

`/professionnels` lists the professionals; `/professionnels/<slug>` is one of them. Migration
`V28`; backend in `com.autox.dealer`, frontend in `features/dealers`.

## Who is listed, and who has a page

- **The directory** lists a professional account that is neither suspended nor deleted, whose
  page an administrator has not hidden, with at least one listing the public search shows.
- **What is counted**, on the card, in the page's header and on its Acheter and Louer tabs, is
  exactly what the search lists on the page before any filter:
  `ListingSearchRepository.unfilteredMatch(offer)`, built by the search's own `appendFilters`, so it
  cannot drift from it. That means the visibility rule and the search's defaults, damaged vehicles
  left out. Counting with the visibility rule alone once put "36 annonces" over a list of 35: the
  damaged one was counted and not listed. A dealer whose only listings are damaged is not in the
  directory, as their page lists nothing until a visitor asks for damaged vehicles.
- **A page** answers for any such dealer, listings or not. A hidden page, a suspended or a deleted
  account answers **404** to the public, like an offline listing. The dealer still sees their
  hidden page, with the reason, and so do administrators. A suspended dealer cannot sign in at all,
  so nobody but an administrator sees theirs.
- **A private seller** has no page; their name on a listing leads nowhere.

## The slug

Made **once, by the database**, when the company profile is inserted (trigger
`tr_pro_profile_slug`), from the name the dealer shows: lower case, accents folded, anything else a
hyphen, 60 characters at most. A taken slug gets `-2`, `-3`… The first of two namesakes keeps the
plain one (existing dealers were given theirs oldest first). It **never changes**: a renamed
garage keeps its address, so links keep working, and a second trigger refuses an `UPDATE` of it.
In the database rather than in Java so that registration, a role change, the demo seed and a
test's `INSERT` all get one.

## Where a dealer is

The place on their company profile if they gave one (`geo_place_id`), otherwise **where most of
their listings online are**. The département is read from that postal code: two digits, three
overseas, `2A`/`2B` for Corsica. The directory's département choices are the ones it has dealers
in, with how many; the names come from `shared/dealer/departements.fr.ts`.

## The listings: the search, not a copy

A dealer's listings are `GET /api/v1/listings` with **`sellerId`**, a criterion appended to
`ListingSearchCriteria` and applied in `appendFilters` like the others. The page embeds the search
page itself (`SearchPage` with its `sellerId` input): the same filters, chips, sort, sale or
leasing switch, and the URL as the only state, on `/professionnels/<slug>?…`. What the search page
leaves out there: the alert (a saved search would not keep the seller), the natural-language note,
recent searches, and its `<h1>`, which is the dealer's name.

## Reaching a dealer

- **AutoX's messaging**, from the page: `POST /api/v1/dealers/{slug}/conversation`. A conversation
  about no listing (`listing_id` null, `via_dealer_page` true), one per visitor and dealer
  (`ux_conversation_dealer_page_buyer`), with the same rules as any other: signed in, verified
  address, the same rate limit as starting a conversation. It stays open while both accounts are.
  The inbox says "Depuis votre / sa page professionnelle" instead of "Annonce supprimée". The form is
  the listing's (`ContactSeller` given a `dealer`).
- **A phone number** only if the dealer ticked "Afficher ce numéro sur ma page professionnelle"
  (`show_phone`, off by default), and only the company's contact number, never the account's own.
- **Never an e-mail address**: `DealerPage` has no field for one.

## What the dealer sets

Mon compte → Entreprise: the presentation, the opening hours (free text, 1 000 characters), the
website, whether to show the phone, the logo, and a link to the page.

- **Website**: http or https only, checked by the API (`@Pattern`) and the database
  (`ck_pro_profile_website`), so a `javascript:` link cannot reach a public page. The form accepts
  `www.garage.fr` and sends `https://www.garage.fr`. V28 gave existing values their `https://`, or
  dropped them if they were not addresses. On the page: `target="_blank" rel="nofollow noopener
  noreferrer"`.
- **Logo**: the photos' three-step upload (slot, PUT to storage, confirm), and the same decoding
  and checks. What is kept is one JPEG re-encoded from the pixels, at most 400 px on its longer
  side: EXIF and GPS are in none of its bytes, and the orientation tag is applied first. The upload
  is deleted once the logo is made, the previous logo after the commit, and the logo with the
  account. Without one, the page shows the name's initials.

## Administrators

`POST /api/v1/admin/users/{id}/dealer-page/hide` with a reason (required), `…/show` with an
optional note. Recorded in `admin_user_action` as `HIDE_DEALER_PAGE` / `SHOW_DEALER_PAGE`
(previous and new value `VISIBLE` / `HIDDEN`), kept a year like the rest of that log. Hiding takes
the page and the directory entry away and nothing else: the account, its listings and its
conversations are untouched; suspending the account is a separate decision.

## Title and link previews

The page sets its title (`Garage Bellevue, professionnel à Lyon · AutoX`), its description (the
start of the dealer's presentation, or what they have online) and the Open Graph and Twitter tags,
the logo as the image when there is one (`core/ui/page-meta.ts`). **The site is rendered in the
browser**: a crawler that runs JavaScript reads these; a link preview that only reads the HTML
(most chat apps) gets `index.html`'s, the site's own. Server-side rendering or prerendering of
these pages would fix that; it is not built.

## Demo data

The four demo dealers get a description and opening hours (`DemoCatalogue.DEALER_PAGES`) through
the ledger, by address, only where they differ, so a second start changes nothing. The log line:

```
Demo dealer pages: gave 4 of 4 dealers a description and opening hours; nothing else changed.
```

and on later starts `Demo dealer pages: complete, nothing changed (4 dealers).` No logos: they keep
their initials.

## Not built

- An upload slot that is never confirmed leaves its object under `dealers/<id>/uploads/` in
  storage; nothing sweeps that prefix yet (listing photos have `ListingImageSweeper`).
- No map, no reviews, no opening hours as structured data (open now / closed), no address on the
  page: the address fields stay on the company profile and are not shown.
