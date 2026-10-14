# Where AutoX is weakest

An honest assessment, written when the MVP is feature-complete — backend and frontend —
and before any deployment work. It replaces the backend-only version written before the
frontend existed; what became of that version's ten items is in the table at the end.

Two lists, kept apart on purpose:

1. **Risks** — what would hurt a person, or the operator, if this were public tomorrow.
2. **Weaknesses** — where the product is incomplete or unproven, with nobody harmed by it.

Each is ordered by how much damage it would do, not by how hard it is to fix. Things that
were knowingly left out of scope are in `docs/deferred.md`; they are repeated here only
where "deferred" undersells what their absence means.

> **Direction since this was written:** AutoX is deployed as a public portfolio demo — no
> real users, invented listings, labelled as such on every page. The risks that only matter
> with real users (dealer verification, legal pages, account deletion, email verification)
> are knowingly left open for that reason. `docs/deployment.md` says what the demo will and
> will not do.

Nothing below is legal advice. Where a law is named, it is to say *a lawyer needs to look
at this*, not to say what the answer is.

---

## 1. Security and privacy risks if this went public tomorrow

### R1. Email does not exist, and three things rest on it — blocker

`autox.mail.provider=log` is the only sender. Consequences, in order of severity:

- **The password reset link is written to the application log.** Anyone who can read the
  log can take over any account. It warns at every startup; it does not refuse to start.
- **A user who forgets their password cannot recover it**, because nothing is sent.
- **No address is ever verified.** `email_verified` is never set. Anyone can register with
  an address that is not theirs and publish under it, and a seller's address may reach
  nobody. A comment in `AuthService.requireLoginAllowed` says verification "attaches to
  publishing a listing instead" — **it does not; nothing checks it.**

*Before going public:* a real `EmailSender`, a startup failure (not a warning) when the
provider is `log` outside development, and verification enforced at publication.

### R2. "Professionnel" is self-declared, uncapped, and displayed as fact — blocker

Anyone can register as a dealer by typing a company name and a registration number.
Neither is checked. That account then:

- has **no cap on live listings** (`professionnel-active-limit: 0`) — the private cap of
  five is the only flood control there is, and choosing the other radio button removes it;
- is shown to buyers with a **"Professionnel" tag** and its company name on every listing.

So the cheapest way to flood the site and the cheapest way to look trustworthy are the
same checkbox. In France a trade seller also carries legal obligations a private one does
not, so presenting an unverified account as one is a consumer-protection question as well.

*Before going public:* cap unverified dealers like private sellers (the per-account
`active_listing_limit` override already exists), and either verify the registration
number or stop displaying the tag until someone has.

### R3. A moderator can remove a listing but cannot stop the person — high

Moderation is post-publication: a listing is public the moment it is published and
reviewed afterwards. That was a deliberate MVP choice and is workable only with the things
around it, which are missing:

- **No account suspension.** `UserStatus.SUSPENDED` exists, blocks sign-in, and **no
  endpoint sets it.** A seller whose listing was refused publishes the next one.
- **A suspended seller's listings would stay public anyway**: the search's visibility rule
  checks the listing, not its seller. An access token already issued keeps working for up
  to 15 minutes.
- **Nobody can report a listing.** The footer's "Signaler une annonce" is declared and
  omitted because the page does not exist. A marketplace hosting user content is expected
  to offer notice-and-action (in the EU, under the Digital Services Act).
- **Uploads are not scanned.** Anything that decodes as an image is public until a
  moderator sees it: no hash-matching against known illegal material, no malware scan.
- **Nothing alerts anyone** that the queue is growing, and it is worked oldest-first.

Re-review after an edit (added in the last change) closes the approve-then-swap hole. It
does not shorten the window in which unreviewed content is public — that window is the
design.

*Before going public:* suspension (endpoint, UI, and seller status in the visibility
predicate), a report button feeding the queue, and a decision about whether first
publications by a new account should wait for review. `PENDING_REVIEW` is reserved in the
schema for exactly that.

