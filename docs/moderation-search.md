# Finding a listing to moderate

`/admin/moderation`: each of the three lists (À vérifier, En ligne, Retirées) can be searched,
filtered, sorted, and read as cards or as rows. All of it is `GET /api/v1/admin/listings`, which
stays admin-only and is checked on every request like the rest of the moderation endpoints.

## The search

`q`: each word must be found in the listing's title, make, model or version, or in its seller's
display name or e-mail, anywhere in them. "bellevue 308" is the Garage Bellevue's 308s;
"h.dupre" finds the seller whose address starts that way.

- **Case and accents do not matter**, on either side: both go through `catalogue_search_key`
  (V16), the search bar's suggestions' normalisation. "citroen" finds "Citroën", "DUPRÉ" finds
  "Dupré", "oeuvre" finds "œuvre".
- **A catalogue make or model is found through the catalogue.** A listing that picked "Peugeot 208"
  from it has neither word in its own columns.
- **`%` and `_` mean themselves.** At most six words are used; the search is 100 characters.

## Filters and orders

| Filter | API | URL |
|---|---|---|
| Make, by name: the catalogue's, or the same name typed by a seller | `make` | `marque` |
| Seller type | `sellerRole` | `vendeur=particulier\|pro` |
| Damaged or not | `damaged` | `etat=endommage\|sain` |
| Sale or leasing | `offer` | `offre=vente\|leasing` |
| Real or demo seed | `demo` | `origine=reelle\|demo` |
| Cash price, bounds included | `priceMin`, `priceMax` (minor units) | `prix-min`, `prix-max` (euros) |

**Orders.** `sort`, in the URL `tri`:
- **Dates:** oldest or newest first. Each list sorts by its own date: when the listing was created
  in the queue, when it went online, and when it was taken down.
- **Price:** ascending or descending.
- **Defaults:** the queue is oldest first, the two other lists newest first.

**The URL holds all of it**, with the list and the page, so a filtered list can be reloaded or
shared. Moving to another list keeps the search and the filters, and starts from its first page
in its own order.

**Cards or rows** is a preference, kept in the browser (`autox.admin.moderation.layout`) and not
in the URL, as the search page's grid and list are. A page is ten cards or twenty-five rows. A row
carries the same decisions as a card; the refusal form takes the row's width while it is open.

## Indexes (V25)

Substring matching on accent-folded text is what trigram indexes answer, so V25 creates `pg_trgm`
(trusted, like `unaccent`; Neon has it). Every branch of a search word is an index:

- `ix_listing_moderation_text`: the listing's own words, by trigram.
- `ix_app_user_moderation_text`: the seller's name and e-mail, by trigram.
- `ix_listing_moderation_make` and `ix_listing_moderation_model`: a make or model found in the
  catalogue, or a seller found by name, gives an id list that the query looks up by id.

The trigram index expressions are repeated word for word in `ModerationSearchRepository`. An index
is only used for the exact expression it was built on, and `ModerationSearchIntegrationTest`
checks the plan uses both. Btree indexes on `(status, date or price, id)` serve the orders.
