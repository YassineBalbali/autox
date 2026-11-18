// The audit gate's rules. Run with `npm run test:scripts`.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { judge, readExceptions } from './audit.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (path) => readFileSync(join(repository, path), 'utf8');

const advisory = (name, id, severity = 'high') => ({
  name,
  severity,
  title: `${name} is vulnerable`,
  url: `https://github.com/advisories/${id}`,
});
/** An `npm audit --json` report: picomatch's own advisory, and a package vulnerable through it. */
const report = (...advisories) => ({
  vulnerabilities: {
    tinyglobby: { name: 'tinyglobby', severity: 'high', via: ['picomatch'] },
    ...Object.fromEntries(advisories.map((a) => [a.name, { name: a.name, severity: a.severity, via: [a] }])),
  },
});
const picomatch = advisory('picomatch', 'GHSA-fc5q-39xc-vjpf');
const exception = {
  advisory: 'GHSA-fc5q-39xc-vjpf',
  package: 'picomatch',
  reviewBy: '2026-12-18',
  reason: 'Build tooling only.',
};

describe('the audit gate', () => {
  it('lets an excused advisory through before its review date', () => {
    const result = judge(report(picomatch), [exception], '2026-11-18');

    assert.deepEqual(result.failing, []);
    assert.deepEqual(result.expired, []);
    assert.deepEqual(result.excused.map((a) => a.id), ['GHSA-fc5q-39xc-vjpf']);
  });

  it('fails on any other HIGH or CRITICAL advisory', () => {
    const other = advisory('lodash', 'GHSA-p6mc-m468-83gw');
    const critical = advisory('axios', 'GHSA-wf5p-g6vw-rhxx', 'critical');

    const result = judge(report(picomatch, other, critical), [exception], '2026-11-18');

    assert.deepEqual(result.failing.map((a) => a.id).sort(), ['GHSA-p6mc-m468-83gw', 'GHSA-wf5p-g6vw-rhxx']);
  });

  it('ignores what is below HIGH, as --audit-level=high did', () => {
    const result = judge(report(advisory('qs', 'GHSA-hrpp-h998-j3pp', 'moderate')), [], '2026-11-18');

    assert.deepEqual(result.failing, []);
  });

  it('excuses the advisory in the package it names, not the same id elsewhere', () => {
    const result = judge(report(advisory('micromatch', 'GHSA-fc5q-39xc-vjpf')), [exception], '2026-11-18');

    assert.deepEqual(result.failing.map((a) => a.package), ['micromatch']);
  });

  it('stops excusing on the review date', () => {
    const result = judge(report(picomatch), [exception], '2026-12-18');

    assert.deepEqual(result.excused, []);
    assert.deepEqual(result.expired.map((a) => a.reviewBy), ['2026-12-18']);
  });

  it('reports an exception that matches nothing any more', () => {
    assert.deepEqual(judge(report(), [exception], '2026-11-18').unused, [exception]);
  });

  it('fails when npm could not audit at all', () => {
    assert.throws(() => judge({ error: { summary: 'ENOTFOUND registry.npmjs.org' } }, [], '2026-11-18'), /ENOTFOUND/);
  });

  it('refuses an exception without an id, a package, a review date or a reason', () => {
    for (const key of ['advisory', 'package', 'reviewBy', 'reason']) {
      assert.throws(() => readExceptions([{ ...exception, [key]: undefined }]), new RegExp(key));
    }
    assert.throws(() => readExceptions([{ ...exception, reviewBy: 'soon' }]), /reviewBy/);
  });
});

describe('the exceptions file', () => {
  const exceptions = readExceptions(JSON.parse(read('frontend/audit-exceptions.json')));

  it('gives each exception at most 30 days before review, counted from today', () => {
    // A renewal is a new review date, and a new 30 days, not a longer one.
    const limit = new Date(Date.now() + 31 * 86_400_000).toISOString().slice(0, 10);
    for (const entry of exceptions) assert.ok(entry.reviewBy <= limit, `${entry.advisory} is reviewed by ${entry.reviewBy}`);
  });

  it('explains each exception in docs/weaknesses.md', () => {
    const doc = read('docs/weaknesses.md');
    for (const entry of exceptions) assert.ok(doc.includes(entry.advisory), `${entry.advisory} is not in docs/weaknesses.md`);
  });
});
