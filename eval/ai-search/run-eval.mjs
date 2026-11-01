#!/usr/bin/env node
/**
 * Evaluates natural-language search against the real model, through the real API.
 *
 * Not part of CI: it spends a provider's quota and its answers are not deterministic enough to
 * gate a build on. Run it by hand when the prompt, the validation or the model changes, and
 * record the result in docs/ai-search.md.
 *
 * What it measures is the whole pipeline a visitor gets — prompt, model, server-side checks,
 * catalogue matching — not the model alone: each query goes to POST /api/v1/search/interpret,
 * and the criteria that come back are compared with queries.json.
 *
 * Setup: a backend with the provider on and the per-address limit off, for example
 *   AUTOX_AI_PROVIDER=groq AUTOX_AI_API_KEY=… AUTOX_AI_MODEL=openai/gpt-oss-20b \
 *   AUTOX_AI_RATE_LIMIT_ENABLED=false mvn spring-boot:run
 *
 * Usage: node eval/ai-search/run-eval.mjs [api base url] [--set queries|heldout] [--pause seconds] [--label name]
 *   --set picks the query file: queries.json, the development set (default), or heldout.json,
 *   written independently and run once against a frozen prompt (see docs/ai-search.md).
 *   Writes eval/ai-search/results/<date>-<set>-<label>.json and prints a Markdown summary.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 ? args.splice(index, 2)[1] : fallback;
};
// Between requests. Groq's free tier allows about 8 000 tokens a minute for these models and a
// request is around 1 500, so a dozen seconds keeps a run under the limit instead of into it.
const pauseSeconds = Number(option('--pause', '12'));
const label = option('--label', 'run');
const set = option('--set', 'queries');
if (!/^[a-z-]+$/.test(set)) throw new Error(`--set must be a file name in eval/ai-search, not ${set}`);
const base = (args[0] ?? 'http://localhost:8081').replace(/\/$/, '');

const FIELDS = ['make', 'model', 'yearFrom', 'yearTo', 'priceMin', 'priceMax', 'mileageMin', 'mileageMax',
  'fuel', 'gearbox', 'body', 'condition', 'location', 'goodDeals'];
const LISTS = new Set(['fuel', 'gearbox', 'body', 'condition']);
const year = new Date().getFullYear();

const { queries } = JSON.parse(readFileSync(join(here, `${set}.json`), 'utf8'));

// --- the API's ids, as the slugs the expectations are written in -----------------------
const getJson = async (path) => {
  const response = await fetch(base + path);
  if (!response.ok) throw new Error(`${path}: ${response.status}`);
  return response.json();
};
const capabilities = await getJson('/api/v1/search/capabilities');
if (!capabilities.interpret) {
  console.error(`${base} has natural-language search off (autox.ai.provider=none). Nothing to evaluate.`);
  process.exit(2);
}
const makes = await getJson('/api/v1/reference/makes');
const makeSlug = new Map(makes.map((make) => [make.id, make.slug]));
const modelSlug = new Map();
const modelSlugOf = async (makeId, modelId) => {
  if (!modelSlug.has(makeId)) {
    const models = await getJson(`/api/v1/reference/makes/${makeId}/models`);
    modelSlug.set(makeId, new Map(models.map((model) => [model.id, model.slug])));
  }
  return modelSlug.get(makeId).get(modelId) ?? `?${modelId}`;
};
const countries = await getJson('/api/v1/reference/countries');
const minorUnit = countries.find((country) => country.currencyCode === 'EUR')?.currencyMinorUnit ?? 2;

/** The criteria the API returned, in the expectations' terms. */
async function normalise(criteria) {
  const euros = (minor) => (minor == null || criteria.currency !== 'EUR' ? undefined : Math.round(minor / 10 ** minorUnit));
  const out = {
    make: criteria.makeId ? makeSlug.get(criteria.makeId) ?? `?${criteria.makeId}` : undefined,
    model: criteria.modelId ? await modelSlugOf(criteria.makeId, criteria.modelId) : undefined,
    yearFrom: criteria.yearFrom ?? undefined,
    yearTo: criteria.yearTo ?? undefined,
    priceMin: euros(criteria.priceMin),
    priceMax: euros(criteria.priceMax),
    mileageMin: criteria.mileageMin ?? undefined,
    mileageMax: criteria.mileageMax ?? undefined,
    fuel: criteria.fuelType ?? [],
    gearbox: criteria.gearbox ?? [],
    body: criteria.bodyType ?? [],
    condition: criteria.condition ?? [],
    location: criteria.location ?? undefined,
    goodDeals: (criteria.priceLevel ?? []).includes('GOOD_DEAL') || undefined,
  };
  return out;
}

