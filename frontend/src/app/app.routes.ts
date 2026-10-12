import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './core/auth/auth.guard';
import { UserRole } from './core/api';

/**
 * Route table.
 *
 * <p>Every screen is lazy via `loadComponent`. On a marketplace most visitors see one
 * search page and one listing; shipping the account screens in that first download is bytes
 * a phone on 4G pays for and never uses.
 *
 * <p>Paths are French, because they are part of the interface the user reads. The two the
 * plumbing needs by name are in `auth.routes.ts`, and one of them — the password reset
 * page — has to match `autox.mail.password-reset-url` on the backend exactly, since that is
 * what the emailed link points at.
 *
 * <p>No listing or search screens yet, so there is no `''` route: the redirect target is
 * the account, which is where both guards lead and the only place there is to be.
 */
export const routes: Routes = [
  // ---- public -------------------------------------------------------------

  {
    path: 'connexion',
    title: 'Se connecter · AutoX',
    loadComponent: () => import('./features/auth/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'inscription',
    title: 'Créer un compte · AutoX',
    loadComponent: () => import('./features/auth/register-page').then((m) => m.RegisterPage),
  },
  {
    path: 'mot-de-passe-oublie',
    title: 'Mot de passe oublié · AutoX',
    loadComponent: () =>
      import('./features/auth/forgot-password-page').then((m) => m.ForgotPasswordPage),
  },
  {
    // Must stay in step with `autox.mail.password-reset-url`. The token arrives as
    // `?token=…` and reaches the component as an input through
    // `withComponentInputBinding()`.
    path: 'reinitialiser-mot-de-passe',
    title: 'Nouveau mot de passe · AutoX',
    loadComponent: () =>
      import('./features/auth/reset-password-page').then((m) => m.ResetPasswordPage),
  },

  // ---- the account section -----------------------------------------------
  //
  // A routed layout with the screens as children. The sidebar is rendered once and stays
  // put while the content changes, `authGuard` is declared once instead of four times,
  // and the profile is loaded once for the whole section rather than per screen.

  {
    path: 'mon-compte',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/account/account-layout').then((m) => m.AccountLayout),
    children: [
      {
        path: '',
        title: 'Mon compte · AutoX',
        loadComponent: () =>
          import('./features/account/profile-page').then((m) => m.ProfilePage),
      },
      {
        path: 'mot-de-passe',
        title: 'Changer mon mot de passe · AutoX',
        loadComponent: () =>
          import('./features/account/change-password-page').then((m) => m.ChangePasswordPage),
      },
      {
        path: 'sessions',
        title: 'Appareils connectés · AutoX',
        loadComponent: () =>
          import('./features/account/sessions-page').then((m) => m.SessionsPage),
      },
      {
        // ADMIN is included because an administrator has to be able to see a dealer's own
        // screens to support them, and excluding the role here would be a guard that says
        // "no" to the one account that is allowed everything.
        path: 'entreprise',
        canActivate: [roleGuard(UserRole.PROFESSIONNEL, UserRole.ADMIN)],
        title: 'Informations de l’entreprise · AutoX',
        loadComponent: () =>
          import('./features/account/company-page').then((m) => m.CompanyPage),
      },
    ],
  },

  // ---- outcomes -----------------------------------------------------------

  {
    path: 'acces-refuse',
    title: 'Accès refusé · AutoX',
    loadComponent: () => import('./features/errors/forbidden-page').then((m) => m.ForbiddenPage),
  },

  {
    path: '',
    pathMatch: 'full',
    redirectTo: '/mon-compte',
  },

  // A wildcard rather than nothing: an unknown path with no route renders an empty shell,
  // which looks like a broken application rather than a wrong address. Sent to the account,
  // whose guard then sends an anonymous visitor to sign in.
  {
    path: '**',
    redirectTo: '/mon-compte',
  },
];
