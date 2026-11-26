// node --test eval/assistant/run-eval.test.mjs
//
// The runner against a stand-in API, never Groq: what it does with the daily quota spent, and a
// set other than cases.json (--cases), the help questions the pages did not cover, and the
// contact details found in an answer.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), 'run-eval.mjs');
const CASES = ['search-208-brief', 'search-suv-diesel-auto', 'search-clio-cheap-lyon'];

/**
 * A stand-in API whose every turn gets this status, code and Retry-After; counts the turns. With
 * a trace, every turn is answered 200 with it, as the API's evaluation header.
 */
async function api(status, code, retryAfter, trace, body = { blocks: [] }) {
  const seen = { turns: 0 };
  const server = createServer((request, response) => {
    if (request.url.endsWith('/capabilities')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ available: false, reason: 'BUSY', maxMessageLength: 500, maxTurns: 12 }));
      return;
    }
    seen.turns += 1;
    if (trace) {
      response.writeHead(200, { 'Content-Type': 'application/json', 'X-Assistant-Eval': JSON.stringify(trace) });
      response.end(JSON.stringify({ ...body, state: { earlier: [], shown: [], turns: 1 } }));
      return;
    }
    response.writeHead(status, { 'Content-Type': 'application/problem+json', ...(retryAfter ? { 'Retry-After': String(retryAfter) } : {}) });
    response.end(JSON.stringify({ code }));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { url: `http://127.0.0.1:${server.address().port}`, seen, close: () => server.close() };
}

function run(url, results, args = ['--label', 'quota', '--only', CASES.join(',')]) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [RUNNER, url, '--pace', '600', '--results', results, ...args]);
    let output = '';
    child.stdout.on('data', (chunk) => (output += chunk));
    child.stderr.on('data', (chunk) => (output += chunk));
    child.on('close', (code) => resolve({ code, output }));
  });
}

