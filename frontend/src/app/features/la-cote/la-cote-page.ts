import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { Observable, catchError, map, of, startWith, switchMap } from 'rxjs';

import {
  FuelType,
  Gearbox,
  LaCoteService,
  Make,
  Model,
  Valuation,
  ValuationEngine,
  ValuationScope,
} from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { SelectField, SelectOption } from '../../shared/forms/select-field';
import { TextField } from '../../shared/forms/text-field';
import { parseWholeNumber, requiredText, wholeNumber } from '../../shared/forms/validation';
import { FUEL_LABELS, GEARBOX_LABELS } from '../../shared/listing/labels.fr';
import { Alert } from '../../shared/ui/alert';
import { CoteResult, CoteLinks } from './cote-result';
import {
  CoteQuery,
  MAX_MILEAGE,
  MAX_POWER,
  MIN_YEAR,
  coteParams,
  engineKey,
  engineLabel,
  isComplete,
  parseCote,
  scopeLabel,
  sellParams,
  similarSearchParams,
} from './la-cote';

/** The engine select's "none of these" entry. */
const OTHER_ENGINE = '__autre__';

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'unknown' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; valuation: Valuation; makeId: string; modelId: string; modelName: string };

/**
 * "La Cote": what a car is worth on AutoX, for anyone, signed in or not.
 *
 * <p>The visitor describes the car on the left; the estimate and what it rests on appear on
 * the right. The description goes into the address first and the estimate is read from the
 * address, so an estimate is a link, the back button returns to the previous one, and a
 * reload shows the same thing.
 *
 * <p>The engine is chosen among those listed for the model now, because the estimate is made
 * from listings of the same engine; "Autre motorisation" takes a fuel and a power for one that
 * is not. The gearbox is asked for the two links that follow an estimate, not for the
 * estimate, which does not depend on it.
 */
@Component({
  selector: 'app-la-cote-page',
  imports: [ReactiveFormsModule, SelectField, TextField, Alert, CoteResult],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './la-cote-page.html',
  styleUrl: './la-cote-page.scss',
})
export class LaCotePage {
  private readonly api = inject(LaCoteService);
  private readonly reference = inject(ReferenceStore);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly other = OTHER_ENGINE;
  private readonly now = new Date();

