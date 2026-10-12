import { Injectable, signal } from '@angular/core';

/** How the results are laid out. The values are the words on the toggle. */
export type ResultsView = 'liste' | 'grille';

export const DEFAULT_VIEW: ResultsView = 'liste';

const KEY = 'autox.search.view';

/**
 * Whether this visitor reads the results as a list or as a grid.
 *
 * <p><b>A preference, not search state — so it is not in the URL.</b> The filters describe
 * what is being looked for and belong in a link; the layout describes how one person likes
 * to look at it. Sending somebody a search should not also send them your choice of view,
 * and switching view should not add an entry to the browser history.
 *
 * <p>Remembered in `localStorage`, which makes it per browser rather than per account:
 * there is no preferences endpoint, and this is a setting most people choose before they
 * have an account at all. If one is added later this is the single place that learns to
 * read and write it.
 *
 * <p>Storage is optional throughout. Safari in private mode and any browser with site data
 * blocked throw on access; the toggle then works for the visit and is simply not
 * remembered, which is better than a results page that will not render.
 */
@Injectable({ providedIn: 'root' })
export class ViewPreference {
  private readonly current = signal<ResultsView>(read());

  readonly view = this.current.asReadonly();

  set(view: ResultsView): void {
    this.current.set(view);
    try {
      localStorage.setItem(KEY, view);
    } catch {
      // Not remembered. Nothing to tell the user: the view they asked for is on screen.
    }
  }
}

function read(): ResultsView {
  try {
    const stored = localStorage.getItem(KEY);
    // Validated, not trusted: storage is writable by anything on the origin, and a value
    // from an older release should fall back rather than select a view that is not there.
    return stored === 'liste' || stored === 'grille' ? stored : DEFAULT_VIEW;
  } catch {
    return DEFAULT_VIEW;
  }
}
