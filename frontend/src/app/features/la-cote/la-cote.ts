import { ParamMap, Params } from '@angular/router';

import {
  FuelType,
  Gearbox,
  Valuation,
  ValuationBasis,
  ValuationEngine,
  ValuationScope,
  ValuationWindow,
} from '../../core/api';
import { formatIntegerFr } from '../../shared/format/number';
import { FUEL_LABELS, labelOf } from '../../shared/listing/labels.fr';
import { EMPTY_QUERY, toQueryParams } from '../search/search-query';

/**
 * La Cote's state, as it is in the address: what the visitor described, and which
 * comparables the estimate was asked for. A valuation is a link that can be sent to someone.
 *
 * <p>The make and model are catalogue slugs, as in the search's addresses; the fuel and the
 * gearbox are their codes in lower case. The version is carried for "Vendre ma voiture"
 * only: it changes nothing in the estimate.
 */
export interface CoteQuery {
  readonly make: string | null;
  readonly model: string | null;
  readonly fuel: FuelType | null;
  readonly power: number | null;
  readonly year: number | null;
  readonly mileage: number | null;
  readonly gearbox: Gearbox | null;
  readonly version: string | null;
  readonly scope: ValuationScope;
}

/** Query-string keys. A contract once one link is shared: add, do not rename. */
export const COTE_PARAM = {
  make: 'marque',
  model: 'modele',
  fuel: 'carburant',
  power: 'puissance',
  year: 'annee',
  mileage: 'km',
  gearbox: 'boite',
  version: 'version',
  scope: 'elargir',
} as const;

/** The loosenings, in the address. The price indicator's own rule has none. */
const SCOPE_SLUGS: Record<ValuationScope, string | null> = {
  [ValuationScope.EXACT]: null,
  [ValuationScope.ALL_ENGINES]: 'motorisations',
  [ValuationScope.WIDER_YEARS]: 'annees',
  [ValuationScope.ANY_MILEAGE]: 'kilometrage',
};

export const MIN_YEAR = 1990;
export const MAX_MILEAGE = 3_000_000;
export const MAX_POWER = 2000;
const MAX_VERSION = 160;

/**
 * Reads La Cote out of the query string. Untrusted, like every address: numbers are parsed
 * and bounded, codes are checked against the generated enums, and anything else is dropped.
 */
export function parseCote(params: ParamMap, now = new Date()): CoteQuery {
  const slug = (raw: string | null) => (raw && /^[a-z0-9]+(-[a-z0-9]+)*$/.test(raw) ? raw : null);
  const scopeSlug = params.get(COTE_PARAM.scope);
  const version = params.get(COTE_PARAM.version)?.trim().slice(0, MAX_VERSION);
  return {
    make: slug(params.get(COTE_PARAM.make)),
    model: slug(params.get(COTE_PARAM.model)),
    fuel: enumOf(params.get(COTE_PARAM.fuel), FuelType),
    power: integer(params.get(COTE_PARAM.power), 1, MAX_POWER),
    year: integer(params.get(COTE_PARAM.year), MIN_YEAR, now.getFullYear() + 1),
    mileage: integer(params.get(COTE_PARAM.mileage), 0, MAX_MILEAGE),
    gearbox: enumOf(params.get(COTE_PARAM.gearbox), Gearbox),
    version: version || null,
    scope:
      (Object.entries(SCOPE_SLUGS).find(([, value]) => value !== null && value === scopeSlug)?.[0] as ValuationScope) ??
      ValuationScope.EXACT,
  };
}

/** Writes La Cote as query parameters, leaving out what is absent. */
export function coteParams(query: CoteQuery): Params {
  const params: Params = {};
  const put = (key: string, value: string | number | null) => {
    if (value !== null && value !== '') {
      params[key] = String(value);
    }
  };
  put(COTE_PARAM.make, query.make);
  put(COTE_PARAM.model, query.model);
  put(COTE_PARAM.fuel, query.fuel ? codeSlug(query.fuel) : null);
  put(COTE_PARAM.power, query.power);
  put(COTE_PARAM.year, query.year);
  put(COTE_PARAM.mileage, query.mileage);
  put(COTE_PARAM.gearbox, query.gearbox ? codeSlug(query.gearbox) : null);
  put(COTE_PARAM.version, query.version);
  put(COTE_PARAM.scope, SCOPE_SLUGS[query.scope]);
  return params;
}

/** Whether there is enough to ask for an estimate: everything but the gearbox and version. */
export function isComplete(query: CoteQuery): boolean {
  return (
    !!query.make && !!query.model && !!query.fuel && query.power !== null && query.year !== null && query.mileage !== null
  );
}

/** An engine as one value, for the select: "PETROL:100". */
export function engineKey(fuel: string, power: number): string {
  return `${fuel}:${power}`;
}

/**
 * "Essence 90 ch — TCe 90 Evolution": the fuel and power the estimate matches on, then the
 * name sellers most often give the engine, which is how most people know theirs. Short
 * enough for the form's select.
 */
export function engineLabel(engine: ValuationEngine): string {
  const example = engine.exampleVersion ? ` — ${engine.exampleVersion}` : '';
  return `${labelOf(FUEL_LABELS, engine.fuelType)} ${engine.powerHp} ch${example}`;
}

/**
 * The search for "Voir les annonces similaires": the comparables' window as search filters.
 * The model, the years and the mileage band that were searched, the fuel when the engine was
 * matched, and the gearbox the visitor gave. The search has no power filter, so it can show a
 * few more than the estimate counted.
 */
