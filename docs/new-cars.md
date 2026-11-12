# New cars ("Neuf")

The "Neuf" section lists new and zero-kilometre cars sold by professionals. It is the ordinary
search, preset to new cars.

## How "new" is modelled

A listing has a condition, `vehicle_condition`: `NEW` or `USED`. There is no separate "0 km
stock" flag. A dealer's zero-kilometre car is `NEW`, with its few kilometres as its mileage.

**The rule:** a car is "neuf" when its condition is `NEW` **and** its seller is a professional.
- In SQL it is `ListingSearchRepository.soldNew(alias)`: the search, its count, the "Neuf /
  Occasion" filter and the /neuf page's make counts all use it.
- In Java it is `Listing.isSoldNew()` (and `Listing.soldNew(condition, role)` for a search row). The
  API returns it as `soldNew` on the listing page and on every result card.

## Enforced on the server

A private seller cannot list a car as new: `NEW_CAR_PROFESSIONAL_ONLY` (403). It is checked on the
seller's role, like leasing (`docs/leasing.md`), on creating, editing, publishing and renewing.

A private listing saved as `NEW` before the rule (none in the demo; production was not checked)
is read as used everywhere: no badge, "Occasion" in the filter. It cannot be published or renewed
again until its seller changes the condition. The listing form offers "Neuf" to dealers only.

## The /neuf page

- **The hero:** a short heading, and the home page's search card (`HomeFinder`) preset to
  `condition=NEW`, without the offer tabs. Its count, its button and "Plus de critères" name the
  new-car search.
- **The makes with new stock:** `GET /api/v1/listings/makes?condition=NEW`. That endpoint takes the
  search's criteria and counts with the search's own filters. So a make's count is exactly what
  `/recherche?etat=new&marque=…` then lists.
  - Only catalogue makes are listed. A make typed as free text is found by name in the search, which a
    catalogue make of the same name would also match, so its count could not be exact.
- **The latest arrivals:** the first page of the new-car search, newest first. Asked with
  `browse=true`, so opening /neuf is not counted as a search in the admin dashboard's search
  usage. A visitor's own search, from the card or a link, is counted as usual.

Every link leads to `/recherche?etat=new`, where all the other filters are.

## On a new car's page

- **Badge:** a "Neuf" badge above the title.
- **Key facts:** "État : Neuf" in place of the mileage tile, and the same on result cards and rows.
  The specification still gives the kilometres, marked "(véhicule neuf)".
- **No market badge:** La Cote and the market price compare used cars only
  (`vehicle_condition <> 'NEW'`), so a new car gets no "Prix du marché" badge.
- **Financing simulator:** still shown, as on any car for sale that is not damaged.

## Leasing

New cars offered in leasing are not in /neuf: like every search, it is for sales unless
`offre=location`. The filter works on leasing offers too.

## Demo data

The seed's `addMissingNew` step adds `DemoCatalogue.NEW_CARS` (15 of this year's models at list
price, 5 to 30 km) to the four demo dealers.
- **Procedure:** the same as the leasing and damaged steps. Through the API, recorded in the ledger,
  and known by title, so a second start adds nothing.
- **Setting:** `autox.demo.new-listings` (default 15) caps it.
- **Descriptions:** each car carries its own sentence, so no two read the same.
- **Titles:** no "neuf", "neuve" or "0 km": the badge says new, and the cars have a few kilometres.
  On a database seeded before, `retitleNewCars` renames them in place, through the ledger and
  without touching status, verdict or dates, before anything is matched by title.
  `DemoCatalogue.FORMER_TITLES` maps each old title to its new one. A new title is never another
  demo car's: a test holds that, and caught "Tesla Model Y Propulsion", which a used demo car
  already had.
