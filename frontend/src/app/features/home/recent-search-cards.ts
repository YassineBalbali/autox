import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { combineLatest, map, of, startWith, switchMap } from 'rxjs';

import { Icon } from '../../shared/ui/icon';
import { describeFilters } from '../search/active-filters';
import { SEARCH_PATH } from '../search/legacy-search-redirect';
import { RecentSearches, idOf } from '../search/recent-searches';
import { toQueryParams } from '../search/search-query';
import { SearchResolver } from '../search/search-resolver';

/**
 * The visitor's recent searches as cards: their criteria as chips, a link back to the
 * results, and a way to forget one.
 *
 * <p>The chips are the same labels the results page puts on its active filters, so a
 * search reads the same in both places. The make and model come back from the catalogue
 * with their real names — "Citroën", not "citroen" — and show their slugs until then.
 */
@Component({
  selector: 'app-recent-search-cards',
  imports: [RouterLink, Icon],
  templateUrl: './recent-search-cards.html',
  styleUrl: './recent-search-cards.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RecentSearchCards {
  private readonly resolver = inject(SearchResolver);
  protected readonly recent = inject(RecentSearches);

  protected readonly searchPath = SEARCH_PATH;

  protected readonly recentCards = toSignal(
    toObservable(this.recent.entries).pipe(
      switchMap((entries) =>
        entries.length === 0
          ? of([])
          : combineLatest(
              entries.map((query) =>
                this.resolver.names(query).pipe(
                  startWith({}),
                  map((names) => ({
                    id: idOf(query),
                    query,
                    params: toQueryParams(query),
                    chips: describeFilters(query, names).map((filter) => filter.label),
                  })),
                ),
              ),
            ),
      ),
    ),
    { initialValue: [] },
  );
}
