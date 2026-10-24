import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule, Validators } from '@angular/forms';
import { Title } from '@angular/platform-browser';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, map, of, startWith, switchMap } from 'rxjs';

import {
  BodyType,
  ErrorCode,
  FuelType,
  Gearbox,
  ImageResponse,
  ImageStatus,
  ListingQuota,
  ListingResponse,
  ListingStatus,
  LaCoteService,
  ListingsSellerService,
  Make,
  Model,
  PublicListingDetail,
  VehicleCondition,
  ValuationEngine,
} from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { engineLabel } from '../la-cote/la-cote';
import { FlashService } from '../../core/ui/flash.service';
import { SelectField, SelectOption } from '../../shared/forms/select-field';
import { TextField } from '../../shared/forms/text-field';
import { requiredText } from '../../shared/forms/validation';
import {
  BODY_LABELS,
  CONDITION_LABELS,
  FUEL_LABELS,
  GEARBOX_LABELS,
  labelOf,
} from '../../shared/listing/labels.fr';
import { PhotoGallery } from '../../shared/listing/photo-gallery';
import { Price } from '../../shared/listing/price';
import { SpecLine } from '../../shared/listing/spec-line';
import { Alert } from '../../shared/ui/alert';
import { NotFound } from '../../shared/ui/not-found';
import { galleryImages, specificationRows } from '../listing/listing-view';
import {
  LIMITS,
  Market,
  OTHER,
  makeIsTyped,
  modelIsTyped,
  offerForm,
  offerValueOf,
  suggestedTitle,
  vehiclePrefill,
  powerValidators,
  toCreateRequest,
  toOfferUpdate,
  toVehicleUpdate,
  vehicleForm,
  vehicleValueOf,
} from './listing-form';
import { transitionsOf } from './listing-status';
import { PhotoUploader } from './photo-uploader';

/** The four steps, by the name each has in the URL. */
export const STEPS = ['vehicule', 'offre', 'photos', 'apercu'] as const;
export type Step = (typeof STEPS)[number];

const STEP_TITLES: Record<Step, string> = {
  vehicule: 'Véhicule',
  offre: 'Prix et description',
  photos: 'Photos',
  apercu: 'Aperçu et publication',
};

type LoadState = 'loading' | 'ready' | 'missing' | 'error';

export const MY_LISTINGS = '/mes-annonces';

/**
 * Create a listing, or edit one. The same form for both.
 *
 * <h2>The order of the steps</h2>
 *
 * <p>Vehicle, then price and location, then photos, then preview. Photos come third
 * rather than second because of what the API requires: a photo is uploaded <em>to a
 * listing</em>, and a listing cannot be created without a price and a location. So the
 * draft is saved at the end of step two, and from then on everything the seller does is
 * kept — the photos go to a real listing as they are dropped, and closing the tab loses
 * nothing.
 *
 * <p>That save is also the moment "create" becomes "edit": the URL changes to the
 * listing's own, so a reload continues with the draft instead of starting again.
 *
 * <h2>Rules are explained here, enforced there</h2>
 *
 * <p>At least one photo, the cap on a private seller's online listings, which states may
 * follow which: all of these are the API's rules and it applies them whatever this screen
 * believes. What the screen adds is saying so beforehand, in French, next to the button —
 * rather than letting the seller press it and read an error.
 */
