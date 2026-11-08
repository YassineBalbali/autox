import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';

import { ModerationView } from '../../core/api';
import { countFr } from '../../shared/format/plural';
import { Icon } from '../../shared/ui/icon';
import { ModerationLayout } from './moderation-layout';
import {
  Choice,
  DAMAGE_CHOICES,
  EMPTY_QUERY,
  ModerationQuery,
  OFFER_CHOICES,
  ORIGIN_CHOICES,
  SELLER_CHOICES,
  SORT_CHOICES,
  activeFilters,
  defaultSort,
  isNarrowed,
} from './moderation-query';

/** How long typing pauses before the list follows. */
export const SEARCH_DELAY_MS = 350;

/**
 * The search, the filters, the order and the choice of cards or rows, above a moderation list.
 *
 * <p>It holds nothing of its own beyond what is being typed: every change goes out as a whole
 * query, the page puts it in the URL, and the URL comes back in as {@link query}. Typing waits
 * for a pause, so a search is one entry in the history and not one per letter; Enter does not
 * wait.
 *
 * <p>On a phone the filters fold behind one button that says how many are on; the search box
 * stays in view.
 */
@Component({
  selector: 'app-moderation-filters',
  imports: [Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './moderation-filters.html',
  styleUrl: './moderation-filters.scss',
})
export class ModerationFilters {
  readonly query = input.required<ModerationQuery>();
  readonly view = input.required<ModerationView>();
  /** The catalogue's makes, by name; null while they load. */
  readonly makes = input<readonly string[] | null>(null);
  readonly layout = input.required<ModerationLayout>();

  readonly queryChange = output<ModerationQuery>();
  readonly layoutChange = output<ModerationLayout>();

  protected readonly sellers = SELLER_CHOICES;
  protected readonly damages = DAMAGE_CHOICES;
  protected readonly offers = OFFER_CHOICES;
  protected readonly origins = ORIGIN_CHOICES;
  protected readonly sorts = SORT_CHOICES;

  /** What is in the search box, which runs ahead of the URL while typing. */
  protected readonly typed = signal('');
  /** Whether the filters are unfolded, on a phone. */
  protected readonly open = signal(false);

  protected readonly filterCount = computed(() => activeFilters(this.query()));
  protected readonly filterLabel = computed(() => {
    const count = this.filterCount();
    return count === 0 ? 'Filtres' : `Filtres (${count})`;
  });
  protected readonly narrowed = computed(() => isNarrowed(this.query()));
  protected readonly sortSlug = computed(() => slugOf(SORT_CHOICES, this.query().sort ?? defaultSort(this.view())));
  /** A make in the URL that the catalogue does not list (one a seller typed) stays choosable. */
  protected readonly makeOptions = computed(() => {
    const names = [...(this.makes() ?? [])];
    const chosen = this.query().make;
    return chosen && !names.some((name) => name.toLocaleLowerCase('fr-FR') === chosen.toLocaleLowerCase('fr-FR'))
      ? [chosen, ...names]
      : names;
  });
  protected readonly activeSummary = computed(() =>
    this.filterCount() ? countFr(this.filterCount(), 'filtre actif', 'filtres actifs') : null,
  );

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    // The URL is the truth: a link followed, Back pressed or the filters cleared puts its search
    // in the box. Not while a pause is awaited, which would undo what is being typed.
    effect(() => {
      const q = this.query().q;
      if (this.timer === undefined) {
        this.typed.set(q);
      }
    });
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected onType(value: string): void {
    this.typed.set(value);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), SEARCH_DELAY_MS);
  }

  /** Enter, or the pause after typing. */
  protected search(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    if (this.typed().trim() !== this.query().q.trim()) {
      this.emit({ q: this.typed() });
    }
  }

  protected choose<K extends keyof ModerationQuery, T>(key: K, choices: readonly Choice<T>[], slug: string): void {
    this.emit({ [key]: choices.find((choice) => choice.slug === slug)?.value ?? null } as Partial<ModerationQuery>);
  }

  protected chooseMake(name: string): void {
    this.emit({ make: name });
  }

  protected chooseSort(slug: string): void {
    this.emit({ sort: SORT_CHOICES.find((choice) => choice.slug === slug)?.value ?? null });
  }

  /** Whole euros; anything else in the box clears that bound. */
  protected setPrice(key: 'priceMin' | 'priceMax', value: string): void {
    const trimmed = value.trim();
    const euros = /^\d{1,8}$/.test(trimmed) ? Number(trimmed) : null;
    if (euros !== this.query()[key]) {
      this.emit({ [key]: euros });
    }
  }

  protected clear(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.typed.set('');
    this.queryChange.emit({ ...EMPTY_QUERY, sort: this.query().sort });
  }

  protected slug<T>(choices: readonly Choice<T>[], value: T | null): string {
    return slugOf(choices, value);
  }

  private emit(change: Partial<ModerationQuery>): void {
    // What is typed and not yet sent goes with any other change, so it is not lost.
    this.queryChange.emit({ ...this.query(), q: this.typed(), ...change });
  }
}

function slugOf<T>(choices: readonly Choice<T>[], value: T | null): string {
  return value === null ? '' : (choices.find((choice) => choice.value === value)?.slug ?? '');
}
