# Where the backend is weakest

An honest assessment, written at the point the MVP API is complete and before the frontend
starts. Ordered by how much damage each would actually do, not by how hard it is to fix.

Things already catalogued as deliberately deferred are in `docs/deferred.md` and are not
repeated here, with one exception: moderation, because calling it "deferred scope" undersells
what its absence means.

> **Updated after the session that followed.** Five of the ten below were acted on, and the
> entries are marked **FIXED** or **PARTLY FIXED** in place rather than deleted — what was
> wrong and why is worth more than a shorter document. In short: the account lifecycle (3),
> the quota race (6), the sweeper coordination (7) and the IPv6 rate-limit key (9) are
> closed, and a health endpoint closed part of (4). Items **1, 2, 5 and 8 stand unchanged**,
> and 1 and 2 are still the two that carry legal exposure. See
> `docs/account-and-correctness-decisions.md`.

---

## 1. There is no way to take a listing down

The single worst thing about the current state.

Listings are **public and unauthenticated** the moment a seller publishes. Nothing in the API
can remove one. The schema for it is complete and unused — `listing.moderation_status`,
`moderation_action`, `ModerationActionType` — but there is no endpoint, so a fraudulent,
illegal or abusive listing can only be dealt with by someone connecting to the database and
writing SQL.

Combined with the next item, this is the gap that carries legal exposure rather than just
product incompleteness.

**Why it is worse than it looks:** the schema being ready makes it *feel* handled. It is not.

## 2. No content safety on uploads, on a public gallery

Anything that decodes as an image can be uploaded and is then publicly readable at a signed
URL. No virus scanning, no image moderation, no hash-matching against known illegal content.

The privacy half is done properly — EXIF including GPS is stripped from the variants *and* the
stored original, so a seller's home coordinates do not sit in the bucket. The content half is
absent entirely.

This and the point above are the same project and should be built together.

## 3. A user who forgets their password is permanently locked out — FIXED

*Was:* no password reset, no password change, and no way to list or revoke sessions, so a
user who lost a phone could not end that session — even though `refresh_token` recorded the
device label, user agent and IP for exactly that purpose. **Registration was the only account
operation that worked.** Called a defect rather than a deferral at the time, and treated as
one.

*Now:* change password (requires the current one, replaces every session), forgotten-password
reset by emailed single-use token, and a session list with per-session revocation. 22 tests.

**One gap remains, and it is a deployment step rather than a defect:** there is no real mail
provider. `EmailSender` has a logging implementation that prints the reset link to the
console, which is how the flow is usable in development and is a **credential leak in
production** — anyone who can read the log can take over any account. It warns at every
startup. A provider is one bean and one property.

Also still missing: email verification is never *sent* (the column and the status exist, and
nothing populates them), and there is no "sign out everywhere" button — though changing the
password does exactly that.

## 4. The backend is operationally blind — PARTLY FIXED

- ~~No health or readiness endpoint.~~ **Fixed:** `/actuator/health`, plus separate
  `/liveness` and `/readiness` probes, public, status only, with everything else actuator
  offers shut off and a test asserting so.
- **No metrics.** No request rate, no latency percentiles, no count of how often the rate
  limiter or login throttle fires. The actuator dependency is now present, so this is a
  configuration change and a scrape target away — but it is not done.
- **Plain-text logs**, and no request correlation id except on 500s. This is now the largest
  remaining piece of the item.
- ~~**No CI.**~~ **Fixed:** `.github/workflows/backend.yml` runs `clean verify` — the full
  228-test suite plus packaging — on every push and on pull requests into `main`.

Still true that **if this were deployed tomorrow, the second indication of a problem would be
a user complaining** — the first is now a load balancer noticing.

## 5. Login is a CPU exhaustion vector

BCrypt at cost 12 is deliberate and right — roughly 250 ms per hash. The login endpoint also
hashes against a dummy value when the email is unknown, which is correct: it keeps the response
constant-time and stops the endpoint being an account-enumeration oracle.

The consequence is that **every login attempt costs ~250 ms of CPU, including the ones that
were never going to succeed.** The throttle counts *failures*, and the per-IP threshold is 20
before any delay. An attacker spraying distinct addresses from distinct sources burns a core at
about four requests a second each, without ever tripping a counter.

This is a known trade — removing the dummy hash would reintroduce enumeration, and lowering the
cost weakens every stored password. The right fixes are elsewhere: a proof-of-work or CAPTCHA
on repeated failures, and rate limiting at the proxy. Worth being aware of rather than
surprised by.

## 6. The active-listing quota has a race — FIXED

*Was:* `requireQuotaAvailable` counted the seller's active listings and then published, at
READ COMMITTED. Two concurrent publications from one seller both read `active = 4` against a
limit of 5, both passed, and both committed — leaving six.

*Now:* a row lock on the seller is taken before the count, so the pair is atomic for that
seller. The test was validated against the unfixed code: with the lock removed, four
simultaneous publications all succeed against a cap of two.

