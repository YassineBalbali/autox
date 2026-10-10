# Where the backend is weakest

An honest assessment, written at the point the MVP API is complete and before the frontend
starts. Ordered by how much damage each would actually do, not by how hard it is to fix.

Things already catalogued as deliberately deferred are in `docs/deferred.md` and are not
repeated here, with one exception: moderation, because calling it "deferred scope" undersells
what its absence means.

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

## 3. A user who forgets their password is permanently locked out

There is no password reset, and no password change for a signed-in user either. There is also
no way to list or revoke sessions, so a user who loses a phone cannot end that session — even
though `refresh_token` records the device label, user agent and IP for exactly that purpose,
and family revocation already works.

Every one of these needs transactional email, which is one provider decision away. But as it
stands the account lifecycle is a dead end: **registration is the only account operation that
works.** For a product about to acquire real users, that is not a deferral, it is a defect.

## 4. The backend is operationally blind

- No health or readiness endpoint — `actuator` is not even a dependency, so nothing can tell a
  load balancer whether an instance is serving.
- No metrics. No request rate, no latency percentiles, no count of how often the rate limiter
  or login throttle fires.
- Plain-text logs, and no request correlation id except on 500s.
- No CI. The 167 tests pass; nothing runs them on a push.

Each is individually small. Together they mean that **if this were deployed tomorrow, the first
indication of a problem would be a user complaining.** `actuator` plus a `/health` probe is
twenty minutes and should probably happen before the frontend, because the frontend will want
something to point a dev proxy at.

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

## 6. The active-listing quota has a race

`requireQuotaAvailable` counts the seller's active listings and then publishes, in one
transaction at READ COMMITTED. Two concurrent publish requests from the same seller both read
`active = 4` against a limit of 5, both pass the check, and both commit — leaving six.

Impact is small: a private seller gets one extra free listing, and it takes deliberately
concurrent requests. But it is a real correctness bug, it is in the code that enforces the only
monetisable limit in the product, and it will matter more once paid tiers exist. The fix is a
`SELECT … FOR UPDATE` on the seller row before counting, or an advisory lock keyed on the
seller id. Cheap; not done.

## 7. Three scheduled jobs with no coordination between instances

`ListingExpirySweeper` (10 min), `ListingImageSweeper` (15 min) and `LoginThrottleSweeper`
(hourly) are plain `@Scheduled` methods. **Every instance runs all of them.**

The throttle sweeper is a single idempotent DELETE and is genuinely safe. The other two are
not obviously so: the image sweeper deletes rows *and* storage objects, and two instances
racing on the same expired upload will duplicate the work at best.

Nothing is wrong today because there is one instance. But "scale out the API" is a change that
looks harmless and is not, and there is currently nothing in the code to stop it going wrong.
Needs leader election, a locking library such as ShedLock, or moving the sweepers out of the
API process entirely.

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
- **No concurrency testing at all.** Every test is sequential. Nothing is known about behaviour
  under parallel load, which is also how item 6 above went unnoticed.

## 9. Rate limiting is weaker than it looks against a determined scraper

Detail in `docs/reference-decisions.md`, but the summary belongs here:

- The key is a **single IP address**, so a scraper on IPv6 rotates addresses within its own /64
  and is never limited. This was visible in the live test, where the key recorded was
  `0:0:0:0:0:0:0:1` rather than `127.0.0.1`.
- It trusts `X-Forwarded-For`, so without a proxy that overwrites the header, a caller forges
  the key and sidesteps both this and the login throttle.
- It costs one database write per public read — fine now, and the wrong place for this at
  volume.

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
- **Price is mutated in place with no history.** Not a defect in anything that exists, but it
  is the one gap that gets *more* expensive every day — see `docs/deferred.md`.

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
- **The test suite.** 167 integration tests against real PostgreSQL and real S3-compatible
  storage. No mocked authentication, no mocked SQL, no in-memory database pretending to be
  Postgres. Several real bugs were found by tests that a mock would have passed — the EXIF
  orientation failure most recently, which 132 tests had missed because every generated test
  image happened to have no EXIF at all.