### R4. A 90-day credential in `localStorage` — medium (was high: the policy now exists)

The refresh token is readable by any script on the origin, so one XSS is a durable account
takeover — including an administrator's, who can read every seller's email address.
`docs/frontend-decisions.md` accepted this *on the condition of a Content-Security-Policy*.

**That condition is now met.** The production build writes a policy into the page and into
`_headers`: scripts from the site's own origin only, no `eval`, `connect-src` and
`img-src` limited to the API and photo storage, no framing, plus `nosniff`,
`Referrer-Policy`, `Permissions-Policy` and HSTS on https. Verified in a browser against
the production build: no violation in normal use, and an injected inline script, an inline
handler, a third-party script, `eval`, and an attempt to send the session to another
origin by `fetch`, image or form were all refused. The API sends its own strict policy.

What remains: **`style-src` allows inline styles**, which Angular needs without a
per-response nonce; and on a host that does not read `_headers`, only the `<meta>` form
applies — no `frame-ancestors`, no HSTS — unless the headers are configured by hand.

### R5. Abuse controls stop at the login form — high

- ~~**Registration is not rate-limited.**~~ **Fixed:** five attempts per hour per client
  address, successful or not, counted before the lookup and the hash, on the same counter
  table as the other throttles.
- **Login has the same CPU cost per attempt**, by design (the dummy hash that prevents
  account enumeration). The throttle counts failures per address and per IP; a spray
  across addresses and sources burns a core without tripping it.
- **Every throttle trusts `X-Forwarded-For` — its first value.** A platform proxy that
  appends to the header leaves that value to the client, who then chooses their own key and
  none of the limits apply. A configuration change (`docs/deployment.md` §5.2), depending
  on the host; the most important item on the deployment list.
- **No CAPTCHA or proof-of-work anywhere.**

### R6. The legal surface of a public site is absent — high, and not an engineering call

No legal notice, terms, or privacy policy (the footer omits the links because the pages do
not exist), and no acceptance of terms at registration. For personal data specifically:

- **A user cannot delete their account or export their data.** There is no endpoint.
  `UserStatus.DELETED` exists and nothing sets it.
- **A private seller's display name, city and postal code are public** on every listing,
  with the month they joined. Normal for classifieds, but nothing tells the seller so at
  registration, and the display name defaults to whatever they typed.
- **A seller is not notified when a listing is taken down** — the reason appears on their
  dashboard, and no message is sent.

### R7. Administrator accounts are ordinary accounts — medium

Same login, single factor, no IP restriction, no audit of what an administrator *reads*
(only of moderation decisions). A demoted administrator keeps the role for up to 15
minutes, because it travels in the access token. Promotion is a SQL statement, which is a
strength (no endpoint to attack) and a process gap (no record of who promoted whom).

### R8. Smaller, and worth knowing

- **Registration reveals whether an address has an account**
  (`EMAIL_ALREADY_REGISTERED`). The forgotten-password endpoint is careful not to; this
  one is not.
- **The full OpenAPI document and Swagger UI are public**, administrator endpoints
  included. Deliberate — the endpoints are discoverable anyway — but worth a conscious
  yes for production.
- ~~**No dependency scanning in CI.**~~ **Fixed:** the backend jar is scanned with Trivy and
  production npm dependencies are audited on every push and weekly; Dependabot proposes
  upgrades. Still no secret scanning, and GitHub Actions are pinned by tag, not by commit.
- **Spring Framework 6.2 carries two CRITICAL advisories with no fixed 6.2 release** —
  `XsltView` and server-sent events with views, neither of which this API uses. Accepted in
  `backend/.trivyignore` with that reasoning. The remedy is the move to Spring Boot 4.
- ~~**Errors leaked internals in three places.**~~ **Fixed.** A type-conversion failure
  echoed Java class names in its message, the framework's own 405/406/415 had no `code`, and
  the HTTP parser's refusals were Tomcat's HTML page. Now asserted by a test that listens on
  a real port and sends thirty
  malformed requests.
