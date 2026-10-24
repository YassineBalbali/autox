# Similar listings

"Annonces similaires" is at the foot of every listing page: three to six other live
listings, shown with the same cards as the search results. If there are fewer than three
good matches, the section is not on the page at all.

The rule is in `backend/src/main/java/com/autox/search/SimilarListings.java`, written as
SQL, and the section is `frontend/src/app/features/listing/similar-listings.ts`.

## The rule

A match is one of two kinds, and the first kind is shown before the second.

| Kind | Matches | Ranked by |
|---|---|---|
| The same model | Same catalogue make and model, registered within 3 years, priced within ±40 % | The price indicator's comparables first (same fuel, power within 15 %), then the nearest year, then the nearest mileage |
| The same kind of car for the money | Same body type, priced within ±20 %, any make | Same fuel first, then the nearest price, then the nearest year |

At most six are shown: same-model matches first, then the rest filled with the second kind.
With fewer than three, none are shown.

**Never shown:**
- the listing itself;
- anything not live (sold, paused, expired, deleted or rejected, by the same check as the
  search);
- anything priced in another currency;
- more than one of a seller's copies of the same car.

"The same car" means the same seller, model, version, year, fuel and gearbox, even when
the mileage or the price differs. A dealer with five identical Clios in stock fills one
card, the one closest to the listing. A seller's copies of the car being viewed are not
shown at all, because that car is already on the page.

## Why this way

- **It reuses the price indicator's comparables, loosened.** Its rule (same version, ±2
  years, same mileage band) leaves most listings with five comparables or fewer. That is
  right for a verdict, but too narrow for suggestions. The comparables still come first
  because they are the closest matches.
- **The same model has a price band, at twice the width.** Without one, the sixth card
  under an 18 000 € Peugeot 3008 diesel was a 30 000 € 3008 plug-in hybrid GT: the same
  name, but a different purchase for a buyer with that budget. I added this band. The
  brief asked only for the same model.
- **For other models, the price is most of the match.** That is why their band is the
  narrower ±20 %, and why the body type is the only other condition. A buyer with
  12 000 € for a small hatchback is shown small hatchbacks at about 12 000 €, whatever
  their make.
- **A listing whose make and model were typed in by hand only gets the second kind.** It
  has no catalogue model to share with other listings.
- **Nothing rather than one or two.** Showing nothing beats showing padding, which tells
  a buyer the site has nothing to offer.

## Computed when read

As with the price indicator, the result is never stored, so it cannot go out of date when
a listing sells or changes price. Each of the two kinds matches on columns that already
have an index: the same model on `ix_listing_live_market` (model and year), and the price
band on `ix_listing_live_price`. On a catalogue as small as the demo's, PostgreSQL scans
the table instead, which costs less at that size.

`GET /listings/{id}/similar` is public, rate-limited per address like the rest of the
public read API, and answers 404 when the listing itself is not public.

The page asks for the section only after the listing has loaded, so the listing never
waits for it. If the request fails, the page says nothing about it.

## On the local demo data

With the 120 seeded listings:
- **109** listings show six cards, **2** show five and **1** shows three.
- **8** show nothing: rare models with no sibling within 3 years, and no other car of the
  same body type within 20 % of their price, such as the two Corolla Touring Sports
  estates.
