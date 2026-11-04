# Leasing offers (LOA and LLD)

A listing is either a sale or a leasing offer. This file says how an offer works and, because LOA
is consumer credit in France, exactly what the site shows about it and why.

**This is not legal advice.** It is a developer's reading of the public texts listed below, written
for a demonstration site whose offers are invented. Nobody with legal training has reviewed it.
Before real dealers publish real offers here, have a lawyer check this page, the wording and the
gaps listed under [What is not covered](#what-is-not-covered).

## The rules of an offer

- **Professional accounts only.** The server refuses anyone else with
  `LEASING_PROFESSIONAL_ONLY` (403), on creating, on editing, on publishing and on renewing. The
  form hides the choice from everyone else, but the rule is the server's.
- **The fields:** type (LOA or LLD), monthly payment TTC, first payment (apport or premier loyer),
  duration in months, mileage allowance per year and, for an LOA only, the purchase option price.
- **The total is computed, never typed:**
  `first payment + monthly × (duration − 1)`, plus the option price for an LOA. The server stores it,
  the request has no field to send one in, and a database constraint holds the same formula, so a
  row that disagrees cannot exist.
- **`priceAmount` on a leasing offer is the vehicle's cash price.** It is shown as "Prix au comptant
  du véhicule", which is one of the figures a contract has to state, but results never lead with
  it: they show the monthly payment.
- **A damaged vehicle cannot be leased.** The server refuses the combination and a constraint backs
  it up. This was my decision, not part of the brief: no dealer offers it, and the two rules would
  otherwise have to be reconciled in the search and the price indicator.
- **A dealer who stops being a professional** keeps the offers already online until they expire,
  but cannot publish or renew them. Taking offers down at once would be an unasked-for side effect
  of an admin's role change.

## Search

Sales and leasing offers never mix. A search is for one or the other, and the default is a sale, so
no existing link, alert or query changes. `offer=LEASING` on the API (`?offre=location` in the
address) is the only way to see offers.

Leasing filters: type, monthly payment, duration, minimum mileage allowance. Leasing sorts: newest,
monthly payment both ways, duration, mileage allowance. A leasing sort on a sale search is a 400,
and the address simply falls back to the default sort. Price, "Bonnes affaires" and the state of
the vehicle do not apply to a leasing search and are not offered there.

Catalogue suggestions come from sales only. Similar listings compare an offer with other offers
and a sale with other sales.

## Price indicator and La Cote

A leasing offer is never a comparable and never gets a verdict. `MarketPrice.comparable()`, the
verdict's `LATERAL` and `LaCote` all require `offer_type = 'SALE'`: the terms of a lease say
nothing about what the market pays for a car.

## What the site shows about an LOA

On the listing page, under the heading **Exemple représentatif**:

| Shown | Text or value | Basis |
|---|---|---|
| The instalments | "Puis N loyers mensuels TTC de X €", in the larger size | L. 312-6, L. 312-8 |
| The first payment | "Premier loyer (apport)" | L. 312-6 (part of the amounts due) |
| The duration | "Durée" | L. 312-6 |
| The mileage allowance | "Kilométrage" | Part of the offer; service-public lists it among a contract's terms |
| The cash price | "Prix au comptant du véhicule" | Service-public lists it among a contract's terms |
| The option price | "Option d'achat finale" | Same |
| The total | "Montant total dû en cas d'achat", in the larger size | L. 312-6, L. 312-8 |
| The mention | "Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager." in bold, larger than the other details | L. 312-5 (exact wording), L. 312-8 (size) |
| The withdrawal period | "Vous disposez d'un délai de rétractation de 14 jours à compter de l'acceptation du contrat." | L. 312-19, DGCCRF fact sheet |

The mention is also shown once above the results of a leasing search, because those results state
monthly payments.

No TAEG or taux débiteur is shown. Article L. 312-6 excludes them for a location avec option
d'achat, and the fields do not exist.

The rules on what advertising must not say (L. 312-10 on payment holidays and on suggesting credit
improves a budget, L. 312-11 on gifts) are not enforced by the site. The demo descriptions do not
say these things; a real dealer's free text could, and only moderation would catch it.

## What the site shows about an LLD

The same figures without an option, the total labelled "Coût total de la location", and the line
"Location sans option d'achat : le véhicule est restitué à la fin du contrat." No credit mention and
no withdrawal line: an LLD is a rental, the consumer-credit rules do not apply to it, and it carries
no statutory withdrawal period.

## What is not covered

These are known gaps, not oversights. I do not know whether each one is required in this setting,
which is the point of asking a lawyer.

- **Who the lender is.** Nothing names the financing company, and the fields have no place for it.
  An advertisement for real credit presumably has to.
- **The dealer's role.** A seller acting as a credit intermediary has its own disclosure duties.
  Nothing on the page addresses them.
- **Fees and required services.** L. 312-6 also asks for information on fees and on any accessory
  service required to obtain the credit, such as insurance. An offer has no field for either, so the
  example is silent about them.
- **"À partir de" on results.** A result shows "à partir de X €/mois" with the type, duration and
  mileage, as the brief asked. It does not repeat the full example on each card; the mention appears
  once above the list and the full example on the listing page. Whether that is enough for
  a list of offers, and whether "à partir de" is apt for one precise set of terms, is an open
  question.
- **The relative sizes on results.** L. 312-8 is applied on the listing page. On results, the monthly
  payment is the largest thing on the card, with the total not shown.
- **Upper limit.** The LOA formalism applies to contracts of up to 75 000 €. The site applies the same
  block to every LOA whatever its amount.
- **Radio, print or anything but this site.** Not relevant here, but the mention's wording differs on
  radio.

## Sources

Read in November 2026, while building this; check that they are still current.

- Code de la consommation, articles L. 312-5, L. 312-6, L. 312-8, L. 312-10 and L. 312-11:
  <https://www.legifrance.gouv.fr/codes/section_lc/LEGITEXT000006069565/LEGISCTA000032221981/>
- DGCCRF, fact sheet on LOA (27 October 2025):
  <https://www.economie.gouv.fr/dgccrf/les-fiches-pratiques/location-avec-option-dachat-loa-dun-vehicule-quelles-sont-les-regles-connaitre>
- Service-public, F2437 (verified 12 June 2026): <https://www.service-public.gouv.fr/particuliers/vosdroits/F2437>

## Changing this

The wording is in `frontend/src/app/shared/listing/leasing.fr.ts`. Do not reword `CREDIT_MENTION`:
it is the statutory text. `leasing-offer.spec.ts` holds it word for word, and a change to what the
block shows should change the table above in the same commit.