function expectedOf(query) {
  const expected = { ...query.expected };
  for (const field of ['yearFrom', 'yearTo']) {
    const value = expected[field];
    if (typeof value === 'string' && value.startsWith('now-')) expected[field] = year - Number(value.slice(4));
  }
  return expected;
}

const fold = (text) => String(text).normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().replace(/[’']/g, "'").trim();
const same = (field, a, b) => (field === 'location' ? fold(a) === fold(b) : a === b);

// --- scoring -------------------------------------------------------------------------
const counts = Object.fromEntries(FIELDS.map((field) => [field, { tp: 0, fp: 0, fn: 0 }]));

/** True positives, false positives and false negatives of one query, per field. */
function score(expected, actual) {
  const mistakes = [];
  for (const field of FIELDS) {
    const count = counts[field];
    if (LISTS.has(field)) {
      const want = new Set(expected[field] ?? []);
      const got = new Set(actual[field] ?? []);
      for (const value of got) (want.has(value) ? count.tp++ : (count.fp++, mistakes.push(`${field} +${value}`)));
      for (const value of want) if (!got.has(value)) (count.fn++, mistakes.push(`${field} -${value}`));
    } else {
      const want = expected[field];
      const got = actual[field];
      if (want !== undefined && got !== undefined && same(field, want, got)) count.tp++;
      else {
        if (got !== undefined) (count.fp++, mistakes.push(`${field}=${got}`));
        if (want !== undefined) (count.fn++, mistakes.push(`${field} missing ${want}`));
      }
    }
  }
  return mistakes;
}

// --- the run -----------------------------------------------------------------------------
const results = [];
for (const [index, query] of queries.entries()) {
  if (index > 0) await sleep(pauseSeconds * 1000);
  let attempt = 0;
  let response;
  let started;
  // A 503 can be the provider's per-minute limit, which the eval's own pace can hit and a visitor
  // would not: wait 30 s and try once more. Not more: a 503 that is the model's answer failing
  // validation repeats at temperature 0, and is a real failure to report, not to wait out.
  for (;;) {
    started = performance.now();
    response = await fetch(`${base}/api/v1/search/interpret`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: query.query }),
    });
    if (response.status !== 503 || attempt >= 1) break;
    attempt++;
    process.stderr.write(`  ${query.id}: 503, waiting 30 s (retry ${attempt})\n`);
    await sleep(30_000);
  }
  const latencyMs = Math.round(performance.now() - started);

  if (!response.ok) {
    results.push({ id: query.id, query: query.query, status: response.status, latencyMs, retried: attempt > 0, failed: true });
    process.stderr.write(`  ${query.id}: FAILED ${response.status}\n`);
    continue;
  }
  const body = await response.json();
  const expected = expectedOf(query);
  const actual = await normalise(body.criteria);
  const mistakes = score(expected, actual);

  let unsupportedRecall = null;
  if (Array.isArray(query.unsupported) && query.unsupported.length > 0) {
    const reported = body.notUnderstood.map(fold).join(' | ');
    const found = query.unsupported.filter((word) => reported.includes(fold(word)));
    unsupportedRecall = found.length / query.unsupported.length;
  }
  const spurious = Array.isArray(query.unsupported) && query.unsupported.length === 0 && body.notUnderstood.length > 0;

  results.push({
    id: query.id, query: query.query, tags: query.tags, status: response.status, latencyMs, retried: attempt > 0,
    exact: mistakes.length === 0, mistakes, expected,
    actual: Object.fromEntries(Object.entries(actual).filter(([, v]) => v !== undefined && !(Array.isArray(v) && !v.length))),
    summary: body.summary, notUnderstood: body.notUnderstood, unsupportedRecall, spurious,
  });
  process.stderr.write(`  ${query.id}: ${mistakes.length === 0 ? 'exact' : mistakes.join(', ')} (${latencyMs} ms)\n`);
}

