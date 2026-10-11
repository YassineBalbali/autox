import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';

import { provideAutoxApi } from './core/api/api.providers';
import { authInterceptor } from './core/auth/auth.interceptor';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),

    provideRouter(
      routes,
      // Route params and query params arrive as component inputs, so a screen does not
      // need to inject ActivatedRoute to read its own id.
      withComponentInputBinding(),
      // Back from a listing returns to where the result list was, rather than the top.
      // On a marketplace that is the difference between usable and infuriating.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),

    // One interceptor, and the order matters only when there are more: auth has to run
    // close to the transport so it sees the real status code.
    provideHttpClient(withInterceptors([authInterceptor])),

    provideAutoxApi(),
  ],
};
