/**
 * The Content-Security-Policy and the other security headers of the web app, as data.
 *
 * Pure functions, no I/O: `build.mjs` writes what these return into the built site, and
 * `security-headers.test.mjs` holds the properties that matter in place.
 *
 * WHY THE ORIGINS ARE PARAMETERS. The policy has to name every origin the app talks to,
 * and two of them are only known once the hosts are chosen: where the API lives, and
 * where photos are stored. They cannot be `*` — a policy that allows any origin for
 * `connect-src` lets an injected script send the session's tokens anywhere, which is the
 * one thing the policy is for.
 */

/** Reduces a URL to its origin; passes a CSP wildcard host (`https://*.example.com`) through. */
export function originOf(value) {
  const text = String(value ?? '').trim();
  if (!text) {
    return null;
  }
  if (/^https?:\/\/\*\.[a-z0-9.-]+(:\d+)?$/i.test(text)) {
    return text.toLowerCase();
  }
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new Error(`Not a URL: "${text}". Give an origin such as https://api.example.com`);
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    throw new Error(`Not an http(s) origin: "${text}"`);
  }
  return url.origin;
}

/** Splits a comma- or space-separated list of origins, dropping blanks and duplicates. */
export function originsOf(list) {
  const seen = new Set();
  for (const part of String(list ?? '').split(/[\s,]+/)) {
    const origin = originOf(part);
    if (origin) {
      seen.add(origin);
    }
  }
  return [...seen];
}

/**
 * The policy's directives, as an ordered map of name to sources.
 *
 * @param {object} options
 * @param {string} [options.apiOrigin]       where the API is, when not the site's own origin
 * @param {string[]} [options.storageOrigins] where photos are read from and uploaded to
 * @param {boolean} [options.framing]        include `frame-ancestors` (a header can; a
 *                                           `<meta>` cannot, and browsers warn if it tries)
 */
export function directives({ apiOrigin = null, storageOrigins = [], framing = true } = {}) {
  const self = "'self'";
  const policy = {
    // Anything not named below is refused.
    'default-src': [self],

    // The app's own bundles and nothing else: no inline script, no eval, no third party.
    // This is the directive that turns an injected <script> into a console error.
    'script-src': [self],

    // 'unsafe-inline' for STYLES only. Angular adds each component's styles to the page
    // as a <style> element at runtime, and without it the site renders unstyled. A style
    // cannot read localStorage or call the API, so this does not weaken what script-src
    // protects; removing it needs a per-response nonce, which needs a server, which a
    // static site does not have.
    'style-src': [self, "'unsafe-inline'"],

    // Photos come from object storage. `data:` is for the inline SVG the logo uses, and
    // `blob:` for a local preview of a file before it is uploaded.
    'img-src': [self, 'data:', 'blob:', ...storageOrigins],

    'font-src': [self],

    // XHR: the API, and object storage — a photo is PUT straight to a pre-signed URL.
    'connect-src': [self, ...(apiOrigin ? [apiOrigin] : []), ...storageOrigins],

    'object-src': ["'none'"],
    'frame-src': ["'none'"],
    'worker-src': [self],
    'manifest-src': [self],

    // A <base> injected into the page would re-point every relative script URL.
    'base-uri': [self],
    // No form on this site posts anywhere: they are all handled in script.
    'form-action': [self],
  };
  if (framing) {
    // Nobody may put this site in a frame: the defence against clickjacking.
    policy['frame-ancestors'] = ["'none'"];
  }
  return policy;
}

/** The policy as the single string a header or a `<meta>` carries. */
export function contentSecurityPolicy(options) {
  return Object.entries(directives(options))
    .map(([name, sources]) => `${name} ${[...new Set(sources)].join(' ')}`)
    .join('; ');
}

/**
 * Every security header for the site's HTML, in the order a reader would check them.
 *
 * `Strict-Transport-Security` is included only for an https site: sent over plain http it
 * is ignored, and on a host name that later loses its certificate it locks visitors out
 * for as long as `max-age` says.
 */
