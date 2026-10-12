import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { Make, Model } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { SearchQuery } from './search-query';

/** What the bar is holding before "Rechercher" is pressed. Strings: these are form fields. */
interface Draft {
  readonly text: string;
  readonly make: string;
  readonly model: string;
  readonly priceMax: string;
  readonly yearMin: string;
}

/** How far back the year list goes. Older than this is rare enough to type in the sidebar. */
const OLDEST_YEAR = 2000;

/**
 * The search bar: what you are looking for, the four filters most people start with, and
 * a button.
 *
 * <p><b>Unlike the sidebar, this is a form with a draft.</b> The sidebar applies each
 * change at once, which is right for refining a list you are already looking at. The bar
 * is where a search is composed — a few words, a make, a budget — and searching on every
 * keystroke of "volkswagen golf" would run fourteen searches and write fourteen history
 * entries on the way to one. So the fields here change nothing until "Rechercher" is
 * pressed, or Enter.
 *
 * <p>The draft is still subordinate to the URL: whenever the query changes from outside —
 * the back button, a chip removed, the sidebar — the draft is reset to match it. What is
 * shown in the bar is therefore always either the current search or an edit of it that
 * has not been submitted yet, never a stale third thing.
 *
 * <p>Make and model live here and not in the sidebar. They are the two filters almost
 * every search starts with, so they belong in the composing step, and showing the same
 * dropdown twice on one screen would only raise the question of which one wins.
 */
@Component({
  selector: 'app-search-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-bar.html',
  styleUrl: './search-bar.scss',
})
export class SearchBar {
  private readonly reference = inject(ReferenceStore);

  readonly query = input.required<SearchQuery>();

  /** The fields of the query to replace. Emitted on submit only. */
  readonly submitted = output<Partial<SearchQuery>>();

  protected readonly draft = signal<Draft>({ text: '', make: '', model: '', priceMax: '', yearMin: '' });

  /** `null` while loading; empty when the catalogue is empty or could not be read. */
  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter(hasSlug)),
      catchError(() => of([] as SluggedMake[])),
    ),
    { initialValue: null },
  );

  /** See `SearchFilters` history: with no catalogue, the make is typed rather than chosen. */
  protected readonly makeIsFreeText = computed(() => this.makes()?.length === 0);

  /** The catalogue entry for the make in the draft — not the one in the URL. */
  protected readonly draftMake = computed(
    () => this.makes()?.find((make) => make.slug === this.draft().make) ?? null,
  );

  /**
   * The models of the make currently chosen in the bar.
   *
   * <p>Keyed on the draft, so picking "Peugeot" fills the model list before anything has
   * been searched for — otherwise the user would have to search by make, wait, and only
   * then be offered a model.
   */
  protected readonly models = toSignal(
    toObservable(this.draftMake).pipe(
      switchMap((make) =>
        make
          ? this.reference.models(make.id).pipe(
              map((models) => models.filter(hasSlug)),
              catchError(() => of([] as SluggedModel[])),
              startWith([] as SluggedModel[]),
            )
          : of([] as SluggedModel[]),
      ),
    ),
    { initialValue: [] as SluggedModel[] },
  );

  protected readonly years = yearsDownTo(OLDEST_YEAR);

  constructor() {
    // The URL wins. Any change to the query — back button, a removed chip, the sidebar —
    // overwrites whatever was being typed, so the bar never shows a search that is neither
    // the current one nor an edit of it.
    effect(() => {
      const query = this.query();
      this.draft.set({
        text: query.text ?? '',
        make: query.make ?? '',
        model: query.model ?? '',
        priceMax: query.priceMax === null ? '' : String(query.priceMax),
        yearMin: query.yearMin === null ? '' : String(query.yearMin),
      });
    });
  }

  protected set(field: keyof Draft, event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLSelectElement).value;
    this.draft.update((draft) => ({
      ...draft,
      [field]: value,
      // A model belongs to one make: changing the make empties it.
      ...(field === 'make' ? { model: '' } : {}),
    }));
  }

  protected submit(event: Event): void {
    event.preventDefault();
    const draft = this.draft();
    const make = blankToNull(draft.make);

    this.submitted.emit({
      text: blankToNull(draft.text),
      make,
      model: make ? blankToNull(draft.model) : null,
      priceMax: wholeNumber(draft.priceMax),
      yearMin: wholeNumber(draft.yearMin),
    });
  }
}

type SluggedMake = Make & { id: string; slug: string };
type SluggedModel = Model & { id: string; slug: string };

function hasSlug<T extends { id?: string; slug?: string }>(
  entry: T,
): entry is T & { id: string; slug: string } {
  return !!entry.id && !!entry.slug;
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/** Digits only. Anything else is "no value" — the URL parser would drop it anyway. */
function wholeNumber(value: string): number | null {
  const trimmed = value.trim();
  return /^\d{1,9}$/.test(trimmed) ? Number(trimmed) : null;
}

/** This year first, since recent cars are what most searches want. */
export function yearsDownTo(oldest: number, now = new Date()): number[] {
  const years: number[] = [];
  for (let year = now.getFullYear(); year >= oldest; year--) {
    years.push(year);
  }
  return years;
}
