# Frontend scaffold and API layer — decisions

The first frontend session: an Angular workspace, a generated API client, and the auth,
error and style plumbing the screens will sit on. **No screens.**

## What was built

```
frontend/
  openapi.json                     the committed API contract (generated FROM, reviewed)
  openapi-generator.config.json    generator options, commented
  openapitools.json                pins the generator to 7.26.0
  scripts/fetch-openapi.mjs        refreshes the snapshot from a running backend
  src/
    environments/                  apiBaseUrl per configuration
    styles/_tokens.scss            colour, spacing, type scale
    styles/_base.scss              mobile-first base styles
    app/core/
      api/
        generated/                 GENERATED, gitignored, 85 files
        index.ts                   the only import path the app uses
        api.providers.ts           BASE_PATH, token store, session rehydration
      auth/
        token-store.ts             interface + localStorage implementation
        auth.service.ts            session state, single in-flight refresh
        auth.interceptor.ts        bearer header, 401 renewal
        auth.guard.ts              authGuard, roleGuard(...)
        auth.routes.ts             the route names the plumbing shares
      errors/
        api-error.ts               narrowing, transport-failure detection
        error-messages.fr.ts       Record<ErrorCode, string> — exhaustive
        error-message.service.ts   code + field -> French
```

Angular **22.2.2**, standalone throughout, zoneless (the v22 default), Vitest for tests.
25 frontend tests. Production bundle 281 kB / 72 kB gzipped.

The tests are weighted at the two places a mistake is expensive and invisible: the shared
401 refresh, and whether `appConfig`'s own provider graph resolves — every other test
builds its own `TestBed`, so without that one a missing provider would first surface as a
blank page in a browser.

---

## 1. Generation, and the three spec bugs it exposed

The client is generated with `@openapitools/openapi-generator-cli` 7.26.0,
`typescript-angular` generator, from the **committed `openapi.json` snapshot** rather than
from a live `/v3/api-docs`. Generation runs as a `pre` script on `build`, `start`, `test`
and `lint`, so a fresh clone cannot compile against a missing client — verified by deleting
the directory and running `npm run build`.

**Why a snapshot and not the live endpoint:** CI has no backend to ask, a developer should
be able to build offline, and a commit should record which contract it was built against.
The cost is that it can go stale, so `OpenApiSnapshotTest` on the backend compares the
committed file against the live document and fails the backend build naming the fix. The
backend workflow therefore watches `frontend/openapi.json` as one of its trigger paths.

**Why this generator, given it needs a JVM:** it is the canonical and best-supported path
to Angular `HttpClient` services, and Java is already a first-class dependency of this
repository. The frontend CI job gains a `setup-java` step; that is the whole cost.

### The requirement did not hold until the spec was fixed

Generating the client is only worth doing if the generated types are the real contract.
Three things in the published document meant they were not.

**Every enum was inline and duplicated.** `ListingStatus` appeared as an anonymous
`type: string, enum: [...]` on both `ListingSummary.status` and `ListingResponse.status`,
so the generator would have produced `ListingSummary.StatusEnum` and
`ListingResponse.StatusEnum` — two unrelated types for one backend enum, no canonical
`ListingStatus` to import, and no `ErrorCode` to key a message table on. That is exactly
the drift the task set out to prevent. Fixed with `@Schema(enumAsRef = true)` on the 18
published enums; the document went from 38 schemas and 0 named enums to 57 schemas and 17.

**The error contract was not in the document at all.** Errors are built at runtime as
`ProblemDetail` with members attached through `setProperty` — the right runtime
representation, and invisible to a schema generator. So 33 operations were described and
not one of their failure modes: a generated client had no error type, and `ErrorCode` never
reached the spec, meaning the one contract a client depends on most was the one thing it
would have had to hand-write. Fixed with `ApiErrorResponse`, a documentation-only record,
plus an `OpenApiCustomizer` that attaches it as the `default` response on every operation.
Because it is a parallel description that can drift, `ErrorContractIntegrationTest` now
compares its members against the members real error responses carry, in both directions.

**Eleven validation predicates were published as request fields.** `@AssertTrue` cross-field
constraints are bean properties as far as a schema generator is concerned, so
`isCompanyNamePresentForPro()` became a writable boolean named `companyNamePresentForPro`
on `RegisterRequest`, with four more on `UpdateListingRequest` and five on
`CreateListingRequest`. Harmless to the server, which ignores them on input; not harmless
in a generated client, where they look like fields a caller should set. Fixed with
`@Schema(hidden = true)`, with a test that fails if the pattern returns.

**One nondeterminism, found by the snapshot test itself.** springdoc derives the `servers`
entry from the host and port of whoever fetched the document, so the same application
described itself differently to a developer on `:8081`, to a MockMvc test, and to a browser
behind a proxy — and no committed snapshot could ever match. Pinned to a single relative
`/`, which is also the honest answer for a same-origin deployment.

