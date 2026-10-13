import { FormControl, FormGroup, ValidatorFn, Validators } from '@angular/forms';

import {
  BodyType,
  CreateListingRequest,
  FuelType,
  Gearbox,
  ListingResponse,
  Make,
  Model,
  UpdateListingRequest,
  VehicleCondition,
} from '../../core/api';
import { parseWholeNumber, requiredText, wholeNumber } from '../../shared/forms/validation';

/**
 * The listing form: its controls, their rules, and the translation to and from the API.
 *
 * <p>Kept apart from the components on purpose. The awkward part of this screen is not
 * the layout, it is the mapping — a make that is either a catalogue id or free text and
 * never half of each, a price typed in euros and stored in cents, an optional field that
 * must be sent as "" to clear and omitted to leave alone. All of that is here, as pure
 * functions, where it can be tested without rendering anything.
 */

/** The value of the make or model select that means "not in the list — I'll type it". */
export const OTHER = '__autre__';

/** Mirrors of the server's limits — `CreateListingRequest` on the backend. */
export const LIMITS = {
  title: 140,
  description: 10_000,
  makeText: 80,
  modelText: 120,
  version: 160,
  postalCode: 12,
  city: 120,
  maxPrice: 10_000_000,
  maxMileage: 3_000_000,
  minYear: 1900,
} as const;

const text = (validators: ValidatorFn[] = []) =>
  new FormControl('', { nonNullable: true, validators });

/** Step 1 — the vehicle. */
export function vehicleForm(now = new Date()) {
  return new FormGroup({
    /** A catalogue make id, {@link OTHER}, or empty. */
    make: text([requiredText()]),
    makeText: text([Validators.maxLength(LIMITS.makeText)]),
    /** A catalogue model id, {@link OTHER}, or empty. */
    model: text(),
    modelText: text([Validators.maxLength(LIMITS.modelText)]),
    version: text([Validators.maxLength(LIMITS.version)]),
    // Next year is allowed: a car registered in December is often sold as next year's model.
    year: text([requiredText(), wholeNumber(LIMITS.minYear, now.getFullYear() + 1)]),
    mileage: text([requiredText(), wholeNumber(0, LIMITS.maxMileage)]),
    fuel: text([requiredText()]),
    gearbox: text([requiredText()]),
    body: text(),
    condition: text([requiredText()]),
  });
}

/** Step 2 — the offer: what it costs, what the seller says about it, where it is. */
export function offerForm() {
  return new FormGroup({
    title: text([requiredText(), Validators.maxLength(LIMITS.title)]),
    price: text([requiredText(), wholeNumber(0, LIMITS.maxPrice)]),
    negotiable: new FormControl(false, { nonNullable: true }),
    description: text([Validators.maxLength(LIMITS.description)]),
    postalCode: text([requiredText(), Validators.maxLength(LIMITS.postalCode)]),
    city: text([requiredText(), Validators.maxLength(LIMITS.city)]),
  });
}

export type VehicleForm = ReturnType<typeof vehicleForm>;
export type OfferForm = ReturnType<typeof offerForm>;
export type VehicleValue = ReturnType<VehicleForm['getRawValue']>;
export type OfferValue = ReturnType<OfferForm['getRawValue']>;

/** Whether the make is being typed rather than chosen. */
export function makeIsTyped(value: Pick<VehicleValue, 'make'>, makes: readonly Make[]): boolean {
  return makes.length === 0 || value.make === OTHER;
}

/** Whether the model is being typed: always, once the make is. */
export function modelIsTyped(value: Pick<VehicleValue, 'make' | 'model'>, makes: readonly Make[]): boolean {
  return makeIsTyped(value, makes) || value.model === OTHER;
}

/** The make and model as the API wants them: both from the catalogue, or both as text. */
type VehicleIdentity =
  | { makeId: string; modelId: string }
  | { makeText: string; modelText: string };

/**
 * Resolves the make and model to ids or to text — never one of each.
 *
 * <p>The API refuses a catalogue make with a typed model ("modelId is required when makeId
 * is given, and vice versa"): a listing is identified against the catalogue entirely or
 * not at all. So when the seller picks "Peugeot" from the list and then types a model that
 * is not in it, the make is sent as the text "Peugeot" too. The seller sees no difference;
 * the listing is simply recorded as free text until the catalogue knows that model.
 */
export function vehicleIdentity(
  value: VehicleValue,
  makes: readonly Make[],
  models: readonly Model[],
): VehicleIdentity {
  const make = makes.find((candidate) => candidate.id === value.make);
  const model = models.find((candidate) => candidate.id === value.model);

  if (make?.id && model?.id && !modelIsTyped(value, makes)) {
    return { makeId: make.id, modelId: model.id };
  }
  return {
    // What is on screen wins: a stale selection behind a typed field is not what the seller sees.
    makeText: (makeIsTyped(value, makes) ? value.makeText : (make?.name ?? value.makeText)).trim(),
    modelText: (modelIsTyped(value, makes) ? value.modelText : (model?.name ?? value.modelText)).trim(),
  };
}