@Component({
  selector: 'app-listing-editor-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    TextField,
    SelectField,
    Alert,
    NotFound,
    PhotoUploader,
    PhotoGallery,
    Price,
    SpecLine,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './listing-editor-page.html',
  styleUrl: './listing-editor-page.scss',
})
export class ListingEditorPage {
  private readonly api = inject(ListingsSellerService);
  private readonly reference = inject(ReferenceStore);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);
  private readonly flash = inject(FlashService);
  private readonly auth = inject(AuthService);
  private readonly title = inject(Title);
  private readonly laCote = inject(LaCoteService);

  /** The listing being edited — absent on the create route. */
  readonly id = input<string | undefined>();
  /** The step, from `?etape=`. */
  readonly etape = input<string | undefined>();
  /** A car to start from, from La Cote's "Vendre ma voiture"; see `vehiclePrefill`. */
  readonly marque = input<string | undefined>();
  readonly modele = input<string | undefined>();
  readonly version = input<string | undefined>();
  readonly annee = input<string | undefined>();
  readonly km = input<string | undefined>();
  readonly carburant = input<string | undefined>();
  readonly puissance = input<string | undefined>();
  readonly boite = input<string | undefined>();

  /** Taken once, on the create route: after that the form is the seller's. */
  private prefillTaken = false;
  /** The prefilled model, set once its make's models have loaded. */
  private readonly pendingModel = signal<string | null>(null);

  protected readonly steps = STEPS;
  protected readonly stepTitles = STEP_TITLES;
  protected readonly limits = LIMITS;
  protected readonly other = OTHER;
  protected readonly myListings = MY_LISTINGS;

  protected readonly load = signal<LoadState>('loading');
  protected readonly listing = signal<ListingResponse | null>(null);
  protected readonly loadFailure = signal<string | null>(null);

  protected readonly saving = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly quota = signal<ListingQuota | null>(null);

  protected readonly vehicle = vehicleForm();
  protected readonly offer = offerForm();

  protected readonly editing = computed(() => this.listing() !== null);
  protected readonly heading = computed(() => (this.id() ? 'Modifier l’annonce' : 'Déposer une annonce'));

  /**
   * The step shown. A step that needs a saved listing falls back to the first when there
   * is none yet — someone opening `/vendre?etape=photos` has nothing to add photos to.
   */
  protected readonly step = computed<Step>(() => {
    const asked = STEPS.find((candidate) => candidate === this.etape()) ?? 'vehicule';
    const needsListing = asked === 'photos' || asked === 'apercu';
    return needsListing && !this.editing() ? 'vehicule' : asked;
  });

  // --- reference data ------------------------------------------------------

  protected readonly makes = toSignal(
    this.reference.makes().pipe(
      map((makes) => makes.filter((make) => make.id)),
      catchError(() => of([] as Make[])),
    ),
    { initialValue: null },
  );

  /** The models of the make currently chosen in the form. */
  protected readonly models = toSignal(
    this.vehicle.controls.make.valueChanges.pipe(
      startWith(this.vehicle.controls.make.value),
      switchMap(
        (make): Observable<Model[]> =>
          make && make !== OTHER
            ? this.reference.models(make).pipe(
                catchError(() => of([] as Model[])),
                startWith([] as Model[]),
              )
            : of([]),
      ),
    ),
    { initialValue: [] as Model[] },
  );

  private readonly enums = toSignal(
    this.reference.enums().pipe(catchError(() => of({} as Record<string, string[]>))),
    { initialValue: {} as Record<string, string[]> },
  );

  /** Mirrors of the two controls the template branches on; see RegisterPage.role. */
  protected readonly make = toSignal(this.vehicle.controls.make.valueChanges, {
    initialValue: this.vehicle.controls.make.value,
  });
  private readonly model = toSignal(this.vehicle.controls.model.valueChanges, {
    initialValue: this.vehicle.controls.model.value,
  });

  /**
   * The engines of the chosen catalogue model as listed on AutoX, offered as a way to fill
   * the fuel and power in one click. There is no trim catalogue to take a power from, and the
   * listed engines are the next best thing: the ones the price indicator compares against.
   */
  protected readonly engines = toSignal(
    this.vehicle.controls.model.valueChanges.pipe(
      startWith(this.vehicle.controls.model.value),
      switchMap((model): Observable<ValuationEngine[]> =>
        model && model !== OTHER
          ? this.laCote.engines({ modelId: model }).pipe(catchError(() => of([] as ValuationEngine[])))
          : of([]),
      ),
    ),
    { initialValue: [] as ValuationEngine[] },
  );

  /** A listing saved before the power was asked for, and still without one. */
  protected readonly powerMissing = computed(() => {
    const listing = this.listing();
    return !!listing && listing.vehicle?.powerHp == null;
  });

  protected readonly makeTyped = computed(() => makeIsTyped({ make: this.make() }, this.makes() ?? []));
  protected readonly modelTyped = computed(() =>
    modelIsTyped({ make: this.make(), model: this.model() }, this.makes() ?? []),
  );

  protected readonly makeOptions = computed<SelectOption[]>(() => [
    ...(this.makes() ?? []).map((make) => ({ value: make.id!, label: make.name ?? make.id! })),
    { value: OTHER, label: 'Autre marque (à saisir)' },
  ]);

  protected readonly modelOptions = computed<SelectOption[]>(() => [
    ...this.models()
      .filter((model) => model.id)
      .map((model) => ({ value: model.id!, label: model.name ?? model.id! })),
    { value: OTHER, label: 'Autre modèle (à saisir)' },
  ]);

  protected readonly fuelOptions = computed(() => options(this.enums()['FuelType'], FuelType, FUEL_LABELS));
  protected readonly gearboxOptions = computed(() => options(this.enums()['Gearbox'], Gearbox, GEARBOX_LABELS));
  protected readonly bodyOptions = computed(() => options(this.enums()['BodyType'], BodyType, BODY_LABELS));
  protected readonly conditionOptions = computed(() =>
    options(this.enums()['VehicleCondition'], VehicleCondition, CONDITION_LABELS),
  );

  /**
   * The seller's market: country, currency, and how many decimals that currency has.
   *
   * <p>From the reference data, for the account's own country. A listing is priced in
   * its market's currency; the form asks for whole units and the API stores minor ones,
   * and the factor between them is not a constant.
   */
  private readonly market = toSignal(
    this.reference.countries().pipe(
      map((countries): Market => {
        const code = this.auth.user()?.countryCode ?? 'FR';
        const country = countries.find((candidate) => candidate.code === code) ?? countries[0];
        return {
          countryCode: country?.code ?? code,
          currencyCode: country?.currencyCode ?? 'EUR',
          minorUnit: country?.currencyMinorUnit ?? DEFAULT_MINOR_UNIT,
        };
      }),
      catchError(() => of<Market>({ countryCode: 'FR', currencyCode: 'EUR', minorUnit: DEFAULT_MINOR_UNIT })),
    ),
    { initialValue: { countryCode: 'FR', currencyCode: 'EUR', minorUnit: DEFAULT_MINOR_UNIT } as Market },
  );

  protected readonly priceLabel = computed(() => `Prix (${this.market().currencyCode === 'EUR' ? '€' : this.market().currencyCode})`);

  // --- preview -------------------------------------------------------------

  protected readonly photos = signal<ImageResponse[]>([]);
  protected readonly hasPhoto = computed(() => this.photos().length > 0);

  /** The saved listing with its current photos, in the shape the public page displays. */
  private readonly preview = computed<PublicListingDetail | null>(() => {
    const listing = this.listing();
    return listing ? ({ ...listing, images: this.photos(), featureCodes: listing.featureCodes ?? [] } as PublicListingDetail) : null;
  });

  protected readonly previewImages = computed(() => (this.preview() ? galleryImages(this.preview()!) : []));
  protected readonly previewSpecs = computed(() => (this.preview() ? specificationRows(this.preview()!) : []));
  protected readonly previewFuel = computed(() => labelOf(FUEL_LABELS, this.listing()?.vehicle?.fuelType));
  protected readonly previewGearbox = computed(() => labelOf(GEARBOX_LABELS, this.listing()?.vehicle?.gearbox));
  protected readonly minorUnit = computed(() => this.market().minorUnit);

  /** Whether the API would let this listing go online from the state it is in. */
  protected readonly publishable = computed(() => {
    const listing = this.listing();
    return listing ? transitionsOf(listing).includes(ListingStatus.ACTIVE) : false;
  });

  protected readonly online = computed(() => this.listing()?.status === ListingStatus.ACTIVE);

  /** True when a private seller already has as many listings online as they may. */
  protected readonly capReached = computed(() => this.publishable() && this.quota()?.canPublish === false);

  protected readonly canPublish = computed(
    () => this.publishable() && this.hasPhoto() && !this.capReached() && !this.saving(),
  );

  constructor() {
    this.syncVehicleRules();
    this.vehicle.controls.make.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
      // A model belongs to one make.
      this.vehicle.controls.model.setValue('', { emitEvent: true });
      this.syncVehicleRules();
    });
    this.vehicle.controls.model.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.syncVehicleRules());

    // The catalogue arriving can change which fields are required.
    effect(() => {
      this.makes();
      this.syncVehicleRules();
    });

    // A car described on La Cote: into the vehicle step, once the makes are known, so the
    // make can be checked against them. The make first, since choosing it clears the model.
    effect(() => {
      const makes = this.makes();
      if (this.id() || this.prefillTaken || makes === null) {
        return;
      }
      this.prefillTaken = true;
      const prefill = vehiclePrefill({
        marque: this.marque(),
        modele: this.modele(),
        version: this.version(),
        annee: this.annee(),
        km: this.km(),
        carburant: this.carburant(),
        puissance: this.puissance(),
        boite: this.boite(),
      });
      if (!prefill) {
        return;
      }
      if (prefill.make && makes.some((make) => make.id === prefill.make)) {
        this.vehicle.controls.make.setValue(prefill.make);
        this.pendingModel.set(prefill.model);
      }
      this.vehicle.patchValue(prefill.values);
    });
    effect(() => {
      const wanted = this.pendingModel();
      if (wanted && this.models().some((model) => model.id === wanted)) {
        this.pendingModel.set(null);
        this.vehicle.controls.model.setValue(wanted);
      }
    });

    // Load the listing named by the route; on the create route there is nothing to load.
    effect(() => {
      const id = this.id();
      if (!id) {
        this.listing.set(null);
        this.load.set('ready');
        this.title.setTitle('Déposer une annonce · AutoX');
        return;
      }
      if (this.listing()?.id === id) {
        return;
      }
      this.fetch(id);
    });

    // Entering the preview: ask how many listings are online, to explain the cap before
    // the publish button is pressed rather than after.
    effect(() => {
      if (this.step() === 'apercu' && this.publishable()) {
        this.api.quota().subscribe({
          next: (quota) => this.quota.set(quota),
          // Unknown is not "no": the button stays enabled and the API decides.
          error: () => this.quota.set(null),
        });
      }
    });

    // Entering the offer step with no title yet: suggest one from the vehicle.
    effect(() => {
      if (this.step() === 'offre' && !this.offer.controls.title.value.trim()) {
        this.offer.controls.title.setValue(
          suggestedTitle(this.vehicle.getRawValue(), this.makes() ?? [], this.models()),
        );
      }
    });
  }

  protected retryLoad(): void {
    const id = this.id();
    if (id) {
      this.fetch(id);
    }
  }

  private fetch(id: string): void {
    this.load.set('loading');
    this.api.getOwn({ id }).subscribe({
      next: (listing) => {
        this.adopt(listing);
        this.vehicle.reset(vehicleValueOf(listing));
        this.offer.reset(offerValueOf(listing, this.market().minorUnit));
        this.syncVehicleRules();
        this.load.set('ready');
        this.title.setTitle(`Modifier « ${listing.title ?? 'annonce'} » · AutoX`);
      },
      error: (error: unknown) => {
        if (asApiError(error)?.code === ErrorCode.NOT_FOUND) {
          // Somebody else's, or deleted. The API does not say which, and neither do we.
          this.load.set('missing');
        } else {
          this.loadFailure.set(this.errors.messageFor(error));
          this.load.set('error');
        }
      },
    });
  }

  private adopt(listing: ListingResponse): void {
    this.listing.set(listing);
    this.photos.set(
      [...(listing.images ?? [])]
        .filter((image) => image.status === undefined || image.status === ImageStatus.READY)
        .sort((a, b) => (a.position ?? 0) - (b.position ?? 0)),
    );
  }

  /** One of the listed engines: its fuel and power, and its usual name if none is typed. */
  protected useEngine(engine: ValuationEngine): void {
    const { fuel, power, version } = this.vehicle.controls;
    fuel.setValue(engine.fuelType ?? '');
    power.setValue(engine.powerHp ? String(engine.powerHp) : '');
    if (!version.value.trim() && engine.exampleVersion) {
      version.setValue(engine.exampleVersion);
    }
    for (const control of [fuel, power, version]) {
      control.markAsDirty();
    }
  }

  protected readonly engineLabel = engineLabel;

  // --- moving between steps ------------------------------------------------

  /** Whether a step can be opened directly from the stepper. */
  protected reachable(step: Step): boolean {
    if (step === 'vehicule') {
      return true;
    }
    if (step === 'offre') {
      return this.editing() || this.vehicle.valid;
    }
    return this.editing();
  }

  protected goTo(step: Step): void {
    this.failure.set(null);
    void this.router.navigate([], { queryParams: { etape: step }, queryParamsHandling: 'merge' });
  }

  protected continueFromVehicle(): void {
    if (this.vehicle.invalid) {
      this.vehicle.markAllAsTouched();
      return;
    }
    const listing = this.listing();
    if (!listing?.id) {
      // Nothing to save yet: the draft cannot exist until it has a price and a location.
      this.goTo('offre');
      return;
    }
    this.save(
      this.api.update({
        id: listing.id,
        updateListingRequest: toVehicleUpdate(this.vehicle.getRawValue(), this.makes() ?? [], this.models()),
      }),
      () => this.goTo('offre'),
    );
  }

  protected continueFromOffer(): void {
    if (this.offer.invalid || this.vehicle.invalid) {
      this.offer.markAllAsTouched();
      this.vehicle.markAllAsTouched();
      if (this.vehicle.invalid) {
        // The draft needs both halves. Send the seller back to the one that is wrong.
        this.goTo('vehicule');
      }
      return;
    }
    const listing = this.listing();
    if (listing?.id) {
      this.save(
        this.api.update({ id: listing.id, updateListingRequest: toOfferUpdate(this.offer.getRawValue(), this.market()) }),
        () => this.goTo('photos'),
      );
      return;
    }

    // The draft is created here. From now on the URL is the listing's own, so a reload
    // carries on with it rather than starting an empty form.
    this.save(
      this.api.create({
        createListingRequest: toCreateRequest(
          this.vehicle.getRawValue(),
          this.offer.getRawValue(),
          this.market(),
          this.makes() ?? [],
          this.models(),
        ),
      }),
      (created) =>
        void this.router.navigate([MY_LISTINGS, created.id, 'modifier'], {
          queryParams: { etape: 'photos' },
          replaceUrl: true,
        }),
    );
  }

  protected onPhotos(photos: ImageResponse[]): void {
    this.photos.set(photos);
  }

  protected publish(): void {
    const id = this.listing()?.id;
    if (!id || !this.canPublish()) {
      return;
    }
    this.save(this.api.publish({ id }), () => {
      this.flash.set('Votre annonce est en ligne.');
      void this.router.navigateByUrl(MY_LISTINGS);
    });
  }

  /** Runs a save, keeps the listing in step with the answer, and reports a failure in French. */
  private save(request: Observable<ListingResponse>, then: (listing: ListingResponse) => void): void {
    this.failure.set(null);
    this.saving.set(true);
    request.subscribe({
      next: (listing) => {
        this.saving.set(false);
        // A PATCH answers with the listing but not always its photos; keep the ones we have.
        const photos = this.photos();
        this.adopt(listing);
        if (!listing.images?.length && photos.length) {
          this.photos.set(photos);
        }
        then(listing);
      },
      error: (error: unknown) => {
        this.saving.set(false);
        this.failure.set(this.errors.messageFor(error));
        if (asApiError(error)?.code === ErrorCode.ACTIVE_LISTING_LIMIT_REACHED) {
          // The cap was reached between the check and the click. Reflect it.
          this.quota.update((quota) => (quota ? { ...quota, canPublish: false } : quota));
        }
      },
    });
  }

  /**
   * Makes exactly the fields on screen required.
   *
   * <p>Which fields exist depends on two choices: a make from the list or typed, a model
   * from the list or typed. A field that is hidden must not be required, or the form can
   * never be valid for a reason the seller cannot see.
   */
  private syncVehicleRules(): void {
    const { makeText, model, modelText, power } = this.vehicle.controls;
    const value = this.vehicle.getRawValue();
    const makes = this.makes() ?? [];
    const typedMake = makeIsTyped(value, makes);
    const typedModel = modelIsTyped(value, makes);

    makeText.setValidators(
      typedMake ? [requiredText(), Validators.maxLength(LIMITS.makeText)] : [Validators.maxLength(LIMITS.makeText)],
    );
    model.setValidators(typedMake ? [] : [requiredText()]);
    modelText.setValidators(
      typedModel ? [requiredText(), Validators.maxLength(LIMITS.modelText)] : [Validators.maxLength(LIMITS.modelText)],
    );
    // Required, except on a listing saved before the field existed and still without one.
    power.setValidators(powerValidators(!this.powerMissing()));
    for (const control of [makeText, model, modelText, power]) {
      control.updateValueAndValidity({ emitEvent: false });
    }
    // With no catalogue at all there is no list to choose from: the make is typed.
    if (makes.length === 0 && this.makes() !== null && value.make !== OTHER) {
      this.vehicle.controls.make.setValue(OTHER, { emitEvent: false });
    }
  }
}

/** The values the API offers that this build also knows, with their French labels. */
function options(
  offered: readonly string[] | undefined,
  known: Record<string, string>,
  labels: Record<string, string>,
): SelectOption[] {
  const supported = new Set(Object.values(known));
  // Until the reference data arrives, every value the build knows: a required select
  // with no options would block the form on a slow connection.
  const values = offered?.length ? offered.filter((value) => supported.has(value)) : [...supported];
  return values.map((value) => ({ value, label: labelOf(labels, value) }));
}
