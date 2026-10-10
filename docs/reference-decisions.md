# Reference endpoints and the public rate limit

## Endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/v1/reference/enums` | none | every published enum's constants |
| GET | `/api/v1/reference/features` | none | 34 equipment codes, with `fr-FR` and `en` labels |
| GET | `/api/v1/reference/makes` | none | manufacturers — **empty until the catalogue is seeded** |
| GET | `/api/v1/reference/makes/{makeId}/models` | none | 404 on an unknown make |
| GET | `/api/v1/reference/countries` | none | open markets, with the currency's minor-unit scale |

All five are `Cache-Control: public, max-age=3600` with an `ETag`, so a repeat call is a 304
with no body.

---

## 1. Why these exist at all

The gap was: **nothing exposed the valid values of the API's enums, or the equipment
vocabulary.** A frontend had no choice but to hardcode both.

Hardcoding the enums is survivable — they are compile-time constants. Hardcoding the ~34
feature codes is not: they live in `vehicle_feature`, a migration can add one, and the failure
is *silent*. The new equipment simply never appears in the listing form, and nothing anywhere
reports a problem. These endpoints make the database the single source for all of it.

### What is published, and what is not

`ReferenceService.PUBLISHED_ENUMS` is an **explicit list**, not a classpath scan. A scan would
mean a client's dropdowns silently gain values the moment someone adds an internal enum, and
this map is a contract: what is in it is promised, what is not is private. `ModerationActionType`
and `TokenRevocationReason` are internal and deliberately absent.

`ErrorCode` **is** published, because clients are told to branch on `code` — so the set of
codes they may receive is part of the contract, not an implementation detail.

A test (`publishedSetCoversTheWireContract`) reflects over every request and response type and
fails if an enum reachable from one is not published. That assertion is the whole point: without
it this endpoint drifts from the API it describes, which is the problem it was built to solve.

### Values, not labels — and why

**The enums endpoint returns no display text.** This is the one decision here worth
disagreeing with, so the reasoning is explicit.

What a frontend cannot safely duplicate is the **set** of constants. What it can own perfectly
well is the French for `DIESEL`. Putting that French in the database means:

- copy lives where no non-developer can edit it anyway, and where changing it needs a migration;
- a translation table per enum — sixteen of them — to hold text that never varies per row;
- and the frontend still needs its own translation files for every other string in the app, so
  the result is two translation systems instead of one.

Against that, the argument *for* database labels is drift — and the drift that actually hurts
is the set of values, which this endpoint fixes. A value appearing here with no translation in
the Angular files is a **visible** gap; a hardcoded list silently omits it. That asymmetry is
the decision.

**Equipment is different and does carry labels**, from `vehicle_feature_translation`. Features
are rows, not constants: they are added by migration, they have a category and a display order
that are data, and the seeded `fr-FR` / `en` labels already existed. For something whose
identity lives in the database, the label belongs there too.

So: `labels` is a map keyed by BCP 47 tag rather than two fixed fields, so a third locale is
rows and not a schema change. A client with no row for its locale falls back to the
language-only tag, then to `en`, then to the code — the endpoint returns what exists rather
than guessing.

### Countries: no localised name, and still no currency symbol

`code` is ISO 3166-1 alpha-2, and a browser renders it in the user's own language with
`Intl.DisplayNames` better than any seeded string could. `name` is returned as the endonym,
for logs and admin screens.

`currency.symbol` stays unexposed, as the language audit found it. Symbol *placement* is a
locale rule, not a currency property — `1 234,56 €` in France, `€1,234.56` elsewhere — and
`Intl.NumberFormat` gets it right from the code and locale. Returning a bare symbol invites
the wrong rendering. What is returned instead is `currencyMinorUnit`, the one field a
price-rendering client genuinely cannot do without: every amount in this API is an integer of
the minor unit, and dividing by the wrong power of ten is wrong by a factor of a hundred.

### An empty `makes` array is the honest answer

Not an error, and not a placeholder. There is no catalogue; a client seeing none knows to use
the free-text make field, which is exactly what the listing API accepts today. `GET
/makes/{id}/models` on an unknown id is a **404, not an empty list** — those mean different
things, and a client that cannot distinguish them shows "no models" for a typo'd id.

### Caching

