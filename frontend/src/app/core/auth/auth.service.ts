import { HttpContext, HttpContextToken } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, finalize, map, shareReplay, throwError } from 'rxjs';

import {
  AuthResponse,
  AuthenticationService,
  RegisterRequest,
  UserRole,
  UserSummary,
} from '../api';
import { TOKEN_STORE, StoredTokens } from './token-store';

/**
 * Marks a request as one the auth interceptor must leave alone.
 *
 * <p>Set on the refresh call itself. Without it, a refresh that answers 401 would be
 * treated as a request to retry, which would trigger another refresh, and so on — and
 * because the API revokes a token family when a rotated token is replayed, that loop does
 * not merely spin, it destroys the session.
 */
export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);

/** Convenience for the generated services, which accept an `HttpContext` per call. */
export function skipAuth(): HttpContext {
  return new HttpContext().set(SKIP_AUTH, true);
}

/**
 * The session: who is signed in, and the tokens that prove it.
 *
 * <p>State is held in signals, so a template reads {@link AuthService.user} directly and
 * the app re-renders without a subscription to manage. Angular 22 is zoneless, which makes
 * signals the only change-detection mechanism that works without `markForCheck`.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly api = inject(AuthenticationService);
  private readonly store = inject(TOKEN_STORE);
  private readonly router = inject(Router);

  private readonly tokens = signal<StoredTokens | null>(null);
  private readonly currentUser = signal<UserSummary | null>(null);

  /** The signed-in account, or `null`. Read this in templates and guards. */
  readonly user = this.currentUser.asReadonly();

  /**
   * Whether a session exists. Note that this is true while an access token is expired but
   * a refresh token is still held — the interceptor will renew on the next 401, and
   * treating the user as signed out in that window would log them out every fifteen minutes.
   */
  readonly isAuthenticated = computed(() => this.tokens() !== null);

  readonly role = computed(() => this.currentUser()?.role ?? null);

  /**
   * The one refresh in flight, if any.
   *
   * <p><b>Sharing this is a correctness requirement, not an optimisation.</b> Refresh
   * tokens rotate on every use, and the API treats a second presentation of a rotated token
   * as theft: it revokes the entire token family and every session descended from that
   * login. So two concurrent refreshes do not waste a round trip — one of them succeeds and
   * the other signs the user out of all their devices.
   *
   * <p>Three requests arriving while a token is expired must therefore produce exactly one
   * call to `/auth/refresh`.
   */
  private refreshInFlight: Observable<string> | null = null;

  /** Rehydrates from storage. Called once, from an APP_INITIALIZER. */
  restore(): void {
    const stored = this.store.read();
    if (!stored) {
      return;
    }
    this.tokens.set(stored);
    // The user summary is deliberately not persisted — it goes stale, and it is cheap to
    // re-read. `GET /api/v1/me` is the authority, and a guard that needs the role will
    // trigger it. Until then the session is "authenticated, role unknown".
  }

  accessToken(): string | null {
    return this.tokens()?.accessToken ?? null;
  }

  hasRefreshToken(): boolean {
    return this.tokens()?.refreshToken != null;
  }

  /** True when the access token is past, or within a second of, its expiry. */
  isAccessTokenExpired(now = Date.now()): boolean {
    const expiresAt = this.tokens()?.accessTokenExpiresAt;
    return expiresAt === undefined || expiresAt - 1000 <= now;
  }

  // --- the session lifecycle ---------------------------------------------

  login(email: string, password: string, deviceLabel?: string): Observable<UserSummary> {
    return this.api
      .login({ loginRequest: { email, password, deviceLabel } }, 'body', false, {
        context: skipAuth(),
      })
      .pipe(map((response) => this.adopt(response)));
  }

  register(registerRequest: RegisterRequest): Observable<UserSummary> {
    return this.api
      .register({ registerRequest }, 'body', false, { context: skipAuth() })
      .pipe(map((response) => this.adopt(response)));
  }

  /**
   * Exchanges the refresh token for a new pair, sharing one call across every caller that
   * asks while it is in flight.
   *
   * <p>The slot is cleared by `finalize` placed <em>before</em> the multicast, so it is
   * released once the call settles rather than when the last subscriber leaves. A request
   * that fails after this refresh has completed starts a fresh one, which is correct; a
   * request that fails during it joins this one, which is the part that matters.
   */
  refresh(): Observable<string> {
    const refreshToken = this.tokens()?.refreshToken;
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token: nothing to renew.'));
    }

    this.refreshInFlight ??= this.api
      .refresh({ refreshRequest: { refreshToken } }, 'body', false, { context: skipAuth() })
      .pipe(
        map((response) => {
          this.adopt(response);
          return requireString(response.accessToken, 'accessToken');
        }),
        finalize(() => {
          this.refreshInFlight = null;
        }),
        // bufferSize 1 so a caller that arrives after the token came back still gets it.
        // refCount false so a component unsubscribing cannot cancel a refresh the rest of
        // the application is waiting on.
        shareReplay({ bufferSize: 1, refCount: false }),
      );

    return this.refreshInFlight;
  }

  /**
   * Ends this device's session on the server, then locally.
   *
   * <p>Local state is cleared whatever the server says. A logout that failed because the
   * token was already revoked, or because the network is down, must still leave the user
   * signed out of the application in front of them — anything else is a sign-out button
   * that sometimes does nothing.
   */
  logout(redirectTo = '/'): void {
    const refreshToken = this.tokens()?.refreshToken;
    if (refreshToken) {
      this.api
        .logout({ refreshRequest: { refreshToken } }, 'body', false, { context: skipAuth() })
        .subscribe({ error: () => undefined });
    }
    this.clearSession();
    void this.router.navigateByUrl(redirectTo);
  }

  /** Drops local session state without calling the server. Used when a refresh fails. */
  clearSession(): void {
    this.refreshInFlight = null;
    this.tokens.set(null);
    this.currentUser.set(null);
    this.store.clear();
  }

  setUser(user: UserSummary | null): void {
    this.currentUser.set(user);
  }

  hasAnyRole(...roles: readonly UserRole[]): boolean {
    const role = this.role();
    return role !== null && roles.includes(role);
  }

  /**
   * Takes a successful auth response into state and storage.
   *
   * <p>Every field of a generated response model is optional, because the backend's schema
   * has no `required` markers on output types — springdoc only infers those from Bean
   * Validation annotations, which responses rightly do not carry. Rather than spread
   * `?.` and `!` through the application, the invariant is asserted once, here, at the
   * boundary where it belongs. A server that stops sending an access token is a bug worth a
   * loud failure, not a silent `undefined`.
   */
  private adopt(response: AuthResponse): UserSummary {
    const accessToken = requireString(response.accessToken, 'accessToken');
    const refreshToken = requireString(response.refreshToken, 'refreshToken');
    const user = response.user;
    if (!user) {
      throw new Error('Auth response carried no user summary.');
    }

    this.tokens.set({
      accessToken,
      refreshToken,
      accessTokenExpiresAt: expiryToEpochMillis(response),
    });
    this.store.write(this.tokens()!);
    this.currentUser.set(user);
    return user;
  }
}

/**
 * Prefers `expiresInSeconds` over the absolute `accessTokenExpiresAt`.
 *
 * <p>The API sends both for exactly this reason: a device with a wrong clock — and phones
 * do have wrong clocks — would compute nonsense from an absolute server instant, while a
 * duration is correct regardless of what the device thinks the time is.
 */
function expiryToEpochMillis(response: AuthResponse): number {
  if (typeof response.expiresInSeconds === 'number' && Number.isFinite(response.expiresInSeconds)) {
    return Date.now() + response.expiresInSeconds * 1000;
  }
  const parsed = response.accessTokenExpiresAt ? Date.parse(response.accessTokenExpiresAt) : NaN;
  // Treat an unusable expiry as "already expired" rather than "never expires": the former
  // costs one extra refresh, the latter means the token is never renewed at all.
  return Number.isNaN(parsed) ? 0 : parsed;
}

function requireString(value: string | undefined, field: string): string {
  if (!value) {
    throw new Error(`Auth response was missing ${field}.`);
  }
  return value;
}

