import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import { BodyType, FuelType, Gearbox, VehicleCondition } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { FilterChip } from '../../shared/ui/filter-chip';
import {
  BODY_LABELS,
  CONDITION_LABELS,
  FUEL_LABELS,
  GEARBOX_LABELS,
  labelOf,
} from '../../shared/listing/labels.fr';
import { SearchQuery, activeFilterCount } from './search-query';

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
 * <p>Text and number fields report on `change`, which fires when the field is left or
 * Enter is pressed, not on every keystroke: typing "15000" would otherwise be five
 * searches and five entries in the browser history.
 */
@Component({
  selector: 'app-search-filters',
  imports: [FilterChip],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './search-filters.html',
  styleUrl: './search-filters.scss',
})
export class SearchFilters {
  private readonly reference = inject(ReferenceStore);

  readonly query = input.required<SearchQuery>();

  /** The fields of the query to replace. */
  readonly changed = output<Partial<SearchQuery>>();

  /** Below the breakpoint the sidebar is collapsed behind a button; this is that state. */
  protected readonly open = signal(false);

  protected readonly activeCount = computed(() => activeFilterCount(this.query()));

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

  protected toggleOpen(): void {
    this.open.update((open) => !open);
  }

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
