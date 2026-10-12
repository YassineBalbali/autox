# Frontend

Angular 22, standalone components, zoneless, French only.

**Desktop is the primary target: screens are designed and reviewed at 1440px.** The
breakpoints stay in place and a phone must not break — no horizontal page scroll, nothing
unusable — but it is not polished yet; a proper mobile pass comes at the end of the
project. The CSS is still written as min-width queries, so that pass adds to the base
rules rather than fighting them.

## Running it

The frontend needs the API. From the repository root:

```bash
docker compose up -d
cd backend && mvn spring-boot:run      # serves on 8081, see the root README
cd frontend && npm install && npm start
```

`http://localhost:4200`. The API base URL lives in `src/environments/` — 8081 in
development, empty in production, where the app and the API are served from one origin.

| Command | What it does |
| --- | --- |
| `npm start` | Dev server |
| `npm run build` | Production build, with budgets and locale inlining |
| `npm test` | Vitest, once |
| `npm run lint` | ESLint over source and templates |
| `npm run api:fetch` | Refreshes `openapi.json` from a running backend |
| `npm run api:generate` | Regenerates the client from `openapi.json` |

`api:generate` runs automatically before anything that compiles TypeScript, so a fresh
clone cannot build against a missing client.

## The API client is generated

Everything under `src/app/core/api/generated/` comes from `openapi.json` and is not
committed. **Do not hand-write a model or a service there, or anywhere else.** There are
17 enums and 57 schemas; a hand-written copy drifts the first time the backend changes and
nothing tells you.

If the application needs a type the API does not describe, that is a gap in the OpenAPI
document to close on the backend, not an interface to add here.

The snapshot is kept honest from the other side: the backend's `OpenApiSnapshotTest` fails
its own build when `frontend/openapi.json` no longer matches the document the application
serves, and names the two commands that fix it.

## Layout

```
src/app/
  core/            Things with one instance, used everywhere
    api/           BASE_PATH wiring, and the generated client behind one index
    auth/          Session state, interceptor, guards, named routes, device label
    errors/        HTTP failure → French sentence
    reference/     Makes, models, enum values, countries — fetched once
    ui/            Flash messages between screens
  layout/          Site header, footer, primary navigation
  shared/          Reusable pieces with no feature knowledge
    brand/         The wordmark
    forms/         Validation rules + text, the field component, submit state
    listing/       Price and spec line
    format/        Dates
    ui/            Alert
  features/
    search/        The home page: filters, results, URL <-> query
    auth/          Sign in, register, forgot, reset
    account/       Layout + sidebar, profile, company, password, sessions
    errors/        Access refused
    styleguide/    The design reference page
src/styles/        Tokens, base, controls
```

## Routes

French, because they are part of the interface. Two have to stay as they are:
`/connexion` and `/acces-refuse` are named in `core/auth/auth.routes.ts` and redirected to
by the interceptor and the guards, and **`/reinitialiser-mot-de-passe` must match
`autox.mail.password-reset-url` on the backend** — that is the link in the reset email, and
a mismatch loads the app and renders nothing.

| Path | Guard |
| --- | --- |
| `/` — the search, and the home page | — |
| `/connexion`, `/inscription`, `/mot-de-passe-oublie`, `/reinitialiser-mot-de-passe` | — |
| `/mon-compte`, `/mon-compte/mot-de-passe`, `/mon-compte/sessions` | `authGuard` |
| `/mon-compte/entreprise` | `roleGuard(PROFESSIONNEL, ADMIN)` |
| `/acces-refuse` | — |

A guard is a courtesy, never a boundary. Every one of these is enforced by the API, which
is the only place that counts — the bundle is public and anybody can edit a `canActivate`
out of it.

## The search screen

The URL is the screen's only state. A filter or sort change navigates; the new query
string is parsed into a `SearchQuery`; that drives the API call. So a link reproduces a
search, the back button undoes one filter at a time, and the sidebar cannot disagree with
the list.

- **Parameter names are a contract** once a link is shared: `marque`, `modele`,
  `annee_min`/`annee_max`, `prix_min`/`prix_max` (whole euros), `km_min`/`km_max`,
  `carburant`, `boite`, `carrosserie`, `etat`, `lieu`, `tri`. Add; do not rename.
- **The query string is untrusted.** `parseQuery` bounds numbers, checks enum values
  against the generated enums and drops anything else — a bad parameter costs that filter,
  never the page.
- **Filter options come from the reference endpoints** (makes, models, enum values). Only
  the French labels are in the frontend (`features/search/labels.fr.ts`), as exhaustive
  `Record`s over the generated enums.
