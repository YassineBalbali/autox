import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { BodyType, FuelType, Gearbox, Make, Model, VehicleCondition } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { FilterChip } from '../../shared/ui/filter-chip';
import {
  BODY_LABELS,
  CONDITION_LABELS,
  FUEL_LABELS,
  GEARBOX_LABELS,
  labelOf,
} from './labels.fr';
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
 * <p>The options come from the reference endpoints — makes, the models of the chosen
 * make, and the values of each enum — not from lists written here. What is written here
 * is only the French for them.
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
  readonly cleared = output<void>();

  /** Below the breakpoint the sidebar is collapsed behind a button; this is that state. */
  protected readonly open = signal(false);

  protected readonly activeCount = computed(() => activeFilterCount(this.query()));

  /** `null` while loading; an empty array when the catalogue is empty or unreachable. */
  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter(hasSlug)),
      catchError(() => of([] as SluggedMake[])),
    ),
    { initialValue: null },
  );

  private readonly enums = toSignal(
    this.reference.enums().pipe(catchError(() => of({} as Record<string, string[]>))),
    { initialValue: {} as Record<string, string[]> },
  );

  /** The catalogue entry for the make in the query, if it is one. */
  protected readonly selectedMake = computed(
    () => this.makes()?.find((make) => make.slug === this.query().make) ?? null,
  );

  /**
   * True when the make filter should be a free-text field rather than a list.
   *
   * <p>The catalogue can legitimately be empty — it is a dataset loaded separately from
   * the code — and listings created before it exists identify their make by text. A
   * dropdown with no options would make the most-used filter unusable in exactly that
   * state, so the field degrades to typing the name, which the API matches as text.
   */
  protected readonly makeIsFreeText = computed(() => this.makes()?.length === 0);

  /** The models of the selected make. Empty until a catalogue make is chosen. */
  protected readonly models = toSignal(
    toObservable(this.selectedMake).pipe(
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

  protected setMake(event: Event): void {
    // Changing the make always clears the model: a model belongs to one make, and
    // "Peugeot" with the model still set to "Clio" is a search that can match nothing.
    this.changed.emit({ make: textOf(event), model: null });
  }

  protected setModel(event: Event): void {
    this.changed.emit({ model: textOf(event) });
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

type SluggedMake = Make & { id: string; slug: string };
type SluggedModel = Model & { id: string; slug: string };

function hasSlug<T extends { id?: string; slug?: string }>(
  entry: T,
): entry is T & { id: string; slug: string } {
  return !!entry.id && !!entry.slug;
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