**It remains the only concurrency test in the suite** — see item 8.

## 7. Three scheduled jobs with no coordination between instances — FIXED

*Was:* `ListingExpirySweeper` (10 min), `ListingImageSweeper` (15 min) and
`LoginThrottleSweeper` (hourly) were plain `@Scheduled` methods, so **every instance ran all
of them**. The image sweeper deletes storage objects as well as rows.

*Now:* all three run under a PostgreSQL session-level advisory lock via
`ScheduledTaskCoordinator`. A second instance skips the run rather than queueing to repeat
it, and an instance that crashes mid-sweep releases the lock when its socket closes.
`LoginThrottleSweeper` became `AuthMaintenanceSweeper` and now also purges the two tables
nothing was purging — expired refresh tokens and spent reset tokens.

## 8. Search is correct but has only been proven at 20,000 rows

The query plans are measured rather than assumed, which is the right standard and found four
real index defects across two tasks. All four sorts are plain index scans with no sort nodes,
and page 500 costs what page 1 costs. That part is solid.

What has *not* been established:

- **20,000 rows is a small marketplace.** La Centrale carries hundreds of thousands. The plans
  should hold — keyset pagination over a partial index is the shape that scales — but "should"
  is doing work in that sentence, and the one honest claim is that it is untested above 20k.
- **The free-text make/model name filter scans the whole visible set** (16,000 rows removed by
  filter, of 20,000). A direct consequence of the deferred catalogue, and it resolves itself
  when the frontend sends `makeId` — but if free-text search gets real traffic first, this is
  the first thing to hurt.
- **Almost no concurrency testing.** One test now covers the quota race; everything else is
  sequential. Nothing is known about behaviour under parallel load, which is how item 6 went
  unnoticed in the first place.

## 9. Rate limiting is weaker than it looks against a determined scraper — PARTLY FIXED

Detail in `docs/reference-decisions.md` and
`docs/account-and-correctness-decisions.md`, but the summary belongs here:

- ~~The key is a single IP address, so a scraper on IPv6 rotates within its own /64 and is
  never limited.~~ **Fixed:** IPv6 is keyed on the /64 prefix, for the public rate limit and
  the login throttle both. Verified live — the recorded key is now
  `0:0:0:0:0:0:0:0/64`.
- **It still trusts `X-Forwarded-For`**, so without a proxy that overwrites the header a
  caller forges the key and sidesteps both this and the login throttle. The README says so
  under "Deploying behind a proxy"; it is a deployment requirement, not something the
  application can fix on its own.
- **It still costs one database write per public read** — fine now, and the wrong place for
  this at volume.

It raises the cost of casual scraping, which is worth having. It is not a defence against
someone who wants the data.

## 10. Smaller things that will be felt

- **Pre-signed read URLs are generated per response and expire in six hours**, so listing
  responses do signing work and image URLs cannot be cached by a CDN or across sessions. One
  environment variable (`AUTOX_S3_PUBLIC_BASE_URL`) switches to stable CDN URLs; until it is
  set, image delivery is the least efficient part of the system.
- **The MinIO dev image is an unpinned moving tag**, so two developers can get different builds
  and CI can break with no commit behind it.
- **Accents are not normalised in location matching**, so "Saint-Étienne" and "Saint-Etienne"
  are different places. `GeoPlace.normalize` exists and is unreachable until the gazetteer
  lands.
- ~~**Price is mutated in place with no history.**~~ **Fixed** — it was the one gap that got
  more expensive every day, so it went first. `listing_price_history` records the opening
  price at publication and every change after it. Not exposed by any endpoint; it exists as
  the input to price estimation.

---

## What is actually solid

Stated for balance, and because knowing what not to re-examine is useful.

- **The error contract.** One RFC 9457 shape everywhere, with a stable `code` and a flat
  `errors` array. Consistent across every endpoint, and tested as such.
- **Token handling.** Rotation on every use, family revocation on replay, hashed storage,
  nothing reversible in the database. The replay detection is correct *and* tested to actually
  fire, which is the part most implementations get wrong.
- **The language-neutral API.** Audited: no `@JsonValue` overrides, no translatable display
  strings in any response, and a test asserting two different `Accept-Language` headers return
  byte-identical bodies. The French frontend can be built without fighting the API.
- **Schema integrity.** Flyway owns it, Hibernate validates against it and refuses to start on
  drift. The XOR constraints, composite foreign keys and deferred unique constraints do real
  work that application code would otherwise have to be trusted to do.
- **The test suite.** 228 integration tests against real PostgreSQL and real S3-compatible
  storage. No mocked authentication, no mocked SQL, no in-memory database pretending to be
  Postgres. Several real bugs were found by tests that a mock would have passed — the EXIF
  orientation failure most recently, which 132 tests had missed because every generated test
  image happened to have no EXIF at all.
