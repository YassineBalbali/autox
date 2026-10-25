import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { BodyType, FuelType, Gearbox, ResultCount, VehicleCondition } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { WIDE, mediaQuery } from '../../core/ui/media';
import { OverlayEntry, OverlayHistory } from '../../core/ui/overlay-history';
import { formatIntegerFr } from '../../shared/format/number';
import { Icon } from '../../shared/ui/icon';
import { FilterChip } from '../../shared/ui/filter-chip';
import {
  BODY_LABELS,
  CONDITION_LABELS,
  FUEL_LABELS,
  GEARBOX_LABELS,
  labelOf,
} from '../../shared/listing/labels.fr';
import { SearchQuery, activeFilterCount } from './search-query';
import { VehicleFilter } from './vehicle-filter';

/** One multi-choice filter: its key in the query, its heading, its options. */
interface ChoiceGroup {
  readonly key: 'fuel' | 'gearbox' | 'body' | 'condition';
  readonly legend: string;
  readonly options: readonly { value: string; label: string }[];
}

type RangeKey = 'yearMin' | 'yearMax' | 'priceMin' | 'priceMax' | 'mileageMin' | 'mileageMax';

/**
 * The filter sidebar.
 *
 * <p><b>It holds no filter state.</b> What it displays is the query it is given, and a
 * change is reported upward as the part of the query that should change; the page turns
 * that into a navigation, the URL changes, and the new query comes back down. So the
 * sidebar cannot disagree with the results beside it, and the back button undoes a filter
 * without this component knowing it happened.
 *
 * <p>Make and model are not here: they are in the search bar above, where a search is
 * composed, and one control per filter is the rule. What is here refines a list that is
 * already on screen — ranges, the enum filters, and where.
 *
 * <p>The enum options come from the reference endpoint, not from lists written here.
 * What is written here is only the French for them.
 *
 * <p><b>Two places.</b> From the large breakpoint up the form is a sidebar beside the
 * results. Below it, it is a full-screen sheet — a native modal dialog, so focus is held in
 * it, the page behind is inert, and Escape closes it — opened from the page's sticky bar.
 * Its changes apply as they are made, exactly as in the sidebar, so the button at its
 * foot only closes it, and can say how many cars the filters find. On a phone the sheet
 * also carries make and model, which the search bar there no longer shows.
 *
 * <p>Back closes the sheet and leaves the filters as they are ({@link OverlayHistory}).
 * While it is open the page records each change in place of the last rather than as a
 * new history entry, so one visit to the sheet is one step back afterwards.
 *
 * <p>Text and number fields report on `change`, which fires when the field is left or
 * Enter is pressed, not on every keystroke: typing "15000" would otherwise be five
 * searches and five entries in the browser history.
 */
@Component({
  selector: 'app-search-filters',
  imports: [FilterChip, NgTemplateOutlet, Icon, VehicleFilter],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-filters.html',
  styleUrl: './search-filters.scss',
})
export class SearchFilters {
  private readonly reference = inject(ReferenceStore);

  readonly query = input.required<SearchQuery>();

  /** How many listings the current filters find, for the sheet's button. */
  readonly resultCount = input<ResultCount | null>(null);

  /** The fields of the query to replace. */
  readonly changed = output<Partial<SearchQuery>>();

  /** "Tout effacer", from the sheet. */
  readonly cleared = output<void>();

  /**
   * Sidebar or sheet. Without `matchMedia` — the test runner — the sidebar: the layout the
   * component had before there was a sheet.
   */
  protected readonly wide = mediaQuery(WIDE, true);

  /** Whether the sheet is open. Read by the page, for its button's aria-expanded. */
  readonly sheetOpen = signal(false);

  private readonly sheet = viewChild.required<ElementRef<HTMLDialogElement>>('sheet');
  private readonly history = inject(OverlayHistory);
  private entry: OverlayEntry | null = null;

  protected readonly activeCount = computed(() => activeFilterCount(this.query()));

  /** "Voir les 120 annonces": what closing the sheet will show. */
  protected readonly showLabel = computed(() => showLabel(this.resultCount()));

