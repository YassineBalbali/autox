import { Injectable, signal } from '@angular/core';

/**
 * The last search the visitor looked at, so a listing can lead back to it.
 *
 * <p>For the case where "back to the results" cannot simply step back in history: the
 * listing was opened in a new tab, or reached by a link, and there is no results page
 * behind it. The link then goes to the last search made in this tab if there was one, and
 * to the unfiltered list otherwise.
 *
 * <p>In memory only. It is a convenience for one visit; the search itself is in the URL,
 * which is the thing worth keeping.
 */
@Injectable({ providedIn: 'root' })
export class SearchMemory {
  private readonly url = signal<string | null>(null);

  readonly lastUrl = this.url.asReadonly();

  remember(url: string): void {
    if (isSearchUrl(url)) {
      this.url.set(url);
    }
  }
}

/**
 * Whether a URL is the search page: the root path, with or without a query string.
 *
 * <p>Deliberately strict. This decides whether a listing's back link may call
 * `history.back()`, and stepping back from a listing reached some other way would take
 * the visitor somewhere they did not ask to go — possibly off the site.
 */
export function isSearchUrl(url: string | null | undefined): boolean {
  return typeof url === 'string' && (url === '/' || url.startsWith('/?'));
}
