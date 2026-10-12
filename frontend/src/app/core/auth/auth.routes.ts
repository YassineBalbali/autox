/**
 * Route paths the auth plumbing needs to know by name.
 *
 * <p>Here rather than inline, because the interceptor, the guards and the sign-in screen
 * all have to agree on them, and they are the one set of paths a wrong value breaks
 * silently — a redirect to a route that does not exist renders an empty page rather than
 * an error.
 *
 * <p>In French, like every other URL the user will see. The app is French-only and the
 * paths are part of the interface.
 */

/** Where an unauthenticated user is sent. */
export const SIGN_IN_ROUTE = '/connexion';

/** Where a signed-in user without the required role is sent. */
export const FORBIDDEN_ROUTE = '/acces-refuse';

/**
 * Query parameter carrying the URL to return to after signing in.
 *
 * <p>Read it back with care when the sign-in screen is built: it comes from the address
 * bar, so an absolute URL here would be an open redirect. Only same-origin paths are safe
 * to navigate to.
 */
export const RETURN_URL_PARAM = 'suivant';

/** Where a signed-in user manages their own account. */
export const ACCOUNT_ROUTE = '/mon-compte';

/**
 * A return URL that is safe to navigate to, or {@link ACCOUNT_ROUTE}.
 *
 * <p>This is the open redirect the constant above warns about, closed. The value arrives
 * from the address bar, so it is attacker-chosen: a link to
 * `/connexion?suivant=https://autox-connexion.example/` would show the real sign-in form
 * and then hand the user to a convincing copy of it, with the credentials they are about
 * to type. Phishing through somebody else's trusted domain is the single most common
 * exploit of a "return to where you were" parameter.
 *
 * <p>So only a path is accepted: one leading slash, and not two. `//evil.example/x` is a
 * protocol-relative URL that browsers resolve to another origin while looking like a path,
 * and it is the case a naive `startsWith('/')` check lets straight through.
 */
export function safeReturnUrl(value: string | null | undefined): string {
  if (typeof value !== 'string' || value.length === 0) {
    return ACCOUNT_ROUTE;
  }
  if (!value.startsWith('/') || value.startsWith('//')) {
    return ACCOUNT_ROUTE;
  }
  // A backslash is treated as a slash by several browsers when resolving a URL, so `/\`
  // is protocol-relative in practice however it reads.
  if (value.startsWith('/\\')) {
    return ACCOUNT_ROUTE;
  }
  // Sending a just-signed-in user back to the sign-in page is a loop, not a return.
  if (value === SIGN_IN_ROUTE || value.startsWith(`${SIGN_IN_ROUTE}?`)) {
    return ACCOUNT_ROUTE;
  }
  return value;
}