// --- report ----------------------------------------------------------------------------
const answered = results.filter((result) => !result.failed);
const ratio = (n, d) => (d === 0 ? null : n / d);
const fieldTable = FIELDS.map((field) => {
  const { tp, fp, fn } = counts[field];
  return { field, support: tp + fn, precision: ratio(tp, tp + fp), recall: ratio(tp, tp + fn), tp, fp, fn };
});
const total = Object.values(counts).reduce((sum, c) => ({ tp: sum.tp + c.tp, fp: sum.fp + c.fp, fn: sum.fn + c.fn }), { tp: 0, fp: 0, fn: 0 });
const latencies = answered.map((result) => result.latencyMs).sort((a, b) => a - b);
const percentile = (p) => (latencies.length ? latencies[Math.min(latencies.length - 1, Math.floor((p / 100) * latencies.length))] : null);
const withUnsupported = answered.filter((result) => result.unsupportedRecall !== null);
const withoutUnsupported = answered.filter((result) => Array.isArray(queries.find((q) => q.id === result.id).unsupported)
  && queries.find((q) => q.id === result.id).unsupported.length === 0);

const report = {
  set,
  label,
  date: new Date().toISOString(),
  api: base,
  queries: queries.length,
  answered: answered.length,
  failed: results.length - answered.length,
  retried: results.filter((result) => result.retried).length,
  exactMatch: ratio(answered.filter((result) => result.exact).length, queries.length),
  micro: { precision: ratio(total.tp, total.tp + total.fp), recall: ratio(total.tp, total.tp + total.fn) },
  fields: fieldTable,
  unsupported: {
    recall: withUnsupported.length ? withUnsupported.reduce((sum, r) => sum + r.unsupportedRecall, 0) / withUnsupported.length : null,
    spuriousRate: ratio(withoutUnsupported.filter((result) => result.spurious).length, withoutUnsupported.length),
  },
  latencyMs: { p50: percentile(50), p95: percentile(95), max: latencies.at(-1) ?? null },
  results,
};

mkdirSync(join(here, 'results'), { recursive: true });
const file = join(here, 'results', `${report.date.slice(0, 10)}-${set}-${label}.json`);
writeFileSync(file, `${JSON.stringify(report, null, 2)}\n`);

const f = (value) => (value === null ? '–' : (100 * value).toFixed(1) + ' %');
console.log(`\n### ${set} · ${label} — ${report.date.slice(0, 10)}\n`);
console.log(`Exact match: ${f(report.exactMatch)} (${answered.filter((r) => r.exact).length}/${queries.length})` +
  ` · failed requests: ${report.failed} (retried once: ${report.retried}) · micro precision ${f(report.micro.precision)} · micro recall ${f(report.micro.recall)}`);
console.log(`Not understood: recall of the unsupported parts ${f(report.unsupported.recall)}` +
  ` · spurious on fully supported queries ${f(report.unsupported.spuriousRate)}`);
console.log(`Latency: p50 ${report.latencyMs.p50} ms · p95 ${report.latencyMs.p95} ms · max ${report.latencyMs.max} ms\n`);
console.log('| Field | Support | Precision | Recall |\n|---|---:|---:|---:|');
for (const row of fieldTable) console.log(`| ${row.field} | ${row.support} | ${f(row.precision)} | ${f(row.recall)} |`);
console.log('\nMisses:');
for (const result of results.filter((r) => r.failed || !r.exact)) {
  console.log(`- ${result.id}: ${result.failed ? `request failed (${result.status})` : result.mistakes.join(', ')}`);
}
console.log(`\nWritten to ${file}`);
