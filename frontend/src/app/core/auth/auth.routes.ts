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