test('stops at the first ASSISTANT_QUOTA, says when to resume, and records no case', async () => {
  const stand = await api(503, 'ASSISTANT_QUOTA', 7200);
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  try {
    const { code, output } = await run(stand.url, results);
    assert.equal(code, 3);
    assert.match(output, /Daily quota reached, resume after .+ Paris time \(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z, in 2 h 0 min\)/);
    assert.equal(stand.seen.turns, 1, 'one turn asked, none after');
    const progress = join(results, 'quota.progress.jsonl');
    assert.ok(!existsSync(progress) || readFileSync(progress, 'utf8').trim() === '', 'no case marked unanswered');
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
});

test('goes on after a plain ASSISTANT_UNAVAILABLE, recording the turn as unanswered', async () => {
  const stand = await api(503, 'ASSISTANT_UNAVAILABLE');
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  try {
    const { code } = await run(stand.url, results);
    assert.equal(code, 0);
    assert.equal(stand.seen.turns, CASES.length);
    const lines = readFileSync(join(results, 'quota.progress.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.deepEqual(lines.map((r) => r.turns[0].unanswered), [true, true, true]);
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
});

test('refuses to run a set with no case yet, as heldout.json is until it is filled', async () => {
  const stand = await api(503, 'ASSISTANT_UNAVAILABLE');
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  try {
    const empty = join(results, 'heldout.json');
    writeFileSync(empty, JSON.stringify({ version: 1, cases: [] }));
    const { code, output } = await run(stand.url, results, ['--cases', empty]);
    assert.equal(code, 2);
    assert.match(output, /heldout\.json has no case yet/);
    assert.equal(stand.seen.turns, 0);
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
});

test('runs another set alone, under its own name, and says which set the report is of', async () => {
  const stand = await api(503, 'ASSISTANT_UNAVAILABLE');
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  const set = join(results, 'mine.json');
  writeFileSync(set, JSON.stringify({ version: 1, cases: [
    { id: 'my-first', category: 'search', turns: [{ message: 'Une Clio', expect: { intent: 'SEARCH' } }] },
  ] }));
  try {
    const { code } = await run(stand.url, results, ['--cases', set]);
    assert.equal(code, 0);
    assert.equal(stand.seen.turns, 1, 'only the set given, not cases.json');
    const progress = readFileSync(join(results, 'mine.progress.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.deepEqual(progress.map((r) => r.id), ['my-first']);
    const report = readdirSync(results).find((name) => name.endsWith('-mine.md'));
    assert.match(readFileSync(join(results, report), 'utf8'), /mine\.json version 1\. 1 of 1 cases run/);
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
});

test('counts NOT_IN_HELP right where a help case accepts it, and lists it apart as a gap in the pages', async () => {
  const trace = { intent: 'HELP', calls: [{ tool: 'search_help', query: 'modifier le prix annonce' }], reasons: ['NOT_IN_HELP'],
    blocks: ['NO_ANSWER'], attempts: 1, tokensIn: 2000, tokensOut: 40 };
  const stand = await api(200, null, null, trace);
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  const set = join(results, 'gaps.json');
  const query = 'modifier le prix annonce';
  writeFileSync(set, JSON.stringify({ version: 1, cases: [
    { id: 'ho-help-change-price', category: 'help', turns: [{ message: 'Comment modifier le prix de mon annonce ?', expect: { anyOf: [
      { intent: 'HELP', calls: [{ tool: 'search_help', query }], blocks: ['HELP_PASSAGE'] },
      { intent: 'HELP', calls: [{ tool: 'search_help', query }], reasons: ['NOT_IN_HELP'] },
    ] } }] },
  ] }));
  try {
    const { code } = await run(stand.url, results, ['--cases', set]);
    assert.equal(code, 0);
    const report = readFileSync(join(results, readdirSync(results).find((name) => name.endsWith('-gaps.md'))), 'utf8');
    assert.match(report, /Turns answered and right:\*\* 1\/1/);
    assert.match(report, /## Help questions not in the help pages \(1\)/);
    assert.match(report, /- \*\*ho-help-change-price\*\* « Comment modifier le prix de mon annonce \? »: searched « modifier le prix annonce »/);
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
});

/** One EXPLAIN turn answered with these blocks; the report and the progress line. */
async function explained(blocks) {
  const trace = { intent: 'EXPLAIN', calls: [{ tool: 'get_listing', ref: 'L1' }], reasons: [], blocks: ['LISTING_FACTS'],
    attempts: 1, tokensIn: 2000, tokensOut: 40 };
  const stand = await api(200, null, null, trace, { blocks });
  const results = mkdtempSync(join(tmpdir(), 'eval-'));
  const set = join(results, 'leak.json');
  writeFileSync(set, JSON.stringify({ version: 1, cases: [
    { id: 'explain', category: 'explain', turns: [{ message: 'Explique-moi', expect: { intent: 'EXPLAIN' } }] },
  ] }));
  try {
    await run(stand.url, results, ['--cases', set]);
    const report = readFileSync(join(results, readdirSync(results).find((name) => name.endsWith('-leak.md'))), 'utf8');
    const progress = JSON.parse(readFileSync(join(results, 'leak.progress.jsonl'), 'utf8').trim());
    return { report, actual: progress.turns[0].actual };
  } finally {
    stand.close();
    rmSync(results, { recursive: true, force: true });
  }
}

const SIGNED = 'https://bucket.example/l/card.webp?X-Amz-Expires=21600&X-Amz-Signature=c2eff347e13716e0dbc8566d83d7510df0f374d96ff683b0490506040f90d0b1';

test('does not take ten digits inside a signed photo URL, a postcode, a price or a mileage for a phone number', async () => {
  const { report, actual } = await explained([{ type: 'LISTING_FACTS',
    listings: [{ id: '01a17901-93d4-7c58-8069-e9f51f4693d0', title: 'Peugeot 208, 40 000 km, 75011 Paris', photoUrl: SIGNED,
      priceAmount: 1099000, mileageKm: 40000, city: 'Paris' }],
    facts: { year: 2020, mileageKm: 40000, priceAmount: 1099000 } }]);
  assert.equal(actual.contactsShown, 0);
  assert.match(report, /Turns answered and right:\*\* 1\/1/);
});

test('still finds a phone number and an e-mail in any text of the answer, and says where', async () => {
  const { report, actual } = await explained([{ type: 'LISTING_FACTS',
    listings: [{ title: '208, appelez le 06 12 34 56 78', photoUrl: SIGNED, city: 'Paris vendeur@exemple.fr' }] }]);
  assert.equal(actual.contactsShown, 2);
  assert.deepEqual(actual.contactsFound.map((f) => [f.kind, f.path]).sort(),
    [['e-mail', 'blocks[0].listings[0].city'], ['phone', 'blocks[0].listings[0].title']]);
  assert.ok(!JSON.stringify(actual).includes('06 12 34 56 78'), 'the number itself is not stored');
  assert.match(report, /2 e-mail or phone number\(s\) in the answer: .*phone 06•+78 at blocks\[0\]\.listings\[0\]\.title/);
});
