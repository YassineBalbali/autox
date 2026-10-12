import { Injectable, computed, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import {
  ListingSearchCriteria,
  ListingsPublicService,
  PublicListingSummary,
  ResultCount,
  SearchSort,
} from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';

/** How many rows a page asks for. Dense rows: twenty-four is about three screens. */
export const PAGE_SIZE = 24;

export type ResultsStatus = 'loading' | 'ready' | 'error';

/**
 * The results of one search: the rows loaded so far, whether there are more, and the count.
 *
 * <p><b>Provided in the root, so it outlives the search page.</b> That is what makes
 * "back to the results" work. Opening a listing destroys the search page; coming back
 * creates a new one, and if the results lived in the page they would be gone — the list
 * would reload from its first page, shorter than the one the visitor left, and the
 * browser would have nothing to restore their scroll position against. Kept here, the
 * rows they had loaded are rendered at once and the page is the height it was.
 *
 * <p>See {@link restore} for when that memory is used and, as importantly, when it is
 * not.
 *
 * <h2>Two requests, deliberately separate</h2>
 *
 * <p>A search asks for the first page and for the count at the same time, and neither
 * waits for the other. The count is bounded but still the slower of the two on a broad
 * search, and the rows are what the user came for — so the list renders as soon as it
 * arrives and the figure above it fills in when it is ready. A failed count is not an
 * error state: the list is still correct, and the heading simply shows no number.
 *
 * <h2>Stale answers</h2>
 *
 * <p>Every new search cancels what the previous one had in flight. Without that, changing
 * two filters quickly can show the rows of the first search under the heading of the
 * second — the slower response wins, and it is not necessarily the later one. The same
 * applies to "voir plus": a page of the old search must never be appended to the new.
 */
@Injectable({ providedIn: 'root' })
export class SearchResultsStore {
  private readonly api = inject(ListingsPublicService);
  private readonly errors = inject(ErrorMessageService);

  private readonly rows = signal<PublicListingSummary[]>([]);
  private readonly state = signal<ResultsStatus>('loading');
  private readonly failure = signal<string | null>(null);
  private readonly cursor = signal<string | null>(null);
  private readonly more = signal(false);
  private readonly moreFailure = signal<string | null>(null);
  private readonly total = signal<ResultCount | null>(null);

  readonly items = this.rows.asReadonly();
  readonly status = this.state.asReadonly();
  /** The French sentence for a failed first page. */
  readonly error = this.failure.asReadonly();
  readonly hasMore = computed(() => this.cursor() !== null);
  readonly loadingMore = this.more.asReadonly();
  /** Set when "voir plus" failed. The rows already shown stay; only the button reports. */
  readonly loadMoreError = this.moreFailure.asReadonly();
  /** `null` until the count arrives, and if it never does. */
  readonly count = this.total.asReadonly();

  private current: { criteria: ListingSearchCriteria; sort: SearchSort } | null = null;
  private pageRequest: Subscription | null = null;
  private countRequest: Subscription | null = null;

  /**
   * Shows what is already held for this exact search, if anything is; searches if not.
   *
   * <p>For one situation only: the visitor coming <em>back</em> — the browser's back
   * button, or a listing's "retour aux résultats". They expect the list as they left it,
   * with the pages they had loaded and at the place they had scrolled to.
   *
   * <p>It is not a cache, and must not be used as one. Arriving at a search any other
   * way — a link, the logo, a changed filter — calls {@link search}, which always asks
   * the API. A marketplace list that shows yesterday's rows because the criteria
   * happened to match is a list with sold cars in it.
   *
   * @return whether the held results were reused
   */
  restore(criteria: ListingSearchCriteria, sort: SearchSort): boolean {
    if (this.state() === 'ready' && this.current && keyOf(this.current) === keyOf({ criteria, sort })) {
      return true;
    }
    this.search(criteria, sort);
    return false;
  }

  /** Starts a search from the top, discarding whatever was shown. */
  search(criteria: ListingSearchCriteria, sort: SearchSort): void {
    this.cancel();
    this.current = { criteria, sort };

    this.rows.set([]);
    this.cursor.set(null);
    this.total.set(null);
    this.failure.set(null);
    this.moreFailure.set(null);
    this.more.set(false);
    this.state.set('loading');

    this.pageRequest = this.api.search({ criteria, sort, size: PAGE_SIZE }).subscribe({
      next: (page) => {
        this.rows.set(page.items ?? []);
        this.cursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.failure.set(this.errors.messageFor(error));
        this.state.set('error');
      },
    });

    this.countRequest = this.api.count({ criteria }).subscribe({
      next: (count) => this.total.set(count),
      // Deliberately silent. See the class comment: no count is not a broken page.
      error: () => this.total.set(null),
    });
  }

  /** Runs the current search again — the retry button on the error state. */
  retry(): void {
    if (this.current) {
      this.search(this.current.criteria, this.current.sort);
    }
  }

  /**
   * Appends the next page.
   *
   * <p>Keyset pagination: the cursor from the last page names the row to continue after,
   * so a listing published while the user was reading does not shift every later row
   * down by one and show the last one twice — which is what a page number does.
   */
  loadMore(): void {
    const cursor = this.cursor();
    if (!this.current || cursor === null || this.more()) {
      // The last guard matters: a double click must not request the same page twice and
      // append it twice.
      return;
    }
    const { criteria, sort } = this.current;

    this.more.set(true);
    this.moreFailure.set(null);

    this.pageRequest = this.api
      .search({ criteria, sort, cursor, size: PAGE_SIZE })
      .subscribe({
        next: (page) => {
          this.rows.update((rows) => [...rows, ...(page.items ?? [])]);
          this.cursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
          this.more.set(false);
        },
        error: (error: unknown) => {
          // The cursor is kept, so pressing the button again retries the same page.
          this.moreFailure.set(this.errors.messageFor(error));
          this.more.set(false);
        },
      });
  }

  /**
   * Abandons anything in flight. Called when the search page is destroyed: the store
   * outlives the page, so a request the page started would otherwise complete into a
   * screen nobody is looking at — and leave "voir plus" stuck in its loading state if
   * the visitor returned before it did.
   */
  release(): void {
    this.cancel();
    if (this.state() === 'loading') {
      // A first page that never arrived is not something to come back to.
      this.current = null;
    }
    this.more.set(false);
  }

  private cancel(): void {
    this.pageRequest?.unsubscribe();
    this.countRequest?.unsubscribe();
    this.pageRequest = null;
    this.countRequest = null;
  }
}

/** Two searches are the same search when their criteria and sort serialise identically. */
function keyOf(search: { criteria: ListingSearchCriteria; sort: SearchSort }): string {
  return JSON.stringify([search.sort, Object.entries(search.criteria).sort(([a], [b]) => a.localeCompare(b))]);
}