  protected readonly form = new FormGroup({
    make: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    model: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    engine: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    fuel: new FormControl('', { nonNullable: true }),
    power: new FormControl('', { nonNullable: true }),
    year: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    mileage: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), wholeNumber(0, MAX_MILEAGE)],
    }),
    gearbox: new FormControl('', { nonNullable: true }),
  });

  /** The page's state, from the address. */
  protected readonly query = toSignal(this.route.queryParamMap.pipe(map((params) => parseCote(params, this.now))), {
    initialValue: parseCote(this.route.snapshot.queryParamMap, this.now),
  });

  // --- the catalogue --------------------------------------------------------

  private readonly makes = toSignal(this.reference.makes().pipe(catchError(() => of([] as Make[]))), {
    initialValue: [] as Make[],
  });

  protected readonly makeSlug = toSignal(this.form.controls.make.valueChanges.pipe(startWith(this.form.controls.make.value)), {
    initialValue: '',
  });
  private readonly modelSlug = toSignal(this.form.controls.model.valueChanges.pipe(startWith(this.form.controls.model.value)), {
    initialValue: '',
  });
  private readonly engineChoice = toSignal(this.form.controls.engine.valueChanges.pipe(startWith('')), {
    initialValue: '',
  });

  private readonly make = computed(() => this.makes().find((make) => make.slug === this.makeSlug()) ?? null);

  /** The make's models; again when the catalogue arrives after a make was taken from the address. */
  private readonly models = toSignal(
    toObservable(computed(() => this.make()?.id ?? null)).pipe(
      switchMap((makeId) =>
        makeId
          ? this.reference.models(makeId).pipe(
              catchError(() => of([] as Model[])),
              startWith([] as Model[]),
            )
          : of([] as Model[]),
      ),
    ),
    { initialValue: [] as Model[] },
  );

  private readonly model = computed(() => this.models().find((model) => model.slug === this.modelSlug()) ?? null);

  /**
   * The engines listed for the chosen model, with the model they are for: the list from the
   * previous model must not be taken for this one's. Null while they load.
   */
  private readonly enginesOf = toSignal(
    toObservable(computed(() => this.model()?.id ?? null)).pipe(
      switchMap((modelId): Observable<{ modelId: string | null; engines: ValuationEngine[] | null }> =>
        modelId
          ? this.api.engines({ modelId }).pipe(
              catchError(() => of([] as ValuationEngine[])),
              startWith(null),
              map((engines) => ({ modelId, engines })),
            )
          : of({ modelId: null, engines: [] as ValuationEngine[] }),
      ),
    ),
    { initialValue: { modelId: null, engines: [] as ValuationEngine[] } },
  );

  protected readonly engines = computed(() => this.enginesOf().engines);

  protected readonly makeOptions = computed<SelectOption[]>(() =>
    this.makes()
      .filter((make) => make.slug)
      .map((make) => ({ value: make.slug!, label: make.name ?? make.slug! })),
  );

  protected readonly modelOptions = computed<SelectOption[]>(() =>
    this.models()
      .filter((model) => model.slug)
      .map((model) => ({ value: model.slug!, label: model.name ?? model.slug! })),
  );

  protected readonly engineOptions = computed<SelectOption[]>(() => [
    ...(this.engines() ?? []).map((engine) => ({
      value: engineKey(engine.fuelType ?? '', engine.powerHp ?? 0),
      label: engineLabel(engine),
    })),
    { value: OTHER_ENGINE, label: 'Autre motorisation (à préciser)' },
  ]);

  protected readonly otherEngine = computed(() => this.engineChoice() === OTHER_ENGINE);

  protected readonly engineHint = computed(() => {
    const engines = this.engines();
    if (!this.model()) {
      return null;
    }
    if (engines === null) {
      return 'Chargement des motorisations…';
    }
    return engines.length
      ? 'Les motorisations de ce modèle en vente sur AutoX, la plus courante en premier.'
      : 'Aucune annonce de ce modèle en ligne : précisez la motorisation.';
  });

  protected readonly fuelOptions: SelectOption[] = Object.entries(FUEL_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly gearboxOptions: SelectOption[] = Object.entries(GEARBOX_LABELS).map(([value, label]) => ({
    value,
    label,
  }));
  protected readonly yearOptions: SelectOption[] = Array.from(
    { length: this.now.getFullYear() + 1 - MIN_YEAR + 1 },
    (_, index) => String(this.now.getFullYear() + 1 - index),
  ).map((year) => ({ value: year, label: year }));

  // --- the estimate ----------------------------------------------------------

  protected readonly state = signal<State>({ kind: 'idle' });
  protected readonly ready = computed(() => {
    const state = this.state();
    return state.kind === 'ready' ? state : null;
  });
  protected readonly failure = computed(() => {
    const state = this.state();
    return state.kind === 'error' ? state.message : null;
  });
  private readonly minorUnits = toSignal(this.reference.minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  protected readonly minorUnit = computed(() => {
    const state = this.state();
    const currency = state.kind === 'ready' ? state.valuation.estimate?.currencyCode : undefined;
    return this.minorUnits()[currency ?? 'EUR'] ?? DEFAULT_MINOR_UNIT;
  });

  /** The two follow-up actions and the loosenings, as links. */
  protected readonly links = computed<CoteLinks | null>(() => {
    const state = this.state();
    if (state.kind !== 'ready') {
      return null;
    }
    const query = this.query();
    return {
      sell: sellParams(query, state.makeId, state.modelId),
      search: similarSearchParams(query, state.valuation.window),
      wider: (state.valuation.widerScopes ?? []).map((widening) => ({
        label: scopeLabel(widening.scope!, query.year),
        count: widening.comparableCount ?? 0,
        params: coteParams({ ...query, scope: widening.scope! }),
      })),
    };
  });

  constructor() {
    // A model belongs to one make, and an engine to one model.
    this.form.controls.make.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.form.controls.model.setValue(''));
    this.form.controls.model.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.form.controls.engine.setValue(''));

    this.form.controls.engine.valueChanges.pipe(takeUntilDestroyed()).subscribe((choice) => this.chooseEngine(choice));

    // The address changed: show what it describes in the form, and estimate it.
    this.route.queryParamMap
      .pipe(
        map((params) => parseCote(params, this.now)),
        switchMap((query) => {
          this.fill(query);
          return isComplete(query) ? this.estimate(query) : of<State>({ kind: 'idle' });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));

    // Engines arriving for a model taken from the address: select the one it names, or
    // "Autre" with its fuel and power when it is not listed.
    effect(() => {
      const { modelId, engines } = this.enginesOf();
      const query = this.query();
      if (engines === null || !query.fuel || query.power === null || this.form.controls.engine.value) {
        return;
      }
      // Only the engines of the model the address names, once they have loaded for it.
      const model = this.model();
      if (!model || model.id !== modelId || model.slug !== query.model) {
        return;
      }
      const key = engineKey(query.fuel, query.power);
      const listed = engines.some((engine) => engineKey(engine.fuelType ?? '', engine.powerHp ?? 0) === key);
      // With its event: choosing an engine is what sets the fuel and power, and shows the
      // fields for one that is not listed.
      this.form.controls.engine.setValue(listed ? key : OTHER_ENGINE);
      this.form.controls.fuel.setValue(query.fuel);
      this.form.controls.power.setValue(String(query.power));
    });
  }

  protected submit(event: Event): void {
    event.preventDefault();
    this.syncOtherEngine();
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();
    const engine = (this.engines() ?? []).find(
      (candidate) => engineKey(candidate.fuelType ?? '', candidate.powerHp ?? 0) === value.engine,
    );
    const query: CoteQuery = {
      make: value.make,
      model: value.model,
      fuel: value.fuel as FuelType,
      power: parseWholeNumber(value.power),
      year: parseWholeNumber(value.year),
      mileage: parseWholeNumber(value.mileage),
      gearbox: (value.gearbox || null) as Gearbox | null,
      version: engine?.exampleVersion ?? null,
      // A new description is valued by the indicator's own rule first.
      scope: ValuationScope.EXACT,
    };
    void this.router.navigate([], { relativeTo: this.route, queryParams: coteParams(query) });
  }

  protected goTo(params: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: params });
  }

  // --- the form --------------------------------------------------------------

  /** A listed engine sets the fuel and power; "Autre" leaves them to be typed. */
  private chooseEngine(choice: string): void {
    if (choice && choice !== OTHER_ENGINE) {
      const [fuel, power] = choice.split(':');
      this.form.controls.fuel.setValue(fuel);
      this.form.controls.power.setValue(power);
    } else if (choice === OTHER_ENGINE) {
      this.form.controls.fuel.setValue('');
      this.form.controls.power.setValue('');
    }
    this.syncOtherEngine();
  }

  /** The fuel and power fields are required only when the visitor types them. */
  private syncOtherEngine(): void {
    const typed = this.form.controls.engine.value === OTHER_ENGINE;
    const { fuel, power } = this.form.controls;
    fuel.setValidators(typed ? [requiredText()] : []);
    power.setValidators(typed ? [requiredText(), wholeNumber(1, MAX_POWER)] : []);
    fuel.updateValueAndValidity({ emitEvent: false });
    power.updateValueAndValidity({ emitEvent: false });
  }

  /**
   * The address's description, into the form. In order, make then model, because choosing a
   * make clears the model; the engine follows when the model's engines have loaded.
   */
  private fill(query: CoteQuery): void {
    const controls = this.form.controls;
    if (controls.make.value !== (query.make ?? '')) {
      controls.make.setValue(query.make ?? '');
    }
    if (controls.model.value !== (query.model ?? '')) {
      controls.model.setValue(query.model ?? '');
    }
    controls.year.setValue(query.year === null ? '' : String(query.year));
    controls.mileage.setValue(query.mileage === null ? '' : String(query.mileage));
    controls.gearbox.setValue(query.gearbox ?? '');
  }

  /** Resolves the slugs to the catalogue, then asks for the estimate. */
  private estimate(query: CoteQuery): Observable<State> {
    return this.reference.makes().pipe(
      switchMap((makes) => {
        const make = makes.find((candidate) => candidate.slug === query.make);
        return make?.id
          ? this.reference.models(make.id).pipe(map((models) => ({ make, model: models.find((m) => m.slug === query.model) })))
          : of({ make: undefined, model: undefined });
      }),
      switchMap(({ make, model }): Observable<State> => {
        if (!make?.id || !model?.id) {
          return of({ kind: 'unknown' });
        }
        return this.api
          .value({
            car: {
              modelId: model.id,
              fuelType: query.fuel!,
              powerHp: query.power!,
              year: query.year!,
              mileageKm: query.mileage!,
              scope: query.scope,
            },
          })
          .pipe(
            map((valuation): State => ({
              kind: 'ready',
              valuation,
              makeId: make.id!,
              modelId: model.id!,
              modelName: `${make.name ?? ''} ${model.name ?? ''}`.trim(),
            })),
            catchError((error: unknown) =>
              of<State>(
                error instanceof HttpErrorResponse && error.status === 404
                  ? { kind: 'unknown' }
                  : { kind: 'error', message: this.errors.messageFor(error) },
              ),
            ),
            startWith<State>({ kind: 'loading' }),
          );
      }),
      catchError((error: unknown) => of<State>({ kind: 'error', message: this.errors.messageFor(error) })),
    );
  }
}
