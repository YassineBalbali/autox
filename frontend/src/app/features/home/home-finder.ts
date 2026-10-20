import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, map, merge, of, skip, startWith, switchMap, take } from 'rxjs';

import { Make, Model, ResultCount } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { formatIntegerFr } from '../../shared/format/number';
import { Icon } from '../../shared/ui/icon';
import { SEARCH_PATH } from '../search/legacy-search-redirect';
import { EMPTY_QUERY, SearchQuery, toQueryParams } from '../search/search-query';
import { SearchResolver } from '../search/search-resolver';
import { PRICE_MAX_OPTIONS } from './home-shortcuts';

/** The form as typed — strings, because that is what form controls hold. */
interface Draft {
  readonly make: string;
  readonly model: string;
  readonly priceMax: string;
  readonly postalCode: string;
}

const EMPTY_DRAFT: Draft = { make: '', model: '', priceMax: '', postalCode: '' };

/**
 * The home page's search card: make, model, budget and postal code, and a button that
 * says how many listings that is.
 *
 * <p>The form is a draft, not a search: nothing navigates until it is submitted, and then
 * it goes to `/recherche` with the same query string a hand-typed link would have. The
 * count on the button follows the draft, so the visitor sees "Rechercher (9)" before
 * committing — and a form that would find nothing says so before the click.
 */
@Component({
  selector: 'app-home-finder',
  imports: [RouterLink, Icon],
  templateUrl: './home-finder.html',
  styleUrl: './home-finder.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeFinder {
  private readonly reference = inject(ReferenceStore);
  private readonly resolver = inject(SearchResolver);
  private readonly router = inject(Router);

  protected readonly searchPath = SEARCH_PATH;
  protected readonly priceOptions = PRICE_MAX_OPTIONS;

  protected readonly draft = signal<Draft>(EMPTY_DRAFT);

  /** Makes with a slug — the only ones a link can name. Alphabetical, as a list to scan. */
  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter(hasSlug).sort(byName)),
      catchError(() => of([] as Make[])),
    ),
    { initialValue: [] as Make[] },
  );

  private readonly chosenMake = computed(
    () => this.makes().find((make) => make.slug === this.draft().make) ?? null,
  );

  /** The chosen make's models, fetched when the make changes; empty until one is chosen. */
  protected readonly models = toSignal(
    toObservable(this.chosenMake).pipe(
      switchMap((make) =>
        make?.id
          ? this.reference.models(make.id).pipe(
              map((models) => models.filter(hasSlug)),
              catchError(() => of([] as Model[])),
            )
          : of([] as Model[]),
      ),
    ),
    { initialValue: [] as Model[] },
  );

  /** The search the form describes right now. */
  private readonly query = computed<SearchQuery>(() => {
    const draft = this.draft();
    const make = draft.make || null;
    return {
      ...EMPTY_QUERY,
      make,
      model: make ? draft.model || null : null,
      priceMax: draft.priceMax ? Number(draft.priceMax) : null,
      location: draft.postalCode || null,
    };
  });

  protected readonly queryParams = computed(() => toQueryParams(this.query()));

  private readonly query$ = toObservable(this.query);

  /**
   * The count on the button, following the form. The first is asked at once — the page
   * has only just arrived, and nothing is being typed. Every later one is debounced, so
   * typing a postal code asks once when the typing stops rather than once per key.
   * switchMap, so an answer to an older form never overwrites a newer one.
   */
  private readonly count = toSignal(
    merge(this.query$.pipe(take(1)), this.query$.pipe(skip(1), debounceTime(250))).pipe(
      switchMap((query) => this.resolver.count(query).pipe(startWith(undefined))),
    ),
    { initialValue: undefined as ResultCount | null | undefined },
  );

  protected readonly searchLabel = computed(() => {
    const count = this.count();
    if (!count || typeof count.count !== 'number') {
      return 'Rechercher';
    }
    return `Rechercher (${count.capped ? '+' : ''}${formatIntegerFr(count.count)})`;
  });

  protected formatPrice(euros: number): string {
    return `${formatIntegerFr(euros)} €`;
  }

  protected setField(field: keyof Draft, event: Event): void {
    const control = event.target as HTMLInputElement | HTMLSelectElement;
    this.draft.update((draft) => ({
      ...draft,
      [field]: field === 'postalCode' ? control.value.replace(/\D/g, '').slice(0, 5) : control.value,
      // A model belongs to its make: changing the make clears it.
      ...(field === 'make' ? { model: '' } : {}),
    }));
    if (field === 'postalCode') {
      // Keep the field showing what was kept, not what was typed.
      control.value = this.draft().postalCode;
    }
  }

  protected submit(event: Event): void {
    event.preventDefault();
    void this.router.navigate([SEARCH_PATH], { queryParams: this.queryParams() });
  }
}

function hasSlug<T extends { id?: string; slug?: string }>(entry: T): boolean {
  return !!entry.id && !!entry.slug;
}

function byName(a: Make, b: Make): number {
  return (a.name ?? '').localeCompare(b.name ?? '', 'fr');
}