export function securityHeaders(options = {}) {
  const headers = {
    'Content-Security-Policy': contentSecurityPolicy({ ...options, framing: true }),
    'X-Content-Type-Options': 'nosniff',
    // For browsers that predate frame-ancestors.
    'X-Frame-Options': 'DENY',
    // Listing ids are in URLs, and so, briefly, is a password-reset token.
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
    'Cross-Origin-Opener-Policy': 'same-origin',
  };
  if (options.https) {
    headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains';
  }
  return headers;
}

/**
 * A `_headers` file, the format Netlify and Cloudflare Pages read from the site root.
 *
 * Hashed bundles are immutable and cached for a year; `index.html` is never cached, or a
 * deploy would not reach a visitor until their cache expired.
 */
export function headersFile(options = {}) {
  const lines = ['/*'];
  for (const [name, value] of Object.entries(securityHeaders(options))) {
    lines.push(`  ${name}: ${value}`);
  }
  lines.push('', '/index.html', '  Cache-Control: no-cache', '');
  for (const pattern of ['/*.js', '/*.css', '/media/*']) {
    lines.push(pattern, '  Cache-Control: public, max-age=31536000, immutable', '');
  }
  return lines.join('\n');
}

/**
 * What is wrong with a built `index.html`, as far as the policy is concerned.
 *
 * `script-src 'self'` forbids inline script and inline event handlers. The build must not
 * emit either, or the deployed page breaks in a way the development server never shows —
 * so the build checks, and fails with the offending markup rather than shipping it.
 */
export function inlineScriptProblems(html) {
  const problems = [];
  for (const match of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)) {
    const [, attributes, body] = match;
    const data = /\btype\s*=\s*["']?(application\/(ld\+)?json|importmap)/i.test(attributes);
    if (!/\bsrc\s*=/.test(attributes) && body.trim() && !data) {
      problems.push(`inline <script>: ${body.trim().slice(0, 80)}`);
    }
  }
  for (const match of html.matchAll(/<[a-z][^>]*\s(on[a-z]+)\s*=\s*["'][^"']*["'][^>]*>/gi)) {
    problems.push(`inline event handler ${match[1]}: ${match[0].slice(0, 100)}`);
  }
  return problems;
}

/**
 * Puts the policy into the page itself, at the top of `<head>`.
 *
 * A `<meta>` policy is weaker than a header — it cannot carry `frame-ancestors`, and it
 * only governs what the parser reaches after it — so it is the fallback, for a host that
 * serves files and offers no way to set headers. It goes before anything that loads a
 * resource.
 */
export function withPolicyMeta(html, options = {}) {
  const policy = contentSecurityPolicy({ ...options, framing: false });
  const tag = `<meta http-equiv="Content-Security-Policy" content="${policy.replaceAll('"', '&quot;')}">`;
  const stripped = html.replace(/\s*<meta http-equiv="Content-Security-Policy"[^>]*>/gi, '');
  if (!/<head[^>]*>/i.test(stripped)) {
    throw new Error('index.html has no <head>');
  }
  // After the charset declaration when there is one: that has to sit within the first
  // kilobyte of the document, and a long list of origins must not push it out. It loads
  // nothing, so the policy loses nothing by following it.
  const charset = /<meta\s+charset=[^>]*>/i;
  if (charset.test(stripped)) {
    return stripped.replace(charset, (meta) => `${meta}\n    ${tag}`);
  }
  return stripped.replace(/<head[^>]*>/i, (head) => `${head}\n    ${tag}`);
}

/**
 * Makes the link-preview tags absolute.
 *
 * The crawlers that build a preview — for a message, a post, a search result — do not
 * resolve a relative `og:image` against the page. The source keeps them relative because
 * the site's address is a deployment fact; this stamps it in once it is known.
 */
export function withAbsoluteUrls(html, siteUrl) {
  const origin = originOf(siteUrl);
  if (!origin) {
    return html;
  }
  return html
    .replace(/(<meta\s+(?:property|name)="(?:og:image|twitter:image)"\s+content=")\/?(?!https?:)([^"]*)"/gi, `$1${origin}/$2"`)
    .replace(/(<meta\s+property="og:url"\s+content=")[^"]*"/i, `$1${origin}/"`)
    .replace(/(<link\s+rel="canonical"\s+href=")[^"]*"/i, `$1${origin}/"`);
}
