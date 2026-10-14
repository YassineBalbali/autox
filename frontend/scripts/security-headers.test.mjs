// Run with `npm run test:scripts` (node --test). Not part of `ng test`: these are build
// scripts, and they are tested with the runtime that runs them.
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  contentSecurityPolicy,
  directives,
  headersFile,
  inlineScriptProblems,
  originOf,
  originsOf,
  securityHeaders,
  withAbsoluteUrls,
  withPolicyMeta,
} from './security-headers.mjs';

const API = 'https://api.example.com';
const STORAGE = ['https://photos.example.com', 'https://bucket.s3.example.net'];

describe('the content security policy', () => {
  it('allows scripts from this origin and from nowhere else', () => {
    // The directive the whole policy exists for: with it, an injected <script> or an
    // inline handler does not run, so it cannot read the tokens in localStorage.
    const policy = directives({ apiOrigin: API, storageOrigins: STORAGE });

    assert.deepEqual(policy['script-src'], ["'self'"]);
    const whole = contentSecurityPolicy({ apiOrigin: API, storageOrigins: STORAGE });
    assert.doesNotMatch(whole, /script-src[^;]*unsafe-inline/);
    assert.doesNotMatch(whole, /unsafe-eval/);
  });

  it('never allows every origin, in any directive', () => {
    const whole = contentSecurityPolicy({ apiOrigin: API, storageOrigins: STORAGE });

    for (const directive of whole.split('; ')) {
      const sources = directive.split(' ').slice(1);
      assert.ok(!sources.includes('*'), directive);
      assert.ok(!sources.includes('https:'), directive);
      assert.ok(!sources.includes('http:'), directive);
    }
  });

  it('lets the app call the API and object storage, and nothing else', () => {
    // connect-src is where a stolen token would be sent. It lists exactly what the app
    // talks to: itself, the API, and the storage a photo is uploaded to.
    assert.deepEqual(directives({ apiOrigin: API, storageOrigins: STORAGE })['connect-src'], [
      "'self'",
      API,
      ...STORAGE,
    ]);
  });

  it('is this origin only when nothing is configured', () => {
    // What CI builds, and what a same-origin deployment needs.
    const policy = directives();

    assert.deepEqual(policy['connect-src'], ["'self'"]);
    assert.deepEqual(policy['img-src'], ["'self'", 'data:', 'blob:']);
  });

  it('loads photos from storage, and allows the two inline image forms the app uses', () => {
    assert.deepEqual(directives({ storageOrigins: STORAGE })['img-src'], ["'self'", 'data:', 'blob:', ...STORAGE]);
  });

  it('allows inline styles, which Angular needs, and not inline script', () => {
    const policy = directives();

    assert.ok(policy['style-src'].includes("'unsafe-inline'"));
    assert.ok(!policy['script-src'].includes("'unsafe-inline'"));
  });

  it('closes the directives that default-src does not cover', () => {
    const policy = directives();

    assert.deepEqual(policy['default-src'], ["'self'"]);
    assert.deepEqual(policy['object-src'], ["'none'"]);
    assert.deepEqual(policy['base-uri'], ["'self'"]);
    assert.deepEqual(policy['form-action'], ["'self'"]);
    assert.deepEqual(policy['frame-ancestors'], ["'none'"]);
  });

  it('leaves frame-ancestors out of the <meta> form, where it is invalid', () => {
    assert.equal(directives({ framing: false })['frame-ancestors'], undefined);
    assert.doesNotMatch(withPolicyMeta('<html><head></head></html>'), /frame-ancestors/);
  });
});

describe('origins', () => {
  it('reduces a URL to its origin', () => {
    assert.equal(originOf('https://api.example.com/api/v1/'), 'https://api.example.com');
    assert.equal(originOf('http://localhost:8081'), 'http://localhost:8081');
    assert.equal(originOf('  '), null);
    assert.equal(originOf(undefined), null);
  });

  it('keeps a wildcard host, which is how a provider with per-bucket hosts is named', () => {
    assert.equal(originOf('https://*.r2.cloudflarestorage.com'), 'https://*.r2.cloudflarestorage.com');
  });

  it('refuses what is not an http(s) origin, loudly', () => {
    // A typo here would otherwise become a policy that silently blocks the API.
    assert.throws(() => originOf('api.example.com'), /Not a URL/);
    assert.throws(() => originOf('javascript:alert(1)'), /Not an http/);
    assert.throws(() => originOf('*'), /Not a URL/);
  });

  it('splits a list on commas or spaces and drops duplicates', () => {
    assert.deepEqual(originsOf('https://a.example, https://b.example https://a.example/x'), [
      'https://a.example',
      'https://b.example',
    ]);
    assert.deepEqual(originsOf(''), []);
  });
});