### One enum is deliberately not generated

`ImageVariantType` is a **map key** — `ImageResponse.variants` is
`Map<ImageVariantType, VariantView>` — and neither OpenAPI nor any generator can constrain
object keys to an enum. So `variants` is typed `{ [key: string]: VariantView }`, and the
three keys (`THUMBNAIL`, `CARD`, `FULL`) are the one vocabulary a client must know by name.
They are available at runtime from `GET /api/v1/reference/enums`.

Nothing needs it yet. When the gallery is built, the clean fix is an API shape change —
`variants` as a list of `{type, url, width, height}` — which would put the enum in the spec
and make the entries self-describing. **Worth deciding then; it is a response-shape change
and not one to make unilaterally.**

### Response fields are all optional, and that is springdoc being correct

Every field of every generated *response* model is `field?: T`, because springdoc infers
`required` from Bean Validation annotations and responses rightly carry none. Request models
are correct — `RegisterRequest.email` is `string`, not `string | undefined` — because the
request DTOs do have `@NotBlank`.

Rather than spread `?.` and `!` through the application, the invariant is asserted **once,
at the boundary**: `AuthService.adopt` throws if a token or user is missing. A server that
stops sending an access token deserves a loud failure, not a silent `undefined`.

---

## 2. The 401 interceptor: one refresh, shared

**The single in-flight refresh is a correctness requirement, not an optimisation.** The API
rotates refresh tokens on every use and treats a replayed one as theft: it revokes the
entire token family and every session descended from that login. So two concurrent
refreshes do not waste a round trip — one succeeds and the other signs the user out of all
their devices.

`AuthService.refresh()` therefore memoises the call:

```ts
this.refreshInFlight ??= this.api.refresh(...).pipe(
  map(...),
  finalize(() => { this.refreshInFlight = null; }),   // before the multicast
  shareReplay({ bufferSize: 1, refCount: false }),
);
```

`finalize` sits **before** `shareReplay`, so the slot is released when the call settles
rather than when the last subscriber leaves; `refCount: false` so a component unsubscribing
cannot cancel a refresh the rest of the application is waiting on. Three requests that 401
together produce exactly one call to `/auth/refresh`; a request that 401s after it finished
starts a new one. Both are tested.

Other decisions in the 401 path:

- **The retry uses `next(...)`**, which continues down the remaining chain rather than
  re-entering the interceptor — so a second 401 on the replay surfaces to the caller
  instead of starting another refresh. One renewal per request, by construction.
- **A 403 is not renewed.** The token is valid and the account may not do this; renewing
  would produce an identical 403 and a wasted round trip.
- **The auth endpoints are skipped twice over**: by an `HttpContextToken` that
  `AuthService` sets on its own calls, and by a path list. Two of them answer 401 in normal
  use — a wrong password at `/auth/login`, a spent token at `/auth/refresh` — and treating
  either as "renew and retry" is how the loop that revokes the token family starts.
- **A failed refresh clears the session and redirects**, carrying the current URL in a
  `suivant` query parameter. The sign-in screen must treat that value as untrusted: it
  comes from the address bar, so only same-origin paths are safe to navigate to. Noted in
  `auth.routes.ts` beside the constant.

## 3. Token storage

`localStorage`, behind a `TokenStore` interface and an injection token so it can be swapped.

The honest version of the trade-off is written out in `token-store.ts`: **the refresh token
is a 90-day credential in JavaScript-readable storage**, so XSS here is a durable account
takeover rather than a session hijack. It is still the best available option — in-memory
would log users out on every reload, against a brief that requires month-long sessions;
`sessionStorage` has identical exposure and loses the session anyway; an http-only cookie is
genuinely safer and would mean putting a backend-for-frontend in front of a deliberately
stateless, header-based API. So the mitigation is to not have XSS: a strict CSP at the edge,
no `innerHTML` with server data, Angular's sanitiser left on.

Reads are defensive — storage can throw in Safari private mode, and the stored shape is
validated before it is trusted rather than crashing the bootstrap on a release that changed
it.

## 4. Guards

`authGuard` requires a session; `roleGuard(...roles)` requires one of the roles.

**The role is not read from the JWT.** A JWT payload is base64, not a secret: a user can
rewrite `"role":"ADMIN"` in their own storage in seconds. Every call behind the guard would
still fail at the API, so nothing is exposed — but the result is a screen full of 403s
instead of an honest refusal, and it invites the belief that the client enforces something.
So the role comes from `GET /api/v1/me`, fetched once per application load. That is a round
trip on the first guarded navigation after a reload, which is the right price.

Guards are a courtesy to the user, never a security boundary. The comment says so, because
the next person to add one needs to know it.