  constructor() {
    effect(() => {
      const sheet = this.sheet().nativeElement;
      // Never modal on a wide screen: if the window is widened with the sheet open, the
      // sidebar takes over and the page must not be left inert behind an empty dialog.
      const open = this.sheetOpen() && !this.wide();
      if (open && !sheet.open) {
        sheet.showModal();
      } else if (!open && sheet.open) {
        sheet.close();
      }
    });
  }

  /** Opens the sheet. Below the large breakpoint only; above it the sidebar is there. */
  openSheet(): void {
    if (this.sheetOpen() || this.wide()) {
      return;
    }
    this.sheetOpen.set(true);
    this.entry = this.history.open(() => {
      this.entry = null;
      this.sheetOpen.set(false);
    });
  }

  /** Closed from its own controls: the close button, the results button, Escape. */
  protected closeSheet(): void {
    this.sheetOpen.set(false);
    this.entry?.closed();
    this.entry = null;
  }

  private readonly enums = toSignal(
    this.reference.enums().pipe(catchError(() => of({} as Record<string, string[]>))),
    { initialValue: {} as Record<string, string[]> },
  );

  protected readonly groups = computed<ChoiceGroup[]>(() => {
    const enums = this.enums();
    return [
      group('fuel', 'Carburant', enums['FuelType'], FuelType, FUEL_LABELS),
      group('gearbox', 'Boîte de vitesses', enums['Gearbox'], Gearbox, GEARBOX_LABELS),
      group('body', 'Carrosserie', enums['BodyType'], BodyType, BODY_LABELS),
      group('condition', 'État', enums['VehicleCondition'], VehicleCondition, CONDITION_LABELS),
    ].filter((entry) => entry.options.length > 0);
  });

  protected isChosen(key: ChoiceGroup['key'], value: string): boolean {
    return (this.query()[key] as readonly string[]).includes(value);
  }

  protected toggleChoice(key: ChoiceGroup['key'], value: string, chosen: boolean): void {
    const current = this.query()[key] as readonly string[];
    const next = chosen ? [...current, value] : current.filter((entry) => entry !== value);
    this.changed.emit({ [key]: next } as Partial<SearchQuery>);
  }

  protected setLocation(event: Event): void {
    this.changed.emit({ location: textOf(event) });
  }

  protected setNumber(key: RangeKey, event: Event): void {
    const field = event.target as HTMLInputElement;
    const raw = field.value.trim();
    const value = /^\d{1,9}$/.test(raw) ? Number(raw) : null;
    if (value === null && raw !== '') {
      // Not a number we can use. Emptied rather than left showing a value that is not
      // being applied — the field must never claim a filter the results do not have.
      field.value = '';
    }
    this.changed.emit({ [key]: value } as Partial<SearchQuery>);
  }
}

/**
 * The sheet's button. The count is asked separately from the results and may be late or
 * missing: then it says what it does without a figure rather than guessing one.
 */
export function showLabel(count: ResultCount | null): string {
  if (count === null || typeof count.count !== 'number') {
    return 'Voir les annonces';
  }
  if (count.capped) {
    return `Voir plus de ${formatIntegerFr(count.count)} annonces`;
  }
  if (count.count === 0) {
    return 'Aucune annonce';
  }
  return count.count === 1 ? 'Voir l’annonce' : `Voir les ${formatIntegerFr(count.count)} annonces`;
}

function textOf(event: Event): string | null {
  const value = (event.target as HTMLInputElement | HTMLSelectElement).value.trim();
  return value.length > 0 ? value : null;
}

/**
 * The options of one filter: the values the API offers, in the API's order, that this
 * build also knows.
 *
 * <p>Both halves matter. The API's list decides what is offered, so a value retired on
 * the backend disappears from the sidebar without a release. The generated enum decides
 * what this build can act on: a value newer than the client would be dropped when the URL
 * is parsed, and a chip that does nothing when pressed is worse than one that is absent
 * until the client is regenerated.
 */
function group(
  key: ChoiceGroup['key'],
  legend: string,
  offered: readonly string[] | undefined,
  known: Record<string, string>,
  labels: Record<string, string>,
): ChoiceGroup {
  const supported = new Set(Object.values(known));
  return {
    key,
    legend,
    options: (offered ?? [])
      .filter((value) => supported.has(value))
      .map((value) => ({ value, label: labelOf(labels, value) })),
  };
}