describe('the other headers', () => {
  it('sends the set a scanner checks for', () => {
    const headers = securityHeaders();

    assert.equal(headers['X-Content-Type-Options'], 'nosniff');
    assert.equal(headers['X-Frame-Options'], 'DENY');
    assert.equal(headers['Referrer-Policy'], 'strict-origin-when-cross-origin');
    assert.match(headers['Permissions-Policy'], /geolocation=\(\)/);
    assert.match(headers['Content-Security-Policy'], /frame-ancestors 'none'/);
  });

  it('sends HSTS for an https site only', () => {
    // Over http it is ignored; on a host that loses its certificate it locks visitors out.
    assert.equal(securityHeaders()['Strict-Transport-Security'], undefined);
    assert.match(securityHeaders({ https: true })['Strict-Transport-Security'], /max-age=31536000/);
  });

  it('writes them for every path, and keeps index.html out of caches', () => {
    const file = headersFile({ apiOrigin: API });

    assert.match(file, /^\/\*\n {2}Content-Security-Policy: default-src 'self'/);
    assert.match(file, /\/index\.html\n {2}Cache-Control: no-cache/);
    assert.match(file, /\/\*\.js\n {2}Cache-Control: public, max-age=31536000, immutable/);
  });
});

describe('the built page', () => {
  it('accepts external scripts, JSON data blocks and styles', () => {
    const html = `
      <head><style>body{}</style><link rel="stylesheet" href="styles-ABC.css"></head>
      <body><script src="main-ABC.js" type="module"></script>
      <script type="application/ld+json">{"@type":"WebSite"}</script></body>`;

    assert.deepEqual(inlineScriptProblems(html), []);
  });

  it('reports an inline script', () => {
    const problems = inlineScriptProblems('<script>window.x = 1</script>');

    assert.equal(problems.length, 1);
    assert.match(problems[0], /inline <script>/);
  });

  it('reports an inline event handler — what critical-CSS inlining emits', () => {
    const problems = inlineScriptProblems(
      '<link rel="stylesheet" href="styles.css" media="print" onload="this.media=\'all\'">',
    );

    assert.equal(problems.length, 1);
    assert.match(problems[0], /onload/);
  });

  it('puts the policy first in <head>, once, however often it is applied', () => {
    const page = '<html lang="fr"><head>\n<meta charset="utf-8"><script src="a.js"></script></head></html>';
    const once = withPolicyMeta(page, { apiOrigin: API });
    const twice = withPolicyMeta(once, { apiOrigin: API });

    assert.equal(twice, once);
    assert.equal(once.match(/http-equiv="Content-Security-Policy"/g).length, 1);
    // Before anything it has to govern, and after the charset, which must stay early.
    assert.ok(once.indexOf('Content-Security-Policy') < once.indexOf('<script'));
    assert.ok(once.indexOf('charset') < once.indexOf('Content-Security-Policy'));

    // With no charset to follow, straight after <head>.
    assert.match(withPolicyMeta('<html><head><title>x</title></head></html>'), /<head>\s*<meta http-equiv/);
    assert.match(once, /connect-src 'self' https:\/\/api\.example\.com/);
  });

  it('makes the preview image and the page address absolute once the site URL is known', () => {
    const page = `
      <link rel="canonical" href="/">
      <meta property="og:url" content="/">
      <meta property="og:image" content="/og-image.png">
      <meta name="twitter:image" content="/og-image.png">`;
    const stamped = withAbsoluteUrls(page, 'https://autox.example.com/some/path');

    assert.match(stamped, /property="og:image" content="https:\/\/autox\.example\.com\/og-image\.png"/);
    assert.match(stamped, /name="twitter:image" content="https:\/\/autox\.example\.com\/og-image\.png"/);
    assert.match(stamped, /property="og:url" content="https:\/\/autox\.example\.com\/"/);
    assert.match(stamped, /rel="canonical" href="https:\/\/autox\.example\.com\/"/);
  });

  it('leaves the page alone when the site URL is not known', () => {
    const page = '<meta property="og:image" content="/og-image.png">';

    assert.equal(withAbsoluteUrls(page, ''), page);
  });
});