## 5. Error messages

`ERROR_MESSAGES_FR` is typed `Record<ErrorCode, string>` against the **generated** enum, so
when the backend adds an error code **this file stops compiling** until someone writes the
French. That compile error is the point; the alternative is a lookup that silently misses
and shows the user the fallback, or worse, the API's English `detail`.

The API's `detail` and `message` are never shown. They are English developer text and the
document says their wording is not a contract. A test asserts no French message contains
the English words a copied `detail` would carry.

**Not in an Angular i18n XLIFF file**, and that split is deliberate: Angular i18n is a
build-time mechanism for template text, while this is a runtime lookup keyed on a value
that arrives over the network. Routing it through `$localize` would work but would scatter
the table across extraction files and lose the exhaustive `Record` type, which is the part
earning its keep. A second locale makes this one file per locale; the shape does not change.

### The weak point, and it is a contract limitation

Validation failures arrive as `{field, message}` where `message` is untranslated developer
text whose wording is explicitly not a contract. So it can be neither shown nor matched on,
and what reaches the client is "this field is wrong" with nothing about *why*.

The layer therefore maps `field` to a French **label** and leaves the explanation to the
form's own client-side rules — which is the correct architecture anyway: client-side
validation produces the user-facing text, and the server's `errors[]` tells you which
fields to mark.

**The better fix belongs in the API:** a machine-readable constraint per violation,
`{field: "email", constraint: "EMAIL"}`, would let the client say exactly what is wrong in
the user's language. Worth raising when the forms are built.

## 6. i18n

Angular's built-in i18n, with **`sourceLocale: "fr"`** — templates are authored in French
and there is nothing to translate, which is the simplest correct setup for one locale.
`@angular/localize` is installed and `i18n` markers are in place (the skip link carries
one), so a second locale is a `locales` entry and a translation file rather than a refactor.
`npm run i18n:extract` is wired for that day.

`fr` and not `fr-FR`: Angular ships locale data for `fr` (which *is* France French) and
warned on every build about falling back from `fr-FR`. Build noise trains people to ignore
warnings. Note that the API's account `locale` is `fr-FR` — a different thing, stored on the
account, not Angular's formatting locale.

## 7. Styles

Two partials. `_tokens.scss` declares the vocabulary as **CSS custom properties** — readable
at runtime, overridable per component and per media query, and a dark theme becomes a second
`:root` block rather than a second build. `_base.scss` applies it to bare HTML.

Mobile-first throughout: every media query is `min-width`, and the base styles *are* the
phone styles. Breakpoints are the one thing that must be Sass variables, because `@media`
cannot read custom properties.

Three decisions worth knowing:

- **`--t-body` is 16px and form controls inherit it.** iOS Safari zooms the viewport when a
  field smaller than 16px is focused, leaving the user on a page they have to pinch back out
  of. 15px looks better and is not an option.
- **Every interactive element is at least 44px**, declared once in the base rather than per
  component, because the one that gets missed is always the small icon button.
- **The focus ring is never removed**, and `prefers-reduced-motion` is respected.

The scales are deliberately small — seven spacing steps, six text sizes. Twenty of each
guarantees two screens built a month apart will not line up.

## 8. CI

Both workflows are now path-filtered, and `actionlint` validates them.

The backend job watches `backend/**`, **`frontend/openapi.json`** and its own file; the
frontend job watches `frontend/**` and its own. The snapshot path is in the backend's list
because that file is the API contract — a snapshot refreshed without the backend change
behind it must fail the backend's `OpenApiSnapshotTest`.

The frontend job is `npm ci`, generate, lint, build, test. Node is **pinned to 24.20.0**
rather than `lts/*`: a runner image rolling forward to a Node major Angular has not
qualified is a red build with no commit behind it. `package.json` now carries the matching
`engines` field.

The build runs the **production** configuration — optimisation, budgets and locale
inlining — because a development build would pass while the thing that ships does not.

---

## Environment note

Angular 22 requires Node `^22.22.3 || ^24.15.0 || >=26`. The machine had 24.11.0, so Node
was upgraded to **24.20.0** (LTS) via winget, with agreement, before scaffolding. Angular 21
would have installed unchanged on 24.11.0 and was offered as the alternative.

## What is deliberately not here

No screens, no components beyond the shell, no forms, no HTTP caching layer, no state
management beyond the auth signals. The route table carries a commented example of each
guard's usage and nothing else.

Also absent, and worth naming:

- **No CSP.** The `localStorage` decision above rests on there being one. It belongs in the
  deployment configuration, not in the bundle.
- **No global error UI.** `ErrorMessageService` produces the sentence; nothing displays it
  yet, because a toast or an inline banner is a screen decision.
- **No HTTP retry or offline handling** beyond distinguishing a transport failure from a
  server error.
