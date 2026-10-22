import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './core/auth/auth.guard';
import { UserRole } from './core/api';
import { legacySearchRedirect } from './features/search/legacy-search-redirect';

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
 * <p>The home page is a way in to the search — a short form, the visitor's recent
 * searches, and shortcuts by make, budget and kind of car — and the results are at
 * `/recherche`. Every search link from before the split started with `/?`; those still
 * work, forwarded by `legacySearchRedirect`.
 */
export const routes: Routes = [
  // ---- the shop window ----------------------------------------------------

  {
    // A bare `/` is the home page; `/?marque=…` is an old search link, and the guard
    // forwards it to the results.
    path: '',
    pathMatch: 'full',
    canActivate: [legacySearchRedirect],
    title: 'AutoX · Voitures d’occasion',
    loadComponent: () => import('./features/home/home-page').then((m) => m.HomePage),
  },
  {
    // Filters, sort order and nothing else live in this route's query string — see
    // features/search/search-query.ts for the parameter names, which are a contract once
    // a link has been shared.
    path: 'recherche',
    title: 'Voitures d’occasion · AutoX',
    loadComponent: () => import('./features/search/search-page').then((m) => m.SearchPage),
  },

  {
    // One listing. Public, like the search. The id reaches the component as an input
    // through `withComponentInputBinding()`. No static title: the page sets its own
    // once it knows which car it is.
    path: 'annonces/:id',
    loadComponent: () => import('./features/listing/listing-page').then((m) => m.ListingPage),
  },

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

  // ---- selling ------------------------------------------------------------
  //
  // Create and edit are one component: the editor takes the listing's id from the route
  // when there is one, and the step from `?etape=`. Saving the draft on the create route
  // navigates to the edit route, so a reload continues with the draft.

  {
    path: 'vendre',
    canActivate: [authGuard],
    title: 'Déposer une annonce · AutoX',
    loadComponent: () =>
      import('./features/seller/listing-editor-page').then((m) => m.ListingEditorPage),
  },
  {
    path: 'mes-annonces',
    canActivate: [authGuard],
    title: 'Mes annonces · AutoX',
    loadComponent: () =>
      import('./features/seller/my-listings-page').then((m) => m.MyListingsPage),
  },
  {
    // No static title: the editor sets its own once it knows which listing it is.
    path: 'mes-annonces/:id/modifier',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/seller/listing-editor-page').then((m) => m.ListingEditorPage),
  },

  // ---- messages -----------------------------------------------------------
  //
  // The list is the parent and the open conversation a child, so going from one
  // conversation to the next swaps only the right-hand side and the list keeps its place.

  {
    path: 'messages',
    canActivate: [authGuard],
    title: 'Messages · AutoX',
    loadComponent: () => import('./features/messages/messages-page').then((m) => m.MessagesPage),
    children: [
      {
        path: '',
        loadComponent: () => import('./features/messages/no-conversation').then((m) => m.NoConversation),
      },
      {
        // The id reaches the component as an input through `withComponentInputBinding()`.
        path: ':id',
        loadComponent: () =>
          import('./features/messages/conversation-view').then((m) => m.ConversationView),
      },
    ],
  },

  // ---- administration -----------------------------------------------------
  //
  // One screen today. `roleGuard` also turns away a visitor with no session, so it is
  // the only guard needed — and it is a courtesy: the API refuses every call these
  // screens make to anyone who is not an administrator.

  { path: 'admin', pathMatch: 'full', redirectTo: 'admin/moderation' },
  {
    // The list and the page number are in the query string: `?vue=`, `?page=`.
    path: 'admin/moderation',
    canActivate: [roleGuard(UserRole.ADMIN)],
    title: 'Modération · AutoX',
    loadComponent: () => import('./features/admin/moderation-page').then((m) => m.ModerationPage),
  },
  {
    path: 'admin/moderation/messages',
    canActivate: [roleGuard(UserRole.ADMIN)],
    title: 'Messages signalés · AutoX',
    loadComponent: () =>
      import('./features/admin/reported-messages-page').then((m) => m.ReportedMessagesPage),
  },

  // ---- internal -----------------------------------------------------------

  {
    // The design reference: every token and shared component, rendered. Public and
    // harmless — it shows nothing but the look of the site — and lazy, so it costs a
    // visitor nothing. Not linked from anywhere.
    path: 'guide-de-style',
    title: 'Guide de style · AutoX',
    loadComponent: () =>
      import('./features/styleguide/styleguide-page').then((m) => m.StyleguidePage),
  },

  // ---- outcomes -----------------------------------------------------------

  {
    path: 'acces-refuse',
    title: 'Accès refusé · AutoX',
    loadComponent: () => import('./features/errors/forbidden-page').then((m) => m.ForbiddenPage),
  },

  // An address that matches nothing gets a page that says so. Not a redirect to the home
  // page, which was the previous behaviour: that showed the visitor something other than
  // what they asked for with nothing explaining why, and made a mistyped link look as
  // though it worked.
  {
    path: '**',
    title: 'Page introuvable · AutoX',
    loadComponent: () => import('./features/errors/not-found-page').then((m) => m.NotFoundPage),
  },
];
