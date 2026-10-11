import {
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
  HttpStatusCode,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { RETURN_URL_PARAM, SIGN_IN_ROUTE } from './auth.routes';
import { AuthService, SKIP_AUTH } from './auth.service';

/**
 * Attaches the access token, and renews it once when the API says it is no longer good.
 *
 * <p>The shape to understand is the 401 path:
 *
 * <ol>
 *   <li>A request comes back 401.</li>
 *   <li>The interceptor asks {@link AuthService.refresh}, which returns the <b>one</b>
 *       refresh call in flight — several failed requests share it.</li>
 *   <li>When it resolves, the original request is replayed with the new token.</li>
 *   <li>If it fails, the session is cleared and the user is sent to sign in.</li>
 * </ol>
 *
 * <p>The retry is sent with {@code next(...)}, which continues down the remaining chain
 * rather than re-entering this interceptor, so a second 401 on the replay surfaces to the
 * caller instead of starting another refresh. One renewal per request, by construction.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  // Both injected here, at the top: an interceptor function body runs inside an injection
  // context, but the callbacks below do not, so `inject()` from inside `catchError` throws.
  const auth = inject(AuthService);
  const router = inject(Router);

  if (request.context.get(SKIP_AUTH) || isPublicAuthEndpoint(request.url)) {
    return next(request);
  }

  const token = auth.accessToken();
  const authorised = token ? withBearer(request, token) : request;

  return next(authorised).pipe(
    catchError((error: unknown) => {
      if (!shouldAttemptRenewal(error, auth)) {
        return throwError(() => error);
      }

      return auth.refresh().pipe(
        switchMap((renewed) => next(withBearer(request, renewed))),
        catchError((refreshError: unknown) => {
          // The refresh token is spent, revoked, or its family was killed by a replay.
          // There is no recovering from this in the client: the session is over.
          auth.clearSession();
          void router.navigate([SIGN_IN_ROUTE], {
            // So the user lands back where they were once they have signed in again.
            queryParams: { [RETURN_URL_PARAM]: router.url },
          });
          return throwError(() => refreshError);
        }),
      );
    }),
  );
};

function withBearer<T>(request: HttpRequest<T>, token: string): HttpRequest<T> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Only a 401 on a request that had a session behind it is worth renewing.
 *
 * <p>A 403 is not: the token is valid and the account simply may not do this, so renewing
 * would produce an identical 403 and one wasted round trip. A 401 with no refresh token is
 * not either — there is nothing to renew with, and the caller should see the 401.
 */
function shouldAttemptRenewal(error: unknown, auth: AuthService): boolean {
  return (
    error instanceof HttpErrorResponse &&
    error.status === HttpStatusCode.Unauthorized &&
    auth.hasRefreshToken()
  );
}

/**
 * The endpoints that must never carry a bearer token or trigger a renewal.
 *
 * <p>Two of these genuinely answer 401 in normal use: a wrong password at
 * {@code /auth/login}, and a spent token at {@code /auth/refresh}. Treating either as
 * "renew and retry" would turn a wrong password into a refresh attempt, and a failed
 * refresh into an endless one.
 *
 * <p>Matched on the path only, so it holds whatever the configured base URL is — and
 * checked in addition to the {@link SKIP_AUTH} context token rather than instead of it,
 * because a call made without going through {@link AuthService} would otherwise slip past.
 */
function isPublicAuthEndpoint(url: string): boolean {
  const path = pathOf(url);
  return (
    path === '/api/v1/auth/login' ||
    path === '/api/v1/auth/register' ||
    path === '/api/v1/auth/refresh' ||
    path === '/api/v1/auth/logout' ||
    path === '/api/v1/auth/password/forgot' ||
    path === '/api/v1/auth/password/reset'
  );
}

/** The path of an absolute or relative URL, without query or fragment. */
function pathOf(url: string): string {
  try {
    // The base is only needed to parse a relative URL and is never used.
    return new URL(url, 'http://local').pathname;
  } catch {
    return url;
  }
}
