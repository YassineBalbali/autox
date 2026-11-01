# Natural-language search

A visitor types *« SUV diesel automatique moins de 15 000 € en bon état »* and lands on the
ordinary results page with *SUV · Diesel · Automatique · ≤ 15 000 €* applied, and a quiet line
saying *« en bon état »* was not taken into account.

This page explains how that works, why it is built this way, how well it works (measured), and
where it does not.

## What it is, and what it is not

**It translates text into filters. It does not search.** `POST /api/v1/search/interpret` takes
the description and returns three things:

- `criteria`: exactly what `GET /api/v1/listings` already accepts. That means catalogue ids, and
  prices in minor units with the currency beside them.
- `summary`: what was applied, in French.
- `notUnderstood`: the parts of the request that did not become a filter.

The web app puts the criteria in the URL and the ordinary search runs. So:

- **A model outage never breaks search.** The endpoint can fail and the page offers the plain
  text search for the same words.
- **The model never sees or touches the database.** It receives the visitor's sentence and
  nothing else, and it returns JSON. Every value in that JSON is checked before it becomes a
  filter (see [Validation](#validation)).
- **Nothing new reaches the results page.** The filters are the ones the sidebar already has.
  The chips, shared links, the back button and recent searches all work as before.

It is optional. `autox.ai.provider` is `none` by default. With `none`:

- `GET /api/v1/search/capabilities` answers `{"interpret": false}`;
- the home page does not show the entry;
- the endpoint answers 404.

## Architecture

```
home page ──POST /search/interpret {query}──▶ SearchInterpretService
                                                │ 1. feature on?          (404 if not)
                                                │ 2. per-address limit    (429, counted before any spend)
                                                │ 3. SearchInterpreter    (Groq; 503 on any failure)
                                                │ 4. InterpretationValidator
                                                ▼
          ◀── {criteria, summary, notUnderstood} ─┘
/recherche?carrosserie=suv&carburant=diesel&…  (+ history state: the text, what was not understood)
```

**The provider sits behind an interface.** `SearchInterpreter` follows the pattern of
`EmailSender` and `ObjectStorage`. The implementation is chosen by `autox.ai.provider`, never by
which beans happen to exist.

- `DisabledSearchInterpreter` (`none`) and `GroqSearchInterpreter` (`groq`) are the two
  implementations.
- The tests use a stub.
- A second provider would be one class. Anything OpenAI-compatible with structured output can
  reuse `InterpretationPrompt` as it is.

### Configuration

| Variable | Default | |
|---|---|---|
| `AUTOX_AI_PROVIDER` | `none` | `none` or `groq`. An unknown value stops startup with a message naming the variable. |
| `AUTOX_AI_API_KEY` | — | Required at startup when the provider is `groq`, through the same check that names the other missing credentials. Like them, it has no default in `application.yml`. |
| `AUTOX_AI_MODEL` | `openai/gpt-oss-20b` | See [Choice of model](#choice-of-model). |
| `AUTOX_AI_RATE_LIMIT_ENABLED` | `true` | Off only for the eval. |

**Timeout: 5 seconds.** Both the connection and the answer are bounded by it.

**Per-address limit: 10 requests in 10 minutes.** Its throttle scope (`SEARCH_INTERPRET`, V17) is
separate from the public read limit of 300 a minute. A request to this endpoint spends a shared,
free quota, which a search does not.

**Every failure ends the same way.** A timeout, Groq's own 429 (quota) and any other provider
error all produce 503 `INTERPRETER_UNAVAILABLE`. The web app then shows *« La recherche
intelligente est indisponible pour le moment. »* with a button that runs the ordinary text
search.

## Prompt design

`InterpretationPrompt` holds all of it. It is short: under 400 words of rules. The free tier's
budget is counted in tokens per minute, so every rule costs throughput.

**Structured output in strict mode.** Groq constrains decoding to a JSON schema. Its rules:

- every property is required;
- optional values are a union with `null`;
- no other properties are allowed.

The answer is therefore always parseable and always this shape. Strict mode guarantees a shape,
not a meaning, which is what validation is for.

**The schema is the filter set the URL can carry, minus two:**

- **No drivetrain.** The results page has no parameter for it, so a drivetrain filter would be
  silently dropped by the client. "Quatre roues motrices" is reported as not understood instead.
- **No free text (`q`).** It would let the model put arbitrary text into a `LIKE`, and the point
  of the schema is that every value can be checked.

`OTHER` is left out of every enum, because nobody searches for it.

**Rules are written as a lexicon, not as examples:**

- fuels: *gazole → DIESEL*;
- gearboxes: *BVA, DSG, EDC → AUTOMATIC*;
- bodies: *citadine → HATCHBACK*;
- number forms: *15k, 15 000 €, 15 mille*.

A lexicon is cheaper in tokens than few-shot examples, and it is reviewable: each line is a
decision someone can disagree with.

**Colloquial phrases map to existing filters, and none invents a threshold the site does not
have:**

| Phrase | Filter | Why |
|---|---|---|
| *pas cher, bonne affaire, bon prix* | `priceLevel = GOOD_DEAL` | The site's own price indicator ([price-indicator.md](price-indicator.md)), not an arbitrary cap |
| *récente* | `yearFrom = this year − 3` | The year is passed into the prompt, so the rule stays true next year |
| *peu de km, peu kilométrée* | `mileageMax = 50 000` | A round figure a buyer would type |
| *moins de N ans* | `yearFrom = this year − N` | |
| *hybride* alone | all three hybrid fuels | A buyer who says "hybride" has not chosen between them |

**What is never understood, by rule:**

- *bon état*, *première main*;
- colours (the search has no colour filter);
- options;
- power;
- seats;
- warranty;
- Crit'Air;
- regions and departments (`location` matches a city or a postal code).

The model is asked to quote these back exactly as written in `notUnderstood`.

**The query is data.**

- It is the only content of the user message, JSON-encoded, so its quotes stay inside a string.
- The system message says it is text to translate and never instructions.
- Its length is capped at 200 characters before it is sent.

This does not make prompt injection impossible; nothing does. What bounds it is the output side:
a schema the provider enforces and values the server checks. The worst a hostile query can do is
choose wrong filters for the person who typed it. The eval set includes one such query.

**Temperature 0 and low reasoning effort.** gpt-oss is a reasoning model. Reasoning tokens are
latency and quota, and the task is translation rather than deliberation. Reasoning is also left
out of the response.

## Validation

`InterpretationValidator` reads the model's object defensively. A field of the wrong type is
absent: a price of `"15000"` (a string) or a year of `2018.5` is not parsed or rounded, because
the schema said integer. Each kind of value is then checked:

| Value | Check | On failure |
|---|---|---|
| Make, model | Resolved against the active catalogue with `catalogue_search_key` (V16, the typeahead's normalisation: case, accents, spacing). Matching tries exact, then letters-and-digits only (*mercedes benz*), then a unique prefix for a make (*mercedes*) or the longest model that starts the name (*golf gti* → Golf). A model named alone brings its make only if exactly one model in the catalogue has that name. | Dropped, and the name is reported. Ambiguous counts as no match. |
| Enums | Against our enums, `OTHER` excluded | Dropped and reported |
| Years | 1950 to next year | Dropped and reported |
| Prices | 1 € to 10 000 000 € (the frontend's own ceiling) | Dropped and reported |
| Mileage | 0 to 1 000 000 km | Dropped and reported |
| Ranges | `min ≤ max` | Both ends dropped and reported |
| Place | Must appear in the query (case and accents folded) | Dropped and reported: the model may not invent a place |
| `notUnderstood` | Each entry must quote the query | Dropped: a "quotation" that is not in the text is the model inventing something to display |

**The summary is written by the server from the validated criteria, never by the model.** It
cannot describe a filter that is not applied, and it cannot carry model text to the screen.

## The web app

- **Where the entry appears.** A band under the home page hero: *Recherche intelligente*, the
  field *« Décrivez la voiture que vous cherchez »* and a button. It is shown only when
  `/search/capabilities` says yes. The header's RECHERCHE field and its typeahead are unchanged.
- **How the filters are applied.** The criteria are mapped back to the URL's terms: ids to slugs
  through the reference data, minor units to whole euros. A make the reference data cannot name
  is dropped rather than sent as text.
- **What the results page shows.** The description, and what was not understood, above the
  ordinary chips. The chips are the understood part, each removable.
- **Where the description is kept.** In history state, not the URL. The URL holds filters, and a
  shared link is a search, not a conversation. The note survives a reload and a return, stays
  while chips are removed, and goes when a new search is typed.

## Evaluation

`eval/ai-search/` holds the evaluation, which is not part of CI:

- `queries.json`: 42 French queries with the filters a careful human would apply.
- `run-eval.mjs`: sends each query through the running API, so it measures the whole pipeline
  (prompt, model, validation, catalogue matching) and not the model alone. It reports the
  metrics below and writes every answer to `results/`.

The set covers:

- makes, models, fuel, gearbox, body type, condition, price, year, mileage and place;
- colloquial forms (*pas chère*, *récente*, *boîte auto*, *12k*, *moins de 5 ans*);
- abbreviations (*vw*, *DSG*) and typos (*renaut megane*, *peugot 208*);
- 14 queries with parts the search cannot filter on;
- one injection attempt.

The expected makes and models are those of the local development catalogue: 18 makes and 69
models, the demo seed's.

**Metrics:**

- **Exact match:** every filter field of a query is right, with nothing missing and nothing
  extra. This is the strictest measure: one wrong field fails the whole query.
- **Per-field precision and recall:**
  - Each filter value counts as one prediction, and each value in a list (two fuels, say) counts
    separately.
  - Precision: of the values applied, how many were asked for.
  - Recall: of the values asked for, how many were applied.
  - Low precision means filters the visitor did not want, which hide good results. Low recall
    means requests that were ignored.
- **Not-understood recall:** on the queries with unsupported parts, the share of those parts
  that were reported. **Spurious rate:** on fully supported queries, how often anything was
  reported anyway.
- **Latency:** at the API, including validation and catalogue lookups.

To run it:

```sh
# a backend with the provider on and the per-address limit off
AUTOX_AI_PROVIDER=groq AUTOX_AI_API_KEY=… AUTOX_AI_RATE_LIMIT_ENABLED=false \
  AUTOX_AI_MODEL=openai/gpt-oss-20b mvn spring-boot:run      # in backend/
node eval/ai-search/run-eval.mjs http://localhost:8081 --label gpt-oss-20b
```

It waits 12 seconds between queries, which keeps a run inside the free tier's token budget. A run
takes about ten minutes.

### Results

*Pending: no Groq key was available when this page was written. Run the commands above and
record the result here: the model, the date, the summary table and the misses.*

### Choice of model

*Pending the results above.*

## Known limits

- **The catalogue bounds it.** A make or model not in the catalogue is reported as not
  understood, even when the model recognised it. That is deliberate: a text filter on an unknown
  name would silently match nothing.
- **One place, no radius.** `location` matches a city or a postal code. *Près de Lyon* is Lyon
  itself, and regions are not understood.
- **No negation.** *Pas diesel* cannot be expressed: the filters are inclusive lists.
- **Approximate thresholds.** *Peu de km* means 50 000 km and *récente* means three years. Both
  are documented, but they are rules of thumb.
- **Not deterministic.** Temperature 0 is not a guarantee on hosted inference. The same query
  can, rarely, come back differently. The eval is a measurement on one day, not a proof.
- **Quota.** The free tier allows about 1 000 requests a day per model. Beyond that every request
  is a 503 and visitors get the ordinary search, which is the intended failure.
- **French only.** The prompt, the lexicon and the eval are French. Another market would need its
  own.