/** "Peugeot 308 BlueHDi 130" — the title a seller would most likely have typed anyway. */
export function suggestedTitle(value: VehicleValue, makes: readonly Make[], models: readonly Model[]): string {
  const identity = vehicleIdentity(value, makes, models);
  const make = 'makeId' in identity ? makes.find((m) => m.id === identity.makeId)?.name : identity.makeText;
  const model = 'modelId' in identity ? models.find((m) => m.id === identity.modelId)?.name : identity.modelText;
  return [make, model, value.version.trim()].filter(Boolean).join(' ').slice(0, LIMITS.title);
}

/** What the listing is sold in and from: the seller's market. */
export interface Market {
  readonly countryCode: string;
  readonly currencyCode: string;
  /** Decimal places of the currency — never assumed to be 2. */
  readonly minorUnit: number;
}

/** Step 1's fields, as the API takes them. Shared by create and update. */
function vehiclePayload(value: VehicleValue, makes: readonly Make[], models: readonly Model[]) {
  return {
    ...vehicleIdentity(value, makes, models),
    versionText: value.version.trim(),
    firstRegistrationYear: parseWholeNumber(value.year)!,
    mileageKm: parseWholeNumber(value.mileage)!,
    fuelType: value.fuel as FuelType,
    gearbox: value.gearbox as Gearbox,
    bodyType: (value.body || undefined) as BodyType | undefined,
    condition: value.condition as VehicleCondition,
  };
}

/** Step 2's fields. The price is typed in whole units and stored in minor ones. */
function offerPayload(value: OfferValue, market: Market) {
  return {
    title: value.title.trim(),
    // Sent even when empty: on an update a blank description clears it, which is what
    // deleting the text means. Omitted, the old description would silently survive.
    description: value.description.trim(),
    priceAmount: parseWholeNumber(value.price)! * 10 ** market.minorUnit,
    currencyCode: market.currencyCode,
    priceNegotiable: value.negotiable,
    postalCode: value.postalCode.trim(),
    city: value.city.trim(),
    countryCode: market.countryCode,
  };
}

/** Everything needed to create the draft. Both forms must be valid. */
export function toCreateRequest(
  vehicle: VehicleValue,
  offer: OfferValue,
  market: Market,
  makes: readonly Make[],
  models: readonly Model[],
): CreateListingRequest {
  return { ...vehiclePayload(vehicle, makes, models), ...offerPayload(offer, market) };
}

/** Step 1 alone, for a listing that already exists. */
export function toVehicleUpdate(
  value: VehicleValue,
  makes: readonly Make[],
  models: readonly Model[],
): UpdateListingRequest {
  return vehiclePayload(value, makes, models);
}

/** Step 2 alone, for a listing that already exists. */
export function toOfferUpdate(value: OfferValue, market: Market): UpdateListingRequest {
  return offerPayload(value, market);
}

/**
 * Fills step 1 from an existing listing.
 *
 * <p>A listing recorded against the catalogue selects its make and model in the lists; one
 * recorded as free text opens the typed fields with its words in them. Either way the
 * seller finds the form as they left it.
 */
export function vehicleValueOf(listing: ListingResponse): VehicleValue {
  const vehicle = listing.vehicle ?? {};
  const catalogued = !!vehicle.makeId && !!vehicle.modelId;
  return {
    make: catalogued ? vehicle.makeId! : OTHER,
    makeText: catalogued ? '' : (vehicle.make ?? ''),
    model: catalogued ? vehicle.modelId! : OTHER,
    modelText: catalogued ? '' : (vehicle.model ?? ''),
    version: vehicle.version ?? '',
    year: vehicle.firstRegistrationYear ? String(vehicle.firstRegistrationYear) : '',
    // Zero is a mileage; only "not stated" is empty.
    mileage: typeof listing.mileageKm === 'number' ? String(listing.mileageKm) : '',
    fuel: vehicle.fuelType ?? '',
    gearbox: vehicle.gearbox ?? '',
    body: vehicle.bodyType ?? '',
    condition: listing.condition ?? '',
  };
}

/** Fills step 2 from an existing listing. */
export function offerValueOf(listing: ListingResponse, minorUnit: number): OfferValue {
  return {
    title: listing.title ?? '',
    price: typeof listing.priceAmount === 'number' ? String(Math.round(listing.priceAmount / 10 ** minorUnit)) : '',
    negotiable: listing.priceNegotiable ?? false,
    description: listing.description ?? '',
    postalCode: listing.location?.postalCode ?? '',
    city: listing.location?.city ?? '',
  };
}
