/**
 * The production build: `ng build`, then the two things `ng build` cannot know.
 *
 *   1. WHERE THE API IS. Compiled into the bundle, because a static site has no server to
 *      ask. Empty means "this origin" — the app and the API behind one proxy.
 *   2. THE SECURITY POLICY, which has to name the API's origin and the storage origins.
 *
 * Both come from the environment, so the same commit builds for any host:
 *
 *   AUTOX_API_BASE_URL      https://api.example.com   (empty: same origin as the site)
 *   AUTOX_STORAGE_ORIGINS   one or more origins photos are read from and uploaded to,
 *                           separated by spaces or commas
 *   AUTOX_SITE_URL          https://autox.example.com — makes the link-preview tags
 *                           absolute, and switches on HSTS when it is https
 *
 * With none of them set the build still succeeds, with a policy that allows this origin
 * only. That is what CI builds, and what a same-origin deployment needs.
 *
 * Run through `npm run build`; extra arguments are passed to `ng build`.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  contentSecurityPolicy,
  headersFile,
  inlineScriptProblems,
  originOf,
  originsOf,
  withAbsoluteUrls,
  withPolicyMeta,
} from './security-headers.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist', 'frontend', 'browser');

const fail = (message) => {
  console.error(`\n[build] ${message}\n`);
  process.exit(1);
};

// ---- configuration -----------------------------------------------------------

let apiBaseUrl;
let options;
let siteUrl;
try {
  const api = originOf(process.env.AUTOX_API_BASE_URL);
  siteUrl = originOf(process.env.AUTOX_SITE_URL);
  apiBaseUrl = api ?? '';
  options = {
    apiOrigin: api,
    storageOrigins: originsOf(process.env.AUTOX_STORAGE_ORIGINS),
    https: siteUrl?.startsWith('https://') ?? false,
  };
} catch (error) {
  fail(error.message);
}

// ---- 1. ng build ------------------------------------------------------------

// `--define` replaces the identifier in the compiled code with this literal; see
// src/environments/environment.ts. JSON.stringify, because the value must be a JavaScript
// expression — a string literal, quotes included.
const ng = join(root, 'node_modules', '@angular', 'cli', 'bin', 'ng.js');
const built = spawnSync(
  process.execPath,
  [ng, 'build', '--define', `AUTOX_API_BASE_URL=${JSON.stringify(apiBaseUrl)}`, ...process.argv.slice(2)],
  { cwd: root, stdio: 'inherit' },
);
if (built.status !== 0) {
  process.exit(built.status ?? 1);
}

// A development build is not what ships, and has no reason to be hardened.
if (process.argv.includes('development') || process.argv.some((arg) => arg.includes('configuration=development'))) {
  process.exit(0);
}

// ---- 2. harden what was built -------------------------------------------------

const index = join(dist, 'index.html');
if (!existsSync(index)) {
  fail(`Expected the site at ${index}. If the output path or the locale layout changed, this script has to follow.`);
}

let html = readFileSync(index, 'utf8');

const problems = inlineScriptProblems(html);
if (problems.length > 0) {
  fail(
    'The built index.html contains inline script, which the Content-Security-Policy forbids\n' +
      'and which would therefore break the deployed site:\n\n  ' +
      problems.join('\n  ') +
      '\n\nThe usual cause is critical-CSS inlining; it is switched off in angular.json for this reason.',
  );
}

html = withPolicyMeta(html, options);
html = withAbsoluteUrls(html, siteUrl);
writeFileSync(index, html);
writeFileSync(join(dist, '_headers'), headersFile(options));

// A single-page app answers every path with the same document, and with a 200: a shared
// link to /annonces/… served with a 404 status is one link previews and crawlers take at its
// word. Netlify's rule for that is a rewrite in _redirects. Netlify never lets a rewrite
// shadow a file that exists, so scripts, robots.txt and the preview image are still served
// as themselves; only paths with no file behind them get index.html.
//
// Deliberately no 404.html: with the rewrite in place it would never be served on Netlify,
// and on a host that falls back to it the deep links would carry a 404 status.
writeFileSync(join(dist, '_redirects'), '/*  /index.html  200\n');

const bytes = readdirSync(dist)
  .filter((name) => /\.(js|css)$/.test(name))
  .reduce((total, name) => total + statSync(join(dist, name)).size, 0);

console.log(`
[build] site          ${dist}
[build] API           ${apiBaseUrl || '(same origin as the site)'}
[build] storage       ${options.storageOrigins.join(' ') || '(none: photos will not load unless served from the site origin)'}
[build] site URL      ${siteUrl ?? '(not set: link-preview image left relative)'}
[build] bundles       ${(bytes / 1024).toFixed(0)} kB of script and style
[build] policy        ${contentSecurityPolicy({ ...options, framing: true })}
`);
