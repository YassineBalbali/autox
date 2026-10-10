# Price history, account operations, and three fixed bugs

The session that closed the account lifecycle and the three correctness defects from
`docs/weaknesses.md`. Migrations `V11` and `V12`.

## Endpoints added

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/me/password` | required | change password; **returns a new token pair** |
| POST | `/api/v1/auth/password/forgot` | none | always 202, always the same body |
| POST | `/api/v1/auth/password/reset` | none | single-use token, 204 |
| GET | `/api/v1/me/sessions` | required | the account's live sessions |
| DELETE | `/api/v1/me/sessions/{id}` | required | end one session |
| GET | `/actuator/health` | none | plus `/liveness` and `/readiness` |

Nothing was added for price history: it is recorded, not exposed.

---

## 1. Price history

### What counts as history, and what does not

One row per asking price the public saw: `INITIAL` at first publication, `SELLER_EDIT` on
every later change. **Edits before the first publication are not recorded.**

That is a narrowing of "record every price change", and it is deliberate. A draft being
edited into shape is composition — nobody saw the old number, no buyer reacted to it, and
it carries no information about what the market will pay. The series is going to be the
training data for price estimation, and the difference between "this seller dropped their
price twice in a month" and "this seller typed three numbers into a form before publishing"
is the difference between signal and noise. Tests assert both halves.

Four smaller calls, in the same spirit:

- **A currency change is a price change.** An amount without its currency says nothing, so
  the currency is on every row and changing it alone appends one.
- **An edit that resubmits the same price appends nothing.** A form that sends every field
  on every save would otherwise fill the series with duplicates.
- **Re-publishing does not append.** `INITIAL` means "the first price the market saw", and
  by the second publication it already has.
- **A change while `PAUSED` does append.** The listing has been public and will be again;
  pausing is not drafting, and a seller re-pricing while hidden is exactly the signal worth
  keeping.

### Shape

There is no `previous_amount` column. The preceding row is the previous price, and a
denormalised copy is one more thing that can disagree with it; the delta is a window
function away. The table is append-only — nothing updates or deletes a row — and
`listing_id` is a plain column rather than a `@ManyToOne`, so recording a price does not
become a reason to load the listing aggregate.

### Why now

It was the one item in `docs/deferred.md` that got more expensive every day. Everything
else deferred can be built later from the same starting point; a price that was never
recorded cannot be recovered at any cost. Added before the frontend exists, so the series
starts at the first real listing.

---

## 2. Account operations

Before this, **registration was the only account operation that worked.** A user who forgot
their password was locked out permanently, and a user who lost a phone could not end that
session — even though `refresh_token` had been recording the device label, user agent and
address for exactly that purpose since the auth work, with nothing reading it back.

### Change password: every session is replaced, including the caller's

The obvious reading is "end the other sessions and leave mine". The server cannot do that.
An access token carries no reference to the refresh family it was issued alongside, so
there is no way to tell which session is calling. The two options were:

1. have the client post its current refresh token to a profile endpoint, and spare that
   family;
2. revoke every family and return a fresh pair in the response.

**The second was chosen.** It is simpler, has no partial-trust branch, and gives the same
guarantee in a form the server can actually keep: when the call returns, the only session
that works anywhere is the one in the response. The cost is that the client must store the
returned tokens — which is why the endpoint returns the full auth response rather than 204,
and why the Javadoc and the OpenAPI description say so in bold.

The current password is required even though the request is already authenticated. A valid
token may be a stolen one, and taking the account over permanently is the most valuable
thing to do with fifteen minutes of access.

A wrong current password is **400 `CURRENT_PASSWORD_INCORRECT`**, not 401. The caller's
token is perfectly valid; a 401 would send a client off to refresh it and retry instead of
telling the user they mistyped.

### Reset: the endpoint must not say whether an account exists

It is unauthenticated and anyone can post any address to it, so a response that differed
for a registered address would be a free list of the site's users. Consequences:

- **Always 202, always the same body**, whether the address is registered, suspended, or
  nonsense. A test asserts the two responses are byte-identical.
- **The throttle counts before the account lookup.** A cap that only applied to real
  accounts would leak existence through the 429. A test covers the unknown-address case
  specifically.
- **One error for every way a token can fail** — unknown, expired, spent, superseded. A
  legitimate user does the same thing in all four cases: ask for a new link.

Other decisions:

- **Only the newest link works.** Issuing a token supersedes any outstanding one, so
  clicking "forgot password" three times does not leave three live keys to the account in
  three inboxes. `used_at` and `superseded_at` are separate columns, so the trail
  distinguishes a reset that happened from a request that was abandoned — the second,
  repeated, is what a targeted attempt looks like.
- **Only the SHA-256 is stored**, as for refresh tokens. A dump of `password_reset_token`
  is not a set of working links. SHA-256 and not BCrypt because the token is already
  full-entropy random: slow hashing defends against guessable inputs, and a reset should
  not pay 250 ms to look up a row.
- **Thirty minutes.** The link is a bearer credential sitting in an inbox, and a user who
  asks for one acts on it within minutes. Five would make a slow mail relay look like a
  broken product.
- **A reset revokes every session.** It is the answer to "someone else may have my
  password", so leaving the other sessions alive — including the attacker's — would defeat
  the point.
- **A suspended or closed account gets no link.** It could not sign in with the new
  password anyway, and mailing one invites a support conversation that ends in "your
  account is suspended", which is something to say directly. An *unverified* account is
  eligible: it can already sign in, so being locked out of it is just as much of a dead end.
- **A reset does not verify the email address.** It arguably demonstrates control of the
  inbox, and flipping `PENDING_VERIFICATION` to `ACTIVE` here was considered and dropped:
  verification is a flow that does not exist yet, and inventing half of it inside the
  password form is how two half-flows end up disagreeing.

### Email: an interface and a logging implementation

`EmailSender` is one method. `LoggingEmailSender` writes the message to the log instead of
sending it, which is what makes the reset flow walkable in development — the link is in the
console.

It is also a loaded gun: the body of a reset email *is* a credential, so anyone who can read
the log can take over any account. It therefore warns at every startup, in those words.

Selection is by the explicit `autox.mail.provider` property, not by `@ConditionalOnMissingBean`.
A real provider is an `EmailSender` bean conditional on its own value of that property, which
makes switching a configuration change and means the two are never both in play — whereas a
missing-bean condition in application code depends on which `@Configuration` Spring happens
to read first.

`EmailMessage` carries the recipient's locale even though nothing localises yet. It is the
one piece of context a template cannot recover later, and leaving it out is what would make
localisation a refactor instead of an addition. The body is English, and says so in a
comment: there is no message-bundle infrastructure, and half of one would be worse than an
obvious gap.

### Sessions: keyed on the family, and not paginated

`id` in the response is the **rotation family**, not the token row. A session's token is
replaced on every refresh, so a row id would change under the client between two reads of
the list; the family is stable for the life of the session.

The ownership check is in the `UPDATE` statement rather than in a read beforehand, so there
is no window between the two and a mismatch simply updates nothing — which answers 404. Not
403: there is nothing to be learned here by guessing identifiers.

**This is the only list endpoint in the API without pagination**, against the project's own
rule. The bound is how many devices one person signs in from, which is single digits, and a
page parameter around a list that cannot grow is ceremony. Said out loud because the rule is
worth keeping everywhere else.

The list cannot mark which entry is the caller's own, for the same reason change-password
cannot spare it. A client that wants "this device" should match the `deviceLabel` it chose
at sign-in.

`USER_REVOKED` was added to the revocation reasons. A session ended from the owner's session
list is not a logout: the device holding it was never asked, and the trail should say which
one it was.

---

## 3. The three bugs

### IPv6 /64 instead of the full address

The live test of the public rate limit recorded the key `0:0:0:0:0:0:0:1`, because a request
to localhost arrives over IPv6. The general case is worse than the curiosity: a single
connection is routinely handed a **/64**, which is 18 billion billion addresses the caller
can choose from at will, so an IPv6 scraper keyed on the full address is never limited at
all — it uses the next one.

`ClientAddress.throttleKey` masks IPv6 to its /64 and leaves IPv4 alone. A /64 is the
smallest block an operator is expected to assign as a unit, which makes it the smallest unit
that behaves like one subscriber; masking to a /48 would start grouping unrelated customers
of the same provider together. IPv4-mapped addresses (`::ffff:203.0.113.7`) are treated as
the IPv4 hosts they are, because masking those would merge every mapped address in the world
into one counter. Brackets and `%eth0` zone indexes are stripped. An unparseable value keys
on itself — counted, not waved through, and not merged with every other unrecognised value.

**Applied to the login throttle as well**, not just the public rate limit. It is the same
defect in the same place, and a spray that walks its own subnet should count as one source.

**The full address is still recorded on `refresh_token`** for the session list. Masking is
the throttle's key, not the audit trail: a user looking at "where was this session created
from" wants the address.

One operational note: counters written before this change are keyed on full addresses and
will sit alongside the new ones until they decay, which is within 24 hours. Not worth a data
migration.

### The active-listing quota race

`requireQuotaAvailable` counted the seller's active listings and then published. Two
statements, at READ COMMITTED: two concurrent publications both read one below the limit,
both pass, and both commit.

Fixed with a **row lock on the seller** (`SELECT … FOR UPDATE` via
`AppUserRepository.lockForQuotaCheck`) taken before the count. The second request waits
there, and then counts the listing the first one created. The seller's own account row is
the natural thing to serialise on: the quota belongs to it, no other write path holds it for
long, and two different sellers never wait for each other.

Rejected alternatives: raising the isolation level to SERIALIZABLE, which makes every
transaction in the application pay for one check; and a PostgreSQL advisory lock, which
needs the UUID hashed into two integers and can collide with an unrelated key.

`QuotaConcurrencyIntegrationTest` is the **only concurrency test in the suite**, and it was
checked against the unfixed code: with the lock removed, four simultaneous publications all
succeed against a cap of two. That matters, because a concurrency test that would pass
either way is worse than none.

### Scheduled sweepers running on every instance

`@Scheduled` is per process. With one instance that is invisible, which is what made it
dangerous — "scale out the API" looks harmless. The image sweeper deletes objects from
storage as well as rows.

`ScheduledTaskCoordinator.runExclusively(name, work)` wraps all three sweeps in a
**PostgreSQL session-level advisory lock**. Three reasons it is the right tool rather than
a lock table or a library:

- the database is already the one piece of shared state every instance agrees on, so
  nothing new has to be deployed;
- the lock is held by a connection, so an instance that crashes mid-sweep releases it the
  moment its socket closes — no lease to expire, no stale-lock recovery to get wrong;
- `pg_try_advisory_lock` does not wait, so a second instance **skips** the run rather than
  queueing up to repeat work that has just been done.

The lock is taken and released on one connection, held open by a `ConnectionCallback` for
the duration; the work inside takes its own connections and transactions from the pool as
usual. Release is in a `finally`, and a test covers the failure path specifically — without
it the lock would leak for the life of the connection and the job would simply stop, with
nothing in the log to say why.

Deliberately **not ShedLock**. It earns its keep when jobs need a recorded run history or a
minimum interval between runs; these are three periodic idempotent sweeps, and a dependency
plus a table would buy nothing a lock does not.

`LoginThrottleSweeper` became **`AuthMaintenanceSweeper`**, because it now sweeps three
tables rather than one. Two of them were never swept at all: `deleteExpiredBefore` existed
on the refresh token repository with no caller, so session rows accumulated for ever. Dead
credentials are now kept for 30 days — long enough to answer "when did that session end?"
during an incident, short enough that the table stays about live sessions.

---

## 4. Health

`/actuator/health`, public, and the exposure list is an **allow-list of one**. Everything
else actuator offers — environment, configuration properties, bean graph, mappings, heap
dumps, log levels, Flyway history — is either a credential or a map of the system. A test
walks all of them and asserts none is served.

`show-details: never` and `show-components: never`, so the body is a status and nothing
more: no database vendor, no disk figures, no bucket names. The one thing that cannot be
suppressed is a `groups` array naming the two probe groups, which every Spring Boot
application has; the test asserts the response has exactly those two keys, so a future
version that adds a field is noticed here rather than in production.

**Liveness and readiness are separate signals.** Liveness answers "is this process alive" —
restarting an instance that is merely waiting on the database makes an outage worse.
Readiness answers "can it serve", which here means it can reach PostgreSQL.

**Object storage is deliberately absent from both.** Authentication, listings and search do
not need it, so an S3 outage should degrade image upload rather than take every instance out
of rotation. That is the same call `StorageReadinessCheck` already makes by logging instead
of failing startup.

Public because a load balancer has no account. It is therefore the one anonymous window into
the deployment, which is why most of the test is about keeping it opaque.

---

## Two things found on the way

**`src/test/resources/application.yml` replaces the main `application.yml`, it does not
merge with it.** Both are `application.yml` on the classpath and the test one wins, so
anything the tests need from production configuration has to be repeated there. This was
discovered when the health group configuration had no effect in tests, and it means the
production file has never been exercised by the suite. Worth knowing before trusting a test
that asserts behaviour driven by configuration — and worth revisiting: renaming the test
file to `application-test.yml` would make the two merge, but it would also silently change
what every existing test runs against, so it is not a change to make in passing.

**Four copies of the same six-line SHA-256 helper had accumulated**, each with its own catch
for an exception the JDK guarantees cannot happen. Collapsed into `com.autox.common.Sha256`.
