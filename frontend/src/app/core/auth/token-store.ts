import { InjectionToken } from '@angular/core';

/** The pair the API hands out, plus when the access token stops being usable. */
export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
  /** Epoch milliseconds. Derived from the API's `accessTokenExpiresAt`. */
  accessTokenExpiresAt: number;
}

/**
 * Where the session lives between page loads.
 *
 * <p>An interface with an injection token rather than a concrete service, because the
 * right answer differs per platform and this is the decision most likely to be revisited.
 * A native shell would back it with the device keychain; a future BFF would drop it
 * entirely in favour of an http-only cookie.
 */
export interface TokenStore {
  read(): StoredTokens | null;
  write(tokens: StoredTokens): void;
  clear(): void;
}

export const TOKEN_STORE = new InjectionToken<TokenStore>('autox.tokenStore');

const KEY = 'autox.session';

/**
 * `localStorage`, with the honest caveat.
 *
 * <p><b>The refresh token is a 90-day credential sitting in JavaScript-readable
 * storage.</b> Any script that runs on this origin can read it and use it until the user
 * signs out everywhere — cross-site scripting here is a full, durable account takeover,
 * not a session hijack that ends when the tab closes.
 *
 * <p>It is still the right choice for this app as it stands, and the alternatives are worse
 * rather than better:
 *
 * <ul>
 *   <li><b>In-memory only</b> would log the user out on every reload. The brief requires
 *       sessions that survive for months, and a marketplace people visit twice a week
 *       cannot ask for a password each time.</li>
 *   <li><b>`sessionStorage`</b> has the same XSS exposure and loses the session when the
 *       tab closes, so it pays the cost without buying anything.</li>
 *   <li><b>An http-only cookie</b> is genuinely safer, and the API deliberately does not
 *       use cookies: it is stateless and header-based so that the web app and a native app
 *       share one contract. Getting cookies would mean a backend-for-frontend in front of
 *       the API — a real option later, and a disproportionate one now.</li>
 * </ul>
 *
 * <p>So the mitigation is to not have XSS: a strict Content-Security-Policy at the edge,
 * no `innerHTML` with anything server-provided, and Angular's sanitiser left switched on.
 * Those are deployment and review disciplines, which is why this comment exists rather
 * than a `// TODO`.
 */
export class LocalStorageTokenStore implements TokenStore {
  read(): StoredTokens | null {
    let raw: string | null;
    try {
      raw = localStorage.getItem(KEY);
    } catch {
      // Safari in private mode, and any browser with site data blocked, throw on access
      // rather than returning null. A user with storage disabled gets a session that lasts
      // until they navigate away, which is better than a page that will not load.
      return null;
    }
    if (!raw) {
      return null;
    }

    try {
      const parsed: unknown = JSON.parse(raw);
      return isStoredTokens(parsed) ? parsed : null;
    } catch {
      // Corrupt or from an older shape. Treat as signed out rather than crashing on boot.
      this.clear();
      return null;
    }
  }

  write(tokens: StoredTokens): void {
    try {
      localStorage.setItem(KEY, JSON.stringify(tokens));
    } catch {
      // Out of quota, or blocked. The session still works for this page load.
    }
  }

  clear(): void {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // Nothing to do, and nothing worth telling the user.
    }
  }
}

/**
 * Validates what came out of storage before it is trusted.
 *
 * <p>The contents are attacker-writable in the XSS case and merely stale in the ordinary
 * one — a shape change between releases would otherwise surface as a `TypeError` during
 * bootstrap, on the one code path a user cannot retry past.
 */
function isStoredTokens(value: unknown): value is StoredTokens {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<StoredTokens>;
  return (
    typeof candidate.accessToken === 'string' &&
    candidate.accessToken.length > 0 &&
    typeof candidate.refreshToken === 'string' &&
    candidate.refreshToken.length > 0 &&
    typeof candidate.accessTokenExpiresAt === 'number' &&
    Number.isFinite(candidate.accessTokenExpiresAt)
  );
}
