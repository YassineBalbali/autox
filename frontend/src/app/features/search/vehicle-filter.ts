import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap } from 'rxjs';

import { Make, Model } from '../../core/api';
import { ReferenceStore } from '../../core/reference/reference.store';
import { SearchQuery } from './search-query';

type SluggedMake = Make & { id: string; slug: string };
type SluggedModel = Model & { id: string; slug: string };

/**
 * Make and model, as filters: the phone's filter sheet carries them, because on a phone
 * the search bar keeps only its text field.
 *
 * <p>The bar's two selects compose a search and wait for its button. These apply the
 * moment they change, like every other control in the sheet — which is what lets the
 * sheet's own button say how many cars the filters already find.
 *
 * <p>Shown below the medium breakpoint only, where the bar's own selects are hidden: one
 * control per filter on screen is the rule, and the sheet is also used on a tablet, whose
 * bar still has them.
 */
@Component({
  selector: 'app-vehicle-filter',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="vehicle">
      <legend i18n="@@filterVehicle">Véhicule</legend>

      <label class="vehicle__label" for="f-marque" i18n="@@filterMake">Marque</label>
      @if (makes()?.length === 0) {
        <!-- No catalogue to choose from: typed, as the bar does. -->
        <input class="input" id="f-marque" type="text" autocomplete="off" [value]="query().make ?? ''" (change)="setMake($event)" />
      } @else {
        <select class="input" id="f-marque" [disabled]="makes() === null" (change)="setMake($event)">
          <option value="" [selected]="!query().make" i18n="@@filterAnyMake">Toutes les marques</option>
          @for (make of makes() ?? []; track make.id) {
            <option [value]="make.slug" [selected]="make.slug === query().make">{{ make.name }}</option>
          }
          @if (query().make && !make() && makes() !== null) {
            <option [value]="query().make" selected>{{ query().make }}</option>
          }
        </select>
      }

      <label class="vehicle__label" for="f-modele" i18n="@@filterModel">Modèle</label>
      <select class="input" id="f-modele" [disabled]="!make()" (change)="setModel($event)">
        <option value="" [selected]="!query().model">
          @if (make()) {
            <ng-container i18n="@@filterAnyModel">Tous les modèles</ng-container>
          } @else {
            <ng-container i18n="@@filterModelNeedsMake">Choisissez une marque</ng-container>
          }
        </option>
        @for (model of models(); track model.id) {
          <option [value]="model.slug" [selected]="model.slug === query().model">{{ model.name }}</option>
        }
      </select>
    </fieldset>
  `,
  styles: `
    .vehicle {
      display: flex;
      flex-direction: column;
      gap: var(--s-1);
      min-width: 0;
      margin: 0;
      padding: 0;
      border: 0;

      legend {
        float: left;
        width: 100%;
        padding: 0;
        margin-bottom: var(--s-1);
        font-size: var(--t-sm);
        font-weight: var(--fw-bold);
      }
    }

    .vehicle__label {
      margin-top: var(--s-1);
      color: var(--c-ink-soft);
      font-size: var(--t-sm);
      font-weight: var(--fw-semibold);
    }

    .input {
      max-width: none;
    }
  `,
})
export class VehicleFilter {
  private readonly reference = inject(ReferenceStore);

  readonly query = input.required<SearchQuery>();

  /** The fields of the query to replace. */
  readonly changed = output<Partial<SearchQuery>>();

  /** `null` while loading; empty when the catalogue is empty or could not be read. */
  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter(hasSlug)),
      catchError(() => of([] as SluggedMake[])),
    ),
    { initialValue: null },
  );

  /** The catalogue entry for the make being searched. */
  protected readonly make = computed(
    () => this.makes()?.find((make) => make.slug === this.query().make) ?? null,
  );

  protected readonly models = toSignal(
    toObservable(this.make).pipe(
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

  protected setMake(event: Event): void {
    // A model belongs to one make: changing the make clears it.
    this.changed.emit({ make: valueOf(event), model: null });
  }

  protected setModel(event: Event): void {
    this.changed.emit({ model: valueOf(event) });
  }
}

function valueOf(event: Event): string | null {
  const value = (event.target as HTMLInputElement | HTMLSelectElement).value.trim();
  return value.length > 0 ? value : null;
}

function hasSlug<T extends { id?: string; slug?: string }>(
  entry: T,
): entry is T & { id: string; slug: string } {
  return !!entry.id && !!entry.slug;
}
