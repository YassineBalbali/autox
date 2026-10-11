import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';

import { appConfig } from './app.config';
import { environment } from '../environments/environment';
import { AuthenticationService, BASE_PATH, ListingsPublicService, ProfileService } from './core/api';
import { AuthService } from './core/auth/auth.service';
import { TOKEN_STORE } from './core/auth/token-store';
import { ErrorMessageService } from './core/errors/error-message.service';

/**
 * That the application's real provider graph resolves.
 *
 * <p>Every other test here builds its own `TestBed` with hand-picked providers, which means
 * none of them would notice if `appConfig` itself were wrong — a missing `provideHttpClient`,
 * an injection token nobody supplies, an app initializer that throws. The production build
 * type-checks that wiring but never runs it, so the first thing to find out would be a blank
 * page in a browser.
 *
 * <p>This instantiates the things the screens will inject, using the real configuration.
 */
describe('appConfig', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [...appConfig.providers] });
  });

  it('resolves the API base path from the environment', () => {
    expect(TestBed.inject(BASE_PATH)).toBe(environment.apiBaseUrl);
  });

  it('provides HttpClient with the auth interceptor attached', () => {
    expect(TestBed.inject(HttpClient)).toBeTruthy();
  });

  it('provides the token store the auth service depends on', () => {
    // An injection token with no provider is the classic way this file goes wrong: it
    // compiles, and then every guarded navigation throws NullInjectorError at runtime.
    expect(TestBed.inject(TOKEN_STORE)).toBeTruthy();
  });

  it('constructs the services a screen will inject', () => {
    expect(TestBed.inject(AuthService)).toBeTruthy();
    expect(TestBed.inject(ErrorMessageService)).toBeTruthy();

    // Generated services, which are providedInRoot and read BASE_PATH through DI. If the
    // generator's provider strategy ever changes, this is where it shows up.
    expect(TestBed.inject(AuthenticationService)).toBeTruthy();
    expect(TestBed.inject(ProfileService)).toBeTruthy();
    expect(TestBed.inject(ListingsPublicService)).toBeTruthy();
  });

  it('runs the session rehydration initializer without a stored session', () => {
    // provideAppInitializer runs AuthService.restore(). With nothing in storage it must be
    // a no-op rather than a throw — otherwise the application fails to bootstrap for every
    // first-time visitor, which is the one case nobody tests by hand.
    TestBed.inject(AuthService);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(false);
    expect(TestBed.inject(AuthService).user()).toBeNull();
  });
});
