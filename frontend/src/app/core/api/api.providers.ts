import { EnvironmentProviders, makeEnvironmentProviders, provideAppInitializer, inject } from '@angular/core';

import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/auth.service';
import { TOKEN_STORE, LocalStorageTokenStore } from '../auth/token-store';
import { BASE_PATH } from './generated';

/**
 * Everything the API layer needs, as one provider for `app.config.ts`.
 *
 * <p>The generated services read `BASE_PATH` through DI, which is what lets the same
 * generated code point at `http://localhost:8081` in development and at the same origin in
 * production without a build-time string substitution inside generated output.
 */
export function provideAutoxApi(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: BASE_PATH, useValue: environment.apiBaseUrl },
    { provide: TOKEN_STORE, useClass: LocalStorageTokenStore },

    // Rehydrate the session before the first route is resolved. Without this, a reload on
    // a guarded page would briefly look signed out and bounce the user to sign-in — the
    // tokens are in storage, they just have not been read yet.
    provideAppInitializer(() => {
      inject(AuthService).restore();
    }),
  ]);
}
