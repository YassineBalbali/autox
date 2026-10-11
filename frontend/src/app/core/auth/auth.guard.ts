import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';

import { ProfileService, UserRole } from '../api';
import { FORBIDDEN_ROUTE, RETURN_URL_PARAM, SIGN_IN_ROUTE } from './auth.routes';
import { AuthService } from './auth.service';

/**
 * Requires a session. Sends an anonymous visitor to sign in, remembering where they were.
 *
 * <p>A guard is a courtesy to the user, never a security boundary. It stops a signed-out
 * visitor from reaching a page that would only show them errors; it does not protect data.
 * Every one of these routes is also enforced by the API, which is the only place that
 * matters — the bundle is public, and anybody can edit a `canActivate` out of it.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree([SIGN_IN_ROUTE], {
    queryParams: { [RETURN_URL_PARAM]: state.url },
  });
};

/**
 * Requires a session <em>and</em> one of the given roles.
 *
 * <pre>
 *   { path: 'pro', canActivate: [roleGuard(UserRole.PROFESSIONNEL, UserRole.ADMIN)], ... }
 * </pre>
 *
 * <p>The role lives in the access token, but the client does not read it from there. A JWT
 * payload is base64, not a secret — a user can rewrite `"role":"ADMIN"` in their own
 * storage in a few seconds, and a client that trusted it would render an admin interface
 * for them. Harmless in itself, since every call behind it still fails at the API, but it
 * produces a screen full of 403s instead of an honest refusal, and it invites the belief
 * that the client is enforcing something.
 *
 * <p>So the role comes from {@code GET /api/v1/me}: fetched once per application load,
 * then held in {@link AuthService}. That is a round trip on the first guarded navigation,
 * which is the right price.
 */
export function roleGuard(...roles: readonly UserRole[]): CanActivateFn {
  return (_route, state) => {
    const auth = inject(AuthService);
    const profile = inject(ProfileService);
    const router = inject(Router);

    if (!auth.isAuthenticated()) {
      return router.createUrlTree([SIGN_IN_ROUTE], {
        queryParams: { [RETURN_URL_PARAM]: state.url },
      });
    }

    if (auth.user() !== null) {
      return decide(auth.hasAnyRole(...roles), router);
    }

    // Session restored from storage, profile not loaded yet: the first guarded navigation
    // after a page reload. Resolve it before deciding rather than guessing.
    return profile.getOwnProfile().pipe(
      map((me) => {
        auth.setUser({
          id: me.id,
          email: me.email,
          role: me.role,
          status: me.status,
          displayName: me.displayName,
          emailVerified: me.emailVerified,
          countryCode: me.countryCode,
          locale: me.locale,
        });
        return decide(auth.hasAnyRole(...roles), router);
      }),
      catchError(() => {
        // The token did not survive. The interceptor has already cleared the session and
        // started a redirect by this point; refusing here keeps the route from rendering.
        return of(
          router.createUrlTree([SIGN_IN_ROUTE], {
            queryParams: { [RETURN_URL_PARAM]: state.url },
          }),
        );
      }),
    ) as Observable<boolean | import('@angular/router').UrlTree>;
  };
}

function decide(allowed: boolean, router: Router) {
  return allowed ? true : router.createUrlTree([FORBIDDEN_ROUTE]);
}
