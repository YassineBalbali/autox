// The production dependency audit CI runs: `npm audit --omit=dev`, failing on any HIGH or
// CRITICAL advisory, except the advisories listed in audit-exceptions.json. Run with
// `npm run audit:prod`.
//
// An exception names one advisory in one package, the reason it does not apply, and the date
// it must be reviewed by. From that date it no longer excuses anything, so the audit fails
// again until someone looks. Every exception is explained in docs/weaknesses.md.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const blocking = new Set(['high', 'critical']);

/** The advisory's GHSA id, from its URL. */
export const advisoryId = (advisory) => /GHSA(-[23456789cfghjmpqrvwx]{4}){3}/.exec(advisory.url ?? '')?.[0] ?? null;

/** Checks the exceptions file's entries and returns them; throws on a malformed one. */
export function readExceptions(entries) {
  if (!Array.isArray(entries)) throw new Error('audit-exceptions.json must be a list.');
  for (const entry of entries) {
    const where = `Exception ${JSON.stringify(entry.advisory)}`;
    if (!/^GHSA(-[23456789cfghjmpqrvwx]{4}){3}$/.test(entry.advisory ?? '')) throw new Error(`${where}: "advisory" must be a GHSA id.`);
    if (!entry.package) throw new Error(`${where}: "package" is missing.`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(entry.reviewBy ?? '') || Number.isNaN(Date.parse(entry.reviewBy))) {
      throw new Error(`${where}: "reviewBy" must be a date, YYYY-MM-DD.`);
    }
    if (!entry.reason?.trim()) throw new Error(`${where}: "reason" is missing.`);
  }
  return entries;
}

/**
 * Sorts the HIGH and CRITICAL advisories in an `npm audit --json` report into the ones that
 * fail the audit and the ones an exception excuses. `today` is YYYY-MM-DD; an exception is
 * valid up to the day before its review date.
 */
export function judge(report, exceptions, today) {
  if (report.error) throw new Error(`npm audit failed: ${report.error.summary ?? JSON.stringify(report.error)}`);
  const advisories = new Map();
  for (const vulnerability of Object.values(report.vulnerabilities ?? {})) {
    for (const via of vulnerability.via ?? []) {
      // A string is a dependency that is vulnerable through another package; that package's
      // own entry carries the advisory.
      if (typeof via === 'object' && blocking.has(via.severity)) advisories.set(`${via.name} ${via.url}`, via);
    }
  }
  const result = { failing: [], excused: [], expired: [], unused: [] };
  const used = new Set();
  for (const advisory of advisories.values()) {
    const id = advisoryId(advisory);
    const exception = exceptions.find((entry) => entry.advisory === id && entry.package === advisory.name);
    const found = { id: id ?? advisory.url, package: advisory.name, severity: advisory.severity, title: advisory.title };
    if (!exception) {
      result.failing.push(found);
    } else if (today >= exception.reviewBy) {
      used.add(exception);
      result.expired.push({ ...found, reviewBy: exception.reviewBy });
    } else {
      used.add(exception);
      result.excused.push({ ...found, reviewBy: exception.reviewBy });
    }
  }
  result.unused = exceptions.filter((entry) => !used.has(entry));
  return result;
}

function run() {
  const frontend = join(dirname(fileURLToPath(import.meta.url)), '..');
  const exceptions = readExceptions(JSON.parse(readFileSync(join(frontend, 'audit-exceptions.json'), 'utf8')));
  // npm exits 1 whenever it finds anything; the report on stdout is what counts. Under
  // `npm run`, npm_execpath is npm's own script, run with this Node; no shell either way.
  const [command, args] = process.env.npm_execpath?.endsWith('.js')
    ? [process.execPath, [process.env.npm_execpath]]
    : ['npm', []];
  const audit = spawnSync(command, [...args, 'audit', '--omit=dev', '--json'], {
    cwd: frontend,
    encoding: 'utf8',
    maxBuffer: 64 << 20,
  });
  if (!audit.stdout?.trim()) throw new Error(`npm audit printed no report.\n${audit.stderr ?? ''}`);
  const today = new Date().toISOString().slice(0, 10);
  const { failing, excused, expired, unused } = judge(JSON.parse(audit.stdout), exceptions, today);

  for (const a of excused) {
    console.log(`Excused until ${a.reviewBy}: ${a.id} (${a.severity}) in ${a.package}, ${a.title}. See docs/weaknesses.md.`);
  }
  for (const entry of unused) {
    console.log(`::warning::Exception ${entry.advisory} (${entry.package}) no longer matches anything; remove it.`);
  }
  for (const a of expired) {
    console.log(`::error::The exception for ${a.id} in ${a.package} was due for review on ${a.reviewBy}. Review it, then fix, renew or remove it.`);
  }
  for (const a of failing) {
    console.log(`::error::${a.id} (${a.severity}) in ${a.package}: ${a.title}`);
  }
  if (failing.length + expired.length > 0) process.exit(1);
  console.log('No HIGH or CRITICAL advisory in the production dependencies, apart from the exceptions above.');
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    run();
  } catch (error) {
    console.log(`::error::${error.message}`);
    process.exit(1);
  }
}