`ShallowEtagHeaderFilter`, registered against `/api/v1/reference/*` only. It buffers the whole
response to hash it, which is the right trade for a few kilobytes of vocabulary re-requested on
every page load, and the wrong one for search results that differ per caller.

**It saves the body, not the query**: a 304 still runs the lookup. Acceptable here — these are
small indexed reads — but worth stating rather than implying. A server-side cache would remove
even that, and is not worth the invalidation question while no endpoint can modify this data.

---

## 2. The public rate limit

### Reusing the login throttle, and renaming its table

The mechanism needed was the one already built: an atomic upsert counter with a decay window
and a `blocked_until` instant. So `V10` generalises it rather than adding a second.

That meant renaming. A table called `login_attempt_counter` holding a row per anonymous search
request is a trap for whoever reads it next, and `failure_count` would be saying "failure" about
traffic that never failed. `V10` renames the table to `throttle_counter`, the columns to
`event_count` / `first_event_at` / `last_event_at`, and adds `PUBLIC_READ` to the scope CHECK.
Pure `ALTER`s — no data moves, and it applied to a populated database in 110 ms.

### Flat, not progressive

The login throttle escalates and caps deliberately: the question there is "is this the account
owner getting it wrong", and locking a real user out of their own account is the failure to
avoid. Here there is no owner to protect and no reason to be gentle with the second offence —
so it is a flat limit with a flat block.

**Default: 300 requests per minute per address, blocked for a minute.** Five a second
sustained. A fast browsing session — search, scroll, open a listing, go back, refine — is
nearer one a second including the reference calls on load. Reaching 300 is a scraper.

Counted: `GET /api/v1/listings`, `GET /api/v1/listings/{id}`, and all of
`/api/v1/reference/**`. Not counted: anything that is not a GET (creating a listing POSTs to
the same path, and is bounded by needing an account), and `/api/v1/listings/mine*`.

### The rollback trap, avoided by shape rather than by annotation

This codebase has hit the same bug three times: a state change recorded, then an exception
thrown, and the record rolled back with it. Refresh-token replay detection, login throttling
and image failures each needed a `REQUIRES_NEW` bean.

Here it is avoided structurally. `PublicRateLimitService.record()` **returns a decision and
never throws**; the interceptor throws. The counter write therefore commits before anything is
refused, and no self-invocation subtlety can reintroduce the bug. A test
(`counterIsCommittedDespiteTheRefusal`) asserts the count keeps climbing across refused
requests — if it rolled back, the counter would be stuck at the limit for ever and the limit
would silently become permanent.

### Fixed window, not sliding

The counter resets once an address goes quiet for longer than the window. A sliding window
needs per-request timestamps, which is a much more expensive structure for a limit whose job is
to stop bulk scraping. The cost is a boundary effect: an address can send 300 at the end of one
window and 300 at the start of the next. Irrelevant against a scraper that needs sustained
throughput.

### Known limits of this — read before relying on it

- **One database upsert per public read, on the hot path.** One indexed statement, fine at MVP
  volume, and the wrong place for this long-term. A reverse proxy or CDN limits before the
  request reaches the application at all, costs nothing per request, and absorbs the volume
  this is meant to stop. **Treat the application-level limit as the floor, not the defence.**
- **The key is a single address, and IPv6 breaks that.** Observed during the live test: the key
  recorded was `0:0:0:0:0:0:0:1`, not `127.0.0.1`. Two consequences. The IPv4 and IPv6 forms of
  one client count separately. More importantly, a residential or hosted IPv6 allocation gives
  one host an enormous number of addresses, so **a scraper on IPv6 can rotate addresses within
  its own /64 and never be limited at all.** The fix is to key IPv6 on the /64 prefix rather
  than the full address. Not done — say the word and it is a short change to `clientIp`.
- **It trusts `X-Forwarded-For`.** `server.forward-headers-strategy=framework` is set, so a
  caller that can set that header freely forges the key and sidesteps both this and the login
  throttle. Only safe behind a proxy that overwrites it. Same caveat as the login throttle, now
  on an endpoint anyone can reach.
- **Authenticated callers are counted too** on the public paths. The limit is generous enough
  that it does not matter, and exempting them would mean a signed-in scraper is unlimited.
