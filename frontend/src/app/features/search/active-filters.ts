import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { switchMap } from 'rxjs';

import { formatIntegerFr } from '../../shared/format/number';
import { BODY_LABELS, CONDITION_LABELS, FUEL_LABELS, GEARBOX_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { SearchQuery } from './search-query';
import { SearchResolver } from './search-resolver';

/** One filter currently applied, in words, with what removing it means. */
export interface AppliedFilter {
  /** Stable within one query — the `track` key. */
  readonly key: string;
  readonly label: string;
  /** The fields of the query to replace in order to remove this filter, and only this one. */
  readonly remove: Partial<SearchQuery>;
}

/** Display names for a make and model slug, when the catalogue could supply them. */
export interface VehicleNames {
  readonly make?: string | null;
  readonly model?: string | null;
}

/**
 * The filters in a query, as a list of things a person can read and remove one at a time.
 *
 * <p>Pure, so the wording and — more importantly — the <em>removal</em> can be tested
 * without a component. Removal is the part that is easy to get subtly wrong: taking away
 * "Diesel" must leave "Essence" selected, taking away the make must take the model with
 * it, and taking away a price range must clear both ends.
 *
 * <p>A range is one filter, not two. "5 000 – 15 000 €" is what the user set and what they
 * expect to remove; two chips for one decision would make the row twice as long and half
 * as clear.
 */
export function describeFilters(query: SearchQuery, names: VehicleNames = {}): AppliedFilter[] {
  const filters: AppliedFilter[] = [];

  if (query.text) {
    filters.push({ key: 'text', label: `« ${query.text} »`, remove: { text: null } });
  }
  if (query.make) {
    filters.push({
      key: 'make',
      label: names.make ?? query.make,
      // The model goes with it: a model with no make is a filter nothing can display.
      remove: { make: null, model: null },
    });
  }
  if (query.make && query.model) {
    filters.push({ key: 'model', label: names.model ?? query.model, remove: { model: null } });
  }

  const year = range(query.yearMin, query.yearMax, String, '');
  if (year) {
    filters.push({ key: 'year', label: year, remove: { yearMin: null, yearMax: null } });
  }
  const price = range(query.priceMin, query.priceMax, formatIntegerFr, ' €');
  if (price) {
    filters.push({ key: 'price', label: price, remove: { priceMin: null, priceMax: null } });
  }
  const mileage = range(query.mileageMin, query.mileageMax, formatIntegerFr, ' km');
  if (mileage) {
    filters.push({ key: 'mileage', label: mileage, remove: { mileageMin: null, mileageMax: null } });
  }

  // One chip per chosen value: each was a separate click, and each should be a separate
  // thing to undo.
  for (const value of query.fuel) {
    filters.push({
      key: `fuel:${value}`,
      label: labelOf(FUEL_LABELS, value),
      remove: { fuel: query.fuel.filter((entry) => entry !== value) },
    });
  }
  for (const value of query.gearbox) {
    filters.push({
      key: `gearbox:${value}`,
      label: `Boîte ${labelOf(GEARBOX_LABELS, value).toLowerCase()}`,
      remove: { gearbox: query.gearbox.filter((entry) => entry !== value) },
    });
  }
  for (const value of query.body) {
    filters.push({
      key: `body:${value}`,
      label: labelOf(BODY_LABELS, value),
      remove: { body: query.body.filter((entry) => entry !== value) },
    });
  }
  for (const value of query.condition) {
    filters.push({
      key: `condition:${value}`,
      label: labelOf(CONDITION_LABELS, value),
      remove: { condition: query.condition.filter((entry) => entry !== value) },
    });
  }

  if (query.location) {
    filters.push({ key: 'location', label: query.location, remove: { location: null } });
  }
  if (query.goodDeals) {
    filters.push({ key: 'goodDeals', label: 'Bonnes affaires', remove: { goodDeals: false } });
  }
  return filters;
}

/** "2018 – 2022", "À partir de 2018", "Jusqu’à 2022" — or nothing when neither end is set. */
function range(
  min: number | null,
  max: number | null,
  format: (value: number) => string,
  unit: string,
): string | null {
  if (min !== null && max !== null) {
    return `${format(min)} – ${format(max)}${unit}`;
  }
  if (min !== null) {
    return `À partir de ${format(min)}${unit}`;
  }
  if (max !== null) {
    return `Jusqu’à ${format(max)}${unit}`;
  }
  return null;
}

/**
 * The row of applied filters under the search bar: each one removable, and all of them at
 * once.
 *
 * <p>It is the answer to "why am I seeing these results?" — the sidebar shows what could
 * be filtered, this shows what is. Each chip is a button whose accessible name says what
 * pressing it does, because a bare "Diesel ×" is a word and a symbol to a screen reader.
 *
 * <p>Like the sidebar it holds no state: it describes the query it is given and reports a
 * removal upward as the fields to change.
 */
@Component({
  selector: 'app-active-filters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (filters().length > 0) {
      <div class="applied">
        <ul class="applied__list" aria-label="Filtres appliqués" i18n-aria-label="@@appliedLabel">
          @for (filter of filters(); track filter.key) {
            <li>
              <button
                class="applied__chip"
                type="button"
                [attr.aria-label]="'Retirer le filtre : ' + filter.label"
                (click)="changed.emit(filter.remove)"
              >
                {{ filter.label }}
                <span class="applied__x" aria-hidden="true">×</span>
              </button>
            </li>
          }
        </ul>
        <button class="applied__clear" type="button" (click)="cleared.emit()" i18n="@@appliedClear">
          Tout effacer
        </button>
      </div>
    }
  `,
  styles: `
    .applied {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--s-2) var(--s-3);
    }

    .applied__list {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-1);
      margin: 0;
      padding: 0;
      list-style: none;
    }

    // The active-filter state from the style pass — accent outline, tint and text — so a
    // chip here and a pressed chip in the sidebar are visibly the same thing.
    .applied__chip {
      display: inline-flex;
      align-items: center;
      gap: var(--s-1);
      height: calc(var(--h-control) - 8px);
      padding: 0 var(--s-2);
      border: 1px solid var(--c-accent);
      border-radius: var(--r-sm);
      background: var(--c-accent-soft);
      color: var(--c-accent);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      white-space: nowrap;

      &:hover {
        border-color: var(--c-accent-strong);
        color: var(--c-accent-strong);
      }
    }

    .applied__x {
      font-size: var(--t-md);
      line-height: 1;
    }

    .applied__clear {
      padding: 0;
      border: 0;
      background: none;
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
      text-decoration: underline;
      text-underline-offset: 2px;

      &:hover {
        color: var(--c-ink);
      }
    }
  `,
})
export class ActiveFilters {
  private readonly resolver = inject(SearchResolver);

  readonly query = input.required<SearchQuery>();

  readonly changed = output<Partial<SearchQuery>>();
  readonly cleared = output<void>();

  /** The make and model as the catalogue names them; see {@link SearchResolver.names}. */
  private readonly names = toSignal(
    toObservable(computed(() => `${this.query().make ?? ''}\n${this.query().model ?? ''}`)).pipe(
      switchMap(() => this.resolver.names(this.query())),
    ),
    { initialValue: {} as VehicleNames },
  );

  protected readonly filters = computed(() => describeFilters(this.query(), this.names()));
}
