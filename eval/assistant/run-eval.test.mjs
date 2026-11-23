// node --test eval/assistant/run-eval.test.mjs
//
// The runner against a stand-in API, never Groq: what it does with the daily quota spent.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const RUNNER = join(dirname(fileURLToPath(import.meta.url)), 'run-eval.mjs');
const CASES = ['search-208-brief', 'search-suv-diesel-auto', 'search-clio-cheap-lyon'];

/** A stand-in API whose every turn gets this status, code and Retry-After; counts the turns. */
async function api(status, code, retryAfter) {
  const seen = { turns: 0 };
  const server = createServer((request, response) => {
    if (request.url.endsWith('/capabilities')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ available: false, reason: 'BUSY', maxMessageLength: 500, maxTurns: 12 }));
      return;
    }
    seen.turns += 1;
    response.writeHead(status, { 'Content-Type': 'application/problem+json', ...(retryAfter ? { 'Retry-After': String(retryAfter) } : {}) });
    response.end(JSON.stringify({ code }));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { url: `http://127.0.0.1:${server.address().port}`, seen, close: () => server.close() };
}

function run(url, results) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [RUNNER, url, '--label', 'quota', '--pace', '600', '--only', CASES.join(','), '--results', results]);
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
