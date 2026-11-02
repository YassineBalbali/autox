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
| `AUTOX_AI_MODEL` | `openai/gpt-oss-120b` | Chosen on the held-out evaluation: see [Choice of model](#choice-of-model). |
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

`InterpretationPrompt` holds all of it. It is short: under 450 words of rules. The free tier's
budget is counted in tokens per minute, so every rule costs throughput.

**Structured output in strict mode.** The schema follows Groq's strict dialect:

- every property is required;
- optional values are a union with `null`;
- no other properties are allowed.

Groq documents strict mode as constraining decoding, so that the answer always matches. **Measured,
it does not hold for gpt-oss-20b.** That model wrote `"condition": null` where the schema said
list, and later `"bodyType": "SUV"` where it said list of strings. Groq checked the answer
afterwards and refused it (HTTP 400, `json_validate_failed`), so the visitor got the fallback.
This was the cause of all 14 of 20b's failures on the first run.

In response, lists may now be `null` too, which removed nearly all of them. gpt-oss-120b never
produced one. Either way the server's validation, not the provider, is what guarantees the values.

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
- versions and trim levels;
- power, in ch or kW;
- colours (the search has no colour filter);
- options;
- seats;
- drivetrain;
- warranty;
- what the car is for;
- regions and departments (`location` matches a city or a postal code).

The model is asked to quote these back exactly as written in `notUnderstood`: every requirement
either fills a field or is listed there, and none is dropped silently. It is also told never to
infer a body, a fuel or a gearbox from a model or version name.

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
| Make, model | Resolved against the active catalogue with `catalogue_search_key` (V16, the typeahead's normalisation: case, accents, spacing). Matching tries exact, then letters-and-digits only (*mercedes benz*), then a unique prefix for a make (*mercedes*) or the longest model that starts the name (*golf gti* → Golf), then the make's one model whose name ends with what was written (*3* → Série 3, *A* → Classe A: French names carry a family word that people, and models, leave out). A model named alone brings its make only if exactly one model in the catalogue has that name. | Dropped, and the name is reported. Ambiguous counts as no match. |
| Enums | Against our enums, `OTHER` excluded | Dropped and reported |
| Years | 1950 to next year | Dropped and reported |
| Prices | 100 € to 10 000 000 € (below that is not a car search; above is the frontend's own ceiling) | Dropped and reported |
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

## Usage counts

The admin dashboard shows how the feature is used, from one row per Paris day in
`search_interpret_usage` (V18):

- `requests`: those that reached the model, so not ones refused for length or by the rate limit;
- `failed`: of those, the ones answered 503;
- `with_not_understood`: of the answered ones, those with at least one part not understood.

**Counts only.** The table has no column a query could be stored in, and a test holds it to
that. A failure to count is logged and never fails the search.

## Evaluation

`eval/ai-search/` holds the evaluation, which is not part of CI:

- `queries.json`: the development set, 42 French queries with the filters a careful human would
  apply.
- `heldout.json`: the held-out set, 12 queries written independently, by someone who had seen
  neither the prompt nor the development set.
- `run-eval.mjs`: sends each query through the running API, so it measures the whole pipeline
  (prompt, model, validation, catalogue matching) and not the model alone. It reports the
  metrics below and writes every answer to `results/`.

### Protocol

The development set was written in the same session as the prompt. A prompt tuned until those
queries pass would score well on them and mean nothing. So:

1. **Development set, as committed.** Both models, results recorded as they came.
2. **One tuning round.** Changes made from those failures only, said below with their reasons,
   then the development set run again.
3. **Freeze.** The prompt and validation were committed (e224d3d) before the held-out set ran.
   The held-out set then ran **once per model**, and nothing changed afterwards.
4. **Choice of model** on the held-out numbers, latency included.

**Writing the held-out expectations.** The expected filters were written from the wording. Three
ambiguous ones were settled with the set's author before any run:

- *après 2018* means 2019 onwards;
- *hybride* alone means the three hybrid fuels;
- Volvo, which the catalogue does not have, is expected as not understood rather than as a
  filter.

The first of these contradicts the prompt's rule (*après N* includes N). It was left so on
purpose. Changing the rule after seeing a held-out answer would have leaked the held-out set into
the prompt, so held-out query h02 measures that disagreement honestly.

### What the sets cover

The development set covers:

- makes, models, fuel, gearbox, body type, condition, price, year, mileage and place;
- colloquial forms (*pas chère*, *récente*, *boîte auto*, *12k*, *moins de 5 ans*);
- abbreviations (*vw*, *DSG*) and typos (*renaut megane*, *peugot 208*);
- 14 queries with parts the search cannot filter on;
- one injection attempt.

The held-out set is shorter and plainer, in the way real searches are.

The expected makes and models are those of the local development catalogue: 18 makes and 69
models, the demo seed's.

### Metrics

- **Exact match:** every filter field of a query is right, with nothing missing and nothing
  extra. This is the strictest measure: one wrong field fails the whole query. A request that
  failed counts as a miss.
- **Per-field precision and recall**, over the answered queries:
  - Each filter value counts as one prediction, and each value in a list (two fuels, say) counts
    separately.
  - Precision: of the values applied, how many were asked for.
  - Recall: of the values asked for, how many were applied.
  - Low precision means filters the visitor did not want, which hide good results. Low recall
    means requests that were ignored.
- **Not-understood recall:** on the queries with unsupported parts, the share of those parts that
  were reported. **Spurious rate:** on fully supported queries, how often anything was reported
  anyway.
- **Latency:** at the API, including validation and catalogue lookups. The timeout is 5 s.

### Running it

```sh
# a backend with the provider on and the per-address limit off (the key comes from .env)
AUTOX_AI_RATE_LIMIT_ENABLED=false AUTOX_AI_MODEL=openai/gpt-oss-120b mvn spring-boot:run   # in backend/
node eval/ai-search/run-eval.mjs http://localhost:8081 --set queries --label gpt-oss-120b
node eval/ai-search/run-eval.mjs http://localhost:8081 --set heldout --label gpt-oss-120b
```

It waits 12 seconds between queries, which keeps a run inside the free tier's token budget. A 503
is retried once after 30 seconds, for the per-minute limit the eval's own pace can hit. It is not
retried more: a validation refusal repeats, and it is a failure a visitor would see.

### Results

All runs took place on 1 November 2026, on Groq's free tier: `openai/gpt-oss-20b` and
`openai/gpt-oss-120b`, temperature 0, reasoning effort low. Every answer is in
`eval/ai-search/results/`.

**Development set (42), before tuning.** This is the prompt and validation as first committed:

| | gpt-oss-20b | gpt-oss-120b |
|---|---:|---:|
| Exact match | 57.1 % (24/42) | 90.5 % (38/42) |
| Requests failed | 14 | 0 |
| Precision / recall (micro) | 95.7 % / 94.4 % | 97.3 % / 97.3 % |
| Not understood reported | 60.0 % | 78.6 % |
| Spurious not-understood | 5.9 % | 7.4 % |
| Latency p50 / p95 / max | 565 / 947 / 2 493 ms | 713 / 1 385 / 1 510 ms |

All 14 of 20b's failures were Groq refusing an answer that broke the schema: `null` for an empty
list (see [Prompt design](#prompt-design)). The other misses, most of them made by both models:

- *BMW série 3*: the model wrote `3`, and no catalogue rule matched it. 120b did the same with *Mercedes classe A*, writing `A`.
- *audi a3 sportback*: a hatchback body was inferred from "sportback".
- The injection query's "prix maximum à 1 €" became a 1 € ceiling.
- Trims (*GTI*, *S line*, *FR*), power (*150 ch*) and usage (*familial*) went unreported, though
  the search cannot filter on them.
- *après 2019* came back as 2020 from both models, against the prompt's inclusive rule.

**The tuning round.** One set of changes, each aimed at a cause rather than at a query:

- Lists may be `null` in the schema.
- A model written without its family word matches the make's one model whose name ends that way.
- Prices under 100 € are dropped.
- The prompt:
  - says not to infer body, fuel or gearbox from a model or version name;
  - spells out *hybride* alone;
  - names versions, power and what the car is for as things to report, with examples taken from
    neither query set;
  - states that every requirement fills a field or is reported.

The *après* rule was not changed (see [Protocol](#protocol)).

**Development set (42), after tuning:**

| | gpt-oss-20b | gpt-oss-120b |
|---|---:|---:|
| Exact match | 90.5 % (38/42) | 95.2 % (40/42) |
| Requests failed | 1 | 0 |
| Precision / recall (micro) | 97.3 % / 99.1 % | 98.2 % / 98.2 % |
| Not understood reported | 92.9 % | 71.4 % |
| Spurious not-understood | 0.0 % | 0.0 % |
| Latency p50 / p95 / max | 580 / 1 394 / 2 154 ms | 793 / 1 327 / 1 541 ms |

What remained:

- **20b:**
  - one refusal, *Dacia Duster 4x4*, where it wrote `"bodyType": "SUV"`, a string where the
    schema says list;
  - *récente* also set as an upper year bound;
  - *DSG* read as semi-automatic;
  - the hatchback still inferred from "sportback".
- **120b:**
  - *après 2019* and *moins de 5 ans* each one year off;
  - fewer unsupported parts reported than before (71.4 %, down from 78.6 %): trims and colour
    were applied correctly but not listed.

These numbers are on the set the prompt was tuned against. They show what the round fixed, and
they are not the estimate of quality.

**Held-out set (12), prompt frozen, one run per model.** This is the estimate:

| | gpt-oss-20b | gpt-oss-120b |
|---|---:|---:|
| Exact match | 83.3 % (10/12) | **91.7 % (11/12)** |
| Requests failed | 0 | 0 |
| Precision / recall (micro) | 95.5 % / 97.7 % | 95.5 % / 97.7 % |
| Not understood reported | 83.3 % | **100 %** |
| Spurious not-understood | 0.0 % | 0.0 % |
| Latency p50 / p95 / max | 637 / 967 / 967 ms | 658 / 994 / 994 ms |

The misses:

- **h05**, *tesla model 3 2021 ou plus récente*, both models:
  - Both added `ELECTRIC`, which the prompt forbids inferring from a model name. It is harmless
    for a Tesla, but it is a rule broken.
  - 120b also read *plus récente* as the *récente* rule (2023, not 2021).
- **h02**, *après 2018*, 20b only: it applied the prompt's inclusive rule (2018). The author reads
  it as 2019, and 120b gave 2019.

On the parts that cannot be filters, 20b left out *couleur blanche* (h07) entirely. Both models
reported Volvo and the XC60 rather than filtering on them, as expected.

### Choice of model

**`openai/gpt-oss-120b`**, now the default (`AUTOX_AI_MODEL`):

- **The held-out set favours it**, 11 of 12 exact against 10 of 12. On twelve queries that is one
  query, too few to decide on alone.
- **It always tells the visitor what it did not apply.** It reported every unsupported part of
  the held-out set. 20b silently dropped a colour, so a visitor asking for a white Clio would have
  been shown every Clio with nothing saying why.
- **It never failed.** Across 96 requests it had no refusals. 20b's answers broke the schema in
  every development run (14, then 1), and each refusal sends a visitor to the fallback. That
  failure mode is the provider's, outside our control, and it varies from run to run.
- **Latency does not separate them.** The held-out p95 was 994 ms against 967 ms, and the slowest
  answer seen in any run was 1.5 s against 2.2 s, against a 5 s timeout. The free tier's limits
  are the same for both models.
- **Its cost is twice 20b's on a paid plan**, which is irrelevant on the free tier.

## Known limits

- **The catalogue bounds it.** A make or model not in the catalogue is reported as not
  understood, even when the model recognised it. That is deliberate: a text filter on an unknown
  name would silently match nothing.
- **One place, no radius.** `location` matches a city or a postal code. *Près de Lyon* is Lyon
  itself, and regions are not understood.
- **No negation.** *Pas diesel* cannot be expressed: the filters are inclusive lists.
- **Approximate thresholds.** *Peu de km* means 50 000 km and *récente* means three years. Both
  are documented, but they are rules of thumb.
- **Not deterministic.** Temperature 0 is not a guarantee on hosted inference. In the first
  development run, 2 of 20b's 16 refused queries succeeded on the retry, unchanged. The eval is a
  measurement on one day, not a proof.
- **The schema is the provider's promise, not ours.** Strict mode did not stop gpt-oss-20b
  breaking it. 120b has not broken it so far, but a provider-side refusal is always possible, and
  its outcome is the fallback search.
- ***Après N* includes N.** The held-out author, and both models most of the time, read it as
  strictly after. The rule was kept so the held-out set stayed unseen. Switching it is a one-line
  prompt change, and the evaluation should be re-run with a fresh held-out set if it is made.
- **Rules that the models bend.** Both inferred `ELECTRIC` from *Tesla Model 3*, and 120b
  read *plus récente* as the *récente* rule. Validation cannot catch a value that is valid but not
  asked for.
- **Small held-out set.** Twelve queries estimate quality coarsely. One query is 8 points of exact
  match.
- **Quota.** The free tier allows about 1 000 requests a day per model. Beyond that every request
  is a 503 and visitors get the ordinary search, which is the intended failure.
- **French only.** The prompt, the lexicon and the eval are French. Another market would need its
  own.