- **`.env.example` sets `AUTOX_LOG_LEVEL=DEBUG`**; copied to a server, that logs more than
  it should.
- **The `demo` profile on a production database** would publish sixty invented listings.
  It is profile-gated and removable by ledger, but nothing refuses it.

### Checked and found sound

Stated so they are not re-examined for nothing: ADMIN cannot be self-assigned at
registration; a listing someone else owns answers 404, not 403; the VIN, the seller's email
and a moderator's note never reach a public or seller-facing response; EXIF including GPS
is stripped from stored originals and variants; uploads go to storage with no bearer token
attached; a moderator's removal cannot be undone through the seller's endpoints, by the
seller or by an administrator; secrets come from the environment with no defaults, and
`.env` is not tracked; `health` is the only actuator endpoint exposed.

---

## 2. Where the product is weakest

### W1. A buyer cannot contact a seller — frontend and backend

The one transaction a marketplace exists for is not there. The listing page has a contact
button that is disabled and says so; no phone number is shown; there is no messaging.
The brief put messaging out of the MVP, and that was right for building — but it means the
MVP demonstrates a catalogue, not a marketplace. **Everything else on this list is
secondary to this.**

### W2. There is no vehicle catalogue outside the demo — backend, data

`vehicle_make`, `vehicle_model` and `vehicle_trim` are empty in a real database. The ten
makes seen in development come from the demo seed. Without them the seller's form falls
back to free text for everyone ("BMW", "bmw" and "B.M.W." become three manufacturers), the
search's make filter has nothing to offer, and a make typed as text is filtered by a scan
rather than an index. It is a data-sourcing task, and it is the largest single thing
between this and a usable site.

### W3. Location is a typed string — backend, data

No gazetteer, so **no "within 50 km"** — the most-used filter on a car site — and
"Saint-Étienne" and "Saint-Etienne" are different places. The bounding-box query is built
and tested and matches nothing, because no listing has coordinates.

### W4. The seller's form covers a fraction of what a listing can hold — frontend

The API accepts, and the public page displays, power, doors, seats, colour, equipment,
previous owners, service history, warranty, inspection date, CO₂ and emission class. The
create/edit form asks for none of them. Only demo listings have a full specification
table; a real seller's listing will look thin beside them. Likewise the form never sends a
first-registration month or a VIN.

### W5. It has never been designed for a phone — frontend

Desktop-first at 1440px was the instruction, and mobile was held to "does not overflow".
It does not overflow. Nobody has designed the search filters, the gallery, the four-step
form or the moderation card for a thumb, and on a classifieds site most visits are on a
phone.

### W6. It is invisible to search engines — frontend

A client-rendered single-page app: no server rendering, no sitemap, no structured data.
A pasted link now previews as **the site** — a title, a description and an image, from
static Open Graph tags — but every address previews the same, because the crawlers that
build previews do not run script: a listing link does not show that listing. Per-page
previews need server rendering or a prerender step.

### W7. Nothing is ever sent to a seller — backend

Beyond R1's reset email: no notice that a listing is about to expire, has expired, was
taken down, or was reinstated. Everything is discoverable on the dashboard and nowhere
else, and listings expire silently after 60 days.

### W8. Moderation works for one moderator and a short queue — frontend and backend

Enough to do the job, and no more: no search or filter (finding one listing means paging
to it), no view of a seller's other listings, no user administration screen although
`GET /admin/users` exists, no reports inbox (R3), and when a listing returns to the queue
after an edit the card says so but cannot show *what* changed.

### W9. The backend is operationally blind, and so is the frontend — both

No metrics, plain-text logs with no correlation id except on a 500, no error tracking in
the browser, no analytics. A health endpoint exists, so a load balancer would notice an
outage; the next indication of any subtler problem would be a user complaining.

### W10. The browser flows are verified by hand-run scripts that are not in the repository — tests