- **The count is a separate request** (`GET /listings/count`, capped at 10 000) so the list
  never waits for it; a failed count is not an error state.
- **"Voir plus" is keyset pagination** and the cursor is deliberately not in the URL.
- **A new search cancels the previous one's requests**, so a slow response cannot be shown
  under a newer filter.

## Conventions

**The API's `message` and `detail` are never shown to a user.** They are English developer
text and the API documents their wording as not being a contract. Every sentence a user
reads comes from `core/errors/error-messages.fr.ts`, keyed on the stable `code`. That table
is typed `Record<ErrorCode, string>`, so a new error code on the backend stops this project
compiling until someone writes the French for it.

**Client-side validation is the only place a user learns *why*.** A field error from the
API names the field and nothing else, so the rules in `shared/forms/validation.ts` mirror
the server's and carry the explanation. They are written as named constants next to the DTO
constraint they come from, so a change on the backend has one place to land.

**Signals, not subscriptions.** The app is zoneless, so a template that reads a plain
property will not re-render when it changes. `AbstractControl` is not signal-backed either
— see `TextField` for how its state is bridged, and why a `computed` over `control.touched`
silently never updates.

**One container, one input measure.** `.page` (`--w-page`, 1200px) wraps the header,
`main` and the footer, so everything shares the same two vertical lines. Inputs are capped
at `--w-field` wherever they sit — a field is never stretched across a desktop panel.
Inputs also stay at 16px or larger, or iOS zooms the viewport on focus.

**Every screen is lazy.** The account section is a routed layout (`AccountLayout`) with
its screens as children: one sidebar, one `authGuard`, one load of the profile.

**Checking for horizontal overflow:** compare against the width you asked for, not
`window.innerWidth`. Under mobile emulation Chrome widens the layout viewport to fit
overflowing content, so `scrollWidth - innerWidth` reads 0 on a page that is too wide.

## Design system

Dense, information-first car-classifieds styling: compact rows, small type for specs,
borders and dividers instead of shadows, nearly square corners. The live reference is
`/guide-de-style` — every token and shared component, rendered from the real ones.

- **Tokens** live in `src/styles/_tokens.scss` and nowhere else. No component file
  contains a colour or a font size that is not a custom property.
- **`--c-accent` is the logo's blue.** It is used for exactly three things: prices, the
  primary button, and active filter states. Informational messages are neutral, not blue.
- **The wordmark** is `app-logo` inside the app (drawn in the tokens) and
  `public/autox-wordmark.svg` outside it. `wordmark.spec.ts` fails if the file's two
  fills stop matching `--c-navy` and `--c-accent`, or if the artwork diverges.
- **Font**: Barlow Semi Condensed (SIL OFL), self-hosted through `@fontsource` — four
  weights, listed in `angular.json`. No font CDN is contacted.
- **Every price** goes through `app-price`; in a list it sits in `.row__aside`, which is
  what keeps it at the same x on every row. **Every spec line** goes through
  `app-spec-line`, in a fixed order.
- **Grouped numbers** go through `shared/format/number.ts`. `Intl` separates thousands
  with a narrow no-break space this font draws at near-zero width; unpatched, "18 990 €"
  renders as "18990 €".
- **Controls are 34px** with a mouse and 44px on a coarse pointer (`--h-control`).

| Shared piece | Where | For |
| --- | --- | --- |
| `app-logo` | `shared/brand` | The wordmark |
| `app-price` | `shared/listing` | Any price |
| `app-spec-line` | `shared/listing` | année · km · carburant · boîte · puissance |
| `app-listing-row` | `shared/listing` | A vehicle in a list: photo, facts, price |
| `app-filter-chip` | `shared/ui` | An on/off filter |
| `app-alert` | `shared/ui` | Error, success, note |
| `app-text-field` | `shared/forms` | A labelled input with its message |
| `.panel`, `.panel__head` | `_controls.scss` | A bordered surface and its title bar |
| `.rows`, `.row`, `.row__main`, `.row__aside` | `_controls.scss` | Any list |
| `.readout` | `_controls.scss` | Read-only label/value rows |
| `.form--rows`, `.form-actions` | `_controls.scss` | Label-beside-field forms |
| `.btn` + `--primary` `--quiet` `--danger` `--sm` `--block` | `_controls.scss` | Buttons |
| `.tag`, `.tag--accent` | `_controls.scss` | A short label on a row |

## Tests

Vitest with the Angular unit-test builder, in jsdom. Component tests address fields by
their label, the way a user does — not by CSS class, and not by a radio's `value`, which
Angular's radio accessor never writes to the DOM.
