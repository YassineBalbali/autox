import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { PARAM } from './search-query';

/** Where the search results live. */
export const SEARCH_PATH = '/recherche';

const SEARCH_KEYS: ReadonlySet<string> = new Set(Object.values(PARAM));

/**
 * Sends an old search link on to the results page.
 *
 * <p>Until the home page existed, the results were at `/` and a search was
 * `/?marque=peugeot&prix_max=15000`. Those links are out there — shared, bookmarked, in
 * the browser history — and the parameter names are a contract precisely so that they
 * keep working. So an address at `/` that carries any search parameter is a search, and
 * goes to `/recherche` with its query string unchanged; a bare `/` is the home page.
 *
 * <p>Replaces the history entry, so the back button does not land on a redirect.
 */
export const legacySearchRedirect: CanActivateFn = (route) => {
  const isSearch = route.queryParamMap.keys.some((key) => SEARCH_KEYS.has(key));
  if (!isSearch) {
    return true;
  }
  const router = inject(Router);
  return router.createUrlTree([SEARCH_PATH], { queryParams: route.queryParams });
};
