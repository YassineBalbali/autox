import { Routes } from '@angular/router';

/**
 * Route table.
 *
 * <p>Empty of screens by design — this task built the plumbing only. What is here is the
 * shape the screens will slot into, and a worked example of each guard so the next person
 * does not have to infer the usage from the guard's source.
 *
 * <pre>
 * // public
 * { path: '', loadComponent: () => import('./features/search/search-page').then(m => m.SearchPage) },
 * { path: 'annonces/:id', loadComponent: ... },
 * { path: 'connexion', loadComponent: ... },
 *
 * // any signed-in user
 * {
 *   path: 'mon-compte',
 *   canActivate: [authGuard],
 *   loadComponent: ...,
 * },
 *
 * // sellers only — a private individual or a dealer, not an admin browsing
 * {
 *   path: 'mes-annonces',
 *   canActivate: [roleGuard(UserRole.PARTICULIER, UserRole.PROFESSIONNEL)],
 *   loadComponent: ...,
 * },
 *
 * // dealer features: subscription tiers, bulk upload
 * {
 *   path: 'pro',
 *   canActivate: [roleGuard(UserRole.PROFESSIONNEL)],
 *   loadChildren: ...,
 * },
 *
 * // moderation, when it ships with the admin UI
 * {
 *   path: 'admin',
 *   canActivate: [roleGuard(UserRole.ADMIN)],
 *   loadChildren: ...,
 * },
 * </pre>
 *
 * <p>Every screen is lazy via `loadComponent`/`loadChildren`. On a marketplace most
 * visitors see one search page and one listing, and shipping the seller dashboard and the
 * admin tools in that first download is bytes a phone on 4G pays for and never uses.
 */
export const routes: Routes = [];