1154 automated tests (341 backend integration, 813 frontend unit) and none of them drives
a real browser against a real API. Each screen *was* exercised that way while it was
built — real upload to object storage, real publish, real moderation — by throwaway
scripts that were never committed. So the most valuable class of test this project has run
is the one CI cannot repeat. There is also no load test: search is proven at 20 000 rows,
and one test covers concurrency.

### W11. CI — resolved

Both workflows have run on GitHub and passed, and now also scan dependencies weekly. The
MinIO image is pinned by digest, with Dependabot proposing the next one.

### W12. Images cannot be cached — backend, configuration

Read URLs are signed per response and expire in six hours, so no CDN and no browser cache
across sessions can hold them, and every listing response does signing work. One variable
(`AUTOX_S3_PUBLIC_BASE_URL`) fixes it. JPEG and PNG only; no WebP.

### W13. One language, by construction — frontend

The data model is multi-country and the API is language-neutral, as designed. The Angular
app has `i18n` markers on most template text and no extraction or second locale; strings
built in TypeScript (confirmations, notices, validation sentences) are French literals.
Adding a language is a real project, not a translation file.

### W14. Smaller things that will be felt

- **A seller cannot remove a listing a moderator took down** from their dashboard: the row
  has no buttons, although the API would accept their delete.
- **The editor opens for a listing that can no longer be edited** (sold, removed) and only
  says so when saving is refused.
- **Accessibility was built carefully and never audited** — no screen-reader pass, no
  automated checker in CI.
- **Validation failures from the API carry no machine-readable constraint**, so when the
  client's own rules and the server's disagree, the user is told a field was refused and
  not why.
- **The result count is capped at 10 000** and cross-currency price sorting is meaningless
  — both correct while one market is open.
- **Reference data is cached by the browser for an hour**, so a catalogue change is slow
  to appear.

---

## 3. What became of the earlier ten

| Earlier item | Now |
|---|---|
| 1. No way to take a listing down | **Fixed.** Moderation: queue, approve, reject, takedown, reinstate, trail, re-review after edit. What is missing around it is R3. |
| 2. No content safety on uploads | **Unchanged.** Now part of R3. |
| 3. Forgotten password locks the user out | **Fixed in code, not in fact** — see R1. |
| 4. Operationally blind | **Partly fixed** (health, CI written). See W9, W11. |
| 5. Login is a CPU exhaustion vector | **Unchanged**, and registration shares it. R5. |
| 6. Quota race | **Fixed**, and the rule now lives in one class shared with reinstatement. |
| 7. Uncoordinated scheduled jobs | **Fixed.** |
| 8. Search proven only at 20 000 rows | **Unchanged.** W10. |
| 9. Rate limiting weaker than it looks | **Partly fixed** (IPv6 /64). Still trusts `X-Forwarded-For`. R5. |
| 10. Smaller things | Price history fixed, MinIO pinned; signed image URLs (W12) and accents (W3) stand. |

---

## 4. What is actually solid

For balance, and because knowing what not to re-examine is useful.

- **The contract.** One OpenAPI document, a client generated from it on every build, and a
  backend test that fails when the committed copy falls behind. It caught real bugs —
  most recently two DTOs sharing a schema name, which had silently given the public seller
  type an email field in the document.
- **Rules live in one place and the client is told the answer.** Listings carry
  `allowedTransitions`, `editable`, `renewable`; moderated listings carry
  `allowedActions`. No screen keeps its own copy of the state machine.
- **The error contract.** One RFC 9457 shape, a stable `code`, French keyed on the code
  and typed so a new code breaks the frontend build until someone writes its sentence.
- **Token handling.** Rotation on every use, family revocation on replay, hashed storage,
  a single in-flight refresh in the client.
- **Schema integrity.** Flyway owns it; Hibernate validates and refuses to start on drift.
- **The test suites.** Backend tests run against real PostgreSQL and real S3-compatible
  storage with nothing mocked. Frontend tests address fields by label and assert the
  French a user reads.
- **The moderation trail.** Append-only, written in the same transaction as the decision,
  and not bypassable through any other endpoint.
