import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { ResultsView } from './view-preference';

/**
 * Placeholders in the shape of the results, while the first page loads — about one
 * screenful, in the view the visitor chose.
 *
 * <p>Same columns, sizes and padding as `app-listing-row` and `app-listing-card`, so
 * nothing moves when the results land. Flat grey blocks, no shimmer: an animated gradient
 * across a screenful of results is the most decorative thing a page can do, and it says
 * nothing a still block does not.
 */
@Component({
  selector: 'app-results-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (view() === 'grille') {
      <ul class="grid" aria-busy="true" aria-label="Chargement des annonces" i18n-aria-label="@@searchLoading">
        @for (item of placeholders; track item) {
          <li class="card" aria-hidden="true">
            <div class="photo"></div>
            <div class="lines card__lines">
              <span class="line line--title"></span>
              <span class="line"></span>
              <span class="line line--price"></span>
            </div>
          </li>
        }
      </ul>
    } @else {
      <ul class="list" aria-busy="true" aria-label="Chargement des annonces" i18n-aria-label="@@searchLoading">
        @for (item of placeholders; track item) {
          <li class="item" aria-hidden="true">
            <div class="photo item__photo"></div>
            <div class="lines">
              <span class="line line--title"></span>
              <span class="line"></span>
              <span class="line line--short"></span>
            </div>
            <span class="line line--price"></span>
          </li>
        }
      </ul>
    }
  `,
  styleUrl: './results-skeleton.scss',
})
export class ResultsSkeleton {
  readonly view = input.required<ResultsView>();

  protected readonly placeholders = [0, 1, 2, 3, 4, 5];
}