export function similarSearchParams(query: CoteQuery, window: ValuationWindow | null | undefined): Params {
  // Without a window (no answer yet), the fuel the visitor gave; with one, the window's, which
  // is absent when every engine was counted.
  const fuel = window ? (window.fuelType as FuelType | undefined) : query.fuel;
  return toQueryParams({
    ...EMPTY_QUERY,
    make: query.make,
    model: query.model,
    yearMin: window?.yearFrom ?? null,
    yearMax: window?.yearTo ?? null,
    mileageMin: window?.mileageFromKm || null,
    mileageMax: window?.mileageToKm ?? null,
    fuel: fuel ? [fuel] : [],
    gearbox: query.gearbox ? [query.gearbox] : [],
  });
}

/**
 * "Vendre ma voiture": the listing form, with the car already described. Catalogue ids, which
 * is what the form selects by; codes in capitals, as the API has them.
 */
export function sellParams(query: CoteQuery, makeId: string, modelId: string): Params {
  const params: Params = { marque: makeId, modele: modelId };
  if (query.version) params['version'] = query.version;
  if (query.year !== null) params['annee'] = String(query.year);
  if (query.mileage !== null) params['km'] = String(query.mileage);
  if (query.fuel) params['carburant'] = query.fuel;
  if (query.gearbox) params['boite'] = query.gearbox;
  return params;
}

/** What a loosening does, as the button that offers it says: "Toutes les motorisations du modèle". */
export function scopeLabel(scope: ValuationScope, year: number | null): string {
  switch (scope) {
    case ValuationScope.ALL_ENGINES:
      return 'Toutes les motorisations du modèle';
    case ValuationScope.WIDER_YEARS:
      return year === null ? 'Plus d’années' : `Années ${year - 4} à ${year + 4}`;
    case ValuationScope.ANY_MILEAGE:
      return 'Tous les kilométrages';
    default:
      return 'Même motorisation, même âge, même kilométrage';
  }
}

/** What an estimate made under a loosening no longer accounts for. */
export function scopeCaveat(scope: ValuationScope | undefined): string | null {
  switch (scope) {
    case ValuationScope.ALL_ENGINES:
      return 'Estimation élargie à toutes les motorisations du modèle : elle ne tient pas compte de la puissance ni du carburant de votre voiture.';
    case ValuationScope.WIDER_YEARS:
      return 'Estimation élargie à quatre ans d’écart : les prix des voitures plus anciennes ou plus récentes ont été ramenés à l’âge de la vôtre, ce qui la rend moins précise.';
    case ValuationScope.ANY_MILEAGE:
      return 'Estimation élargie à tous les kilométrages : les prix ont été ramenés au kilométrage de votre voiture, ce qui la rend moins précise.';
    default:
      return null;
  }
}

/**
 * The comparables' window, as one sentence: "Même motorisation (Essence, 85 à 115 ch), mise
 * en circulation de 2018 à 2022, entre 30 000 et 60 000 km."
 */
export function windowSentence(window: ValuationWindow | null | undefined): string {
  if (!window) {
    return '';
  }
  const engine = window.fuelType
    ? `Même motorisation (${labelOf(FUEL_LABELS, window.fuelType)}, ${window.powerFromHp} à ${window.powerToHp} ch)`
    : 'Toutes motorisations';
  const years = `mise en circulation de ${window.yearFrom} à ${window.yearTo}`;
  let mileage: string;
  if (window.mileageFromKm === undefined || window.mileageFromKm === null) {
    mileage = 'tous kilométrages';
  } else if (window.mileageToKm === undefined || window.mileageToKm === null) {
    mileage = `${km(window.mileageFromKm)} et plus`;
  } else if (window.mileageFromKm === 0) {
    mileage = `moins de ${km(window.mileageToKm)}`;
  } else {
    mileage = `entre ${formatIntegerFr(window.mileageFromKm)} et ${km(window.mileageToKm)}`;
  }
  return `${engine}, ${years}, ${mileage}.`;
}

/** The years of the comparables found: "2020" or "2019 à 2022". */
export function basisYears(basis: ValuationBasis): string {
  return basis.yearFrom === basis.yearTo ? `${basis.yearFrom}` : `${basis.yearFrom} à ${basis.yearTo}`;
}

/** The mileages of the comparables found: "40 000 km" or "32 000 à 58 000 km". */
export function basisMileage(basis: ValuationBasis): string {
  return basis.mileageFromKm === basis.mileageToKm
    ? km(basis.mileageFromKm ?? 0)
    : `${formatIntegerFr(basis.mileageFromKm ?? 0)} à ${km(basis.mileageToKm ?? 0)}`;
}

/** "4 annonces comparables", "1 annonce comparable". */
export function comparables(count: number): string {
  return count > 1 ? `${count} annonces comparables` : `${count} annonce comparable`;
}

/** Whether the answer has a figure. */
export function hasEstimate(valuation: Valuation | null): boolean {
  return !!valuation?.estimate?.medianAmount;
}

function km(value: number): string {
  return `${formatIntegerFr(value)} km`;
}

function codeSlug(code: string): string {
  return code.toLowerCase().replace(/_/g, '-');
}

function enumOf<T extends string>(raw: string | null, type: Record<string, T>): T | null {
  if (!raw) {
    return null;
  }
  const candidate = raw.trim().toUpperCase().replace(/-/g, '_');
  return (Object.values(type) as string[]).includes(candidate) ? (candidate as T) : null;
}

function integer(raw: string | null, min: number, max: number): number | null {
  if (raw === null || !/^\d{1,9}$/.test(raw.trim())) {
    return null;
  }
  const value = Number(raw.trim());
  return value >= min && value <= max ? value : null;
}
