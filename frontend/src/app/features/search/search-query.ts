import { ParamMap, Params } from '@angular/router';

import {
  BodyType,
  DamageFilter,
  FuelType,
  Gearbox,
  LeasingType,
  ListingSearchCriteria,
  OfferType,
  PriceLevel,
  SearchSort,
  VehicleCondition,
} from '../../core/api';

/**
 * A search, as the address bar describes it.
 *
 * <p><b>The URL is the only place this state lives.</b> The page holds no copy: a filter
 * is changed by navigating, and what is displayed is whatever the current URL parses to.
 * That is what makes a search shareable, makes the back button step through the filters
 * one at a time, and makes a reload land exactly where the user was — all for free, and
 * with no way for the screen and the address to disagree.
 *
 * <p>Prices are in whole currency units here, because this is what a person types and
 * what belongs in a link; the conversion to the API's minor units happens once, in
 * {@link toCriteria}.
 */
export interface SearchQuery {
  /** Free text from the search box: every word must match the title, make, model or version. */
  readonly text: string | null;
  /** A catalogue slug when the make is known, otherwise the text the user typed. */
  readonly make: string | null;
  readonly model: string | null;
  readonly yearMin: number | null;
  readonly yearMax: number | null;
  readonly priceMin: number | null;
  readonly priceMax: number | null;
  readonly mileageMin: number | null;
  readonly mileageMax: number | null;
  readonly fuel: readonly FuelType[];
  readonly gearbox: readonly Gearbox[];
  readonly body: readonly BodyType[];
  readonly condition: readonly VehicleCondition[];
  /** Damaged vehicles: out of a search unless it asks for them. */
  readonly damage: DamageFilter;
  readonly location: string | null;
  /** Only listings the price indicator calls a good deal. */
  readonly goodDeals: boolean;
  /** Sales or leasing offers: a search is for one or the other, never both. */
  readonly offer: OfferType;
  readonly leasingType: readonly LeasingType[];
  /** In whole currency units, like the prices above. */
  readonly monthlyMin: number | null;
  readonly monthlyMax: number | null;
  readonly durationMin: number | null;
  readonly durationMax: number | null;
  readonly allowanceMin: number | null;
  /** "Crit'Air 2 ou mieux": the highest class wanted, 0 to 5. The API estimates each listing's. */
  readonly critAirMax: number | null;
  readonly sort: SearchSort;
}

export const DEFAULT_SORT = SearchSort.NEWEST;

export const EMPTY_QUERY: SearchQuery = {
  text: null,
  make: null,
  model: null,
  yearMin: null,
  yearMax: null,
  priceMin: null,
  priceMax: null,
  mileageMin: null,
  mileageMax: null,
  fuel: [],
  gearbox: [],
  body: [],
  condition: [],
  damage: DamageFilter.UNDAMAGED,
  location: null,
  goodDeals: false,
  offer: OfferType.SALE,
  leasingType: [],
  monthlyMin: null,
  monthlyMax: null,
  durationMin: null,
  durationMax: null,
  allowanceMin: null,
  critAirMax: null,
  sort: DEFAULT_SORT,
};

/**
 * The query-string keys. French, like every other part of a URL the user sees.
 *
 * <p>A contract once a single link has been shared: renaming one breaks every bookmark
 * that uses it. Add, do not rename.
 */
export const PARAM = {
  // The one key that is not French: `q` is what every search box on the web calls it,
  // and it is what a person editing the URL by hand will try first.
  text: 'q',
  make: 'marque',
  model: 'modele',
  yearMin: 'annee_min',
  yearMax: 'annee_max',
  priceMin: 'prix_min',
  priceMax: 'prix_max',
  mileageMin: 'km_min',
  mileageMax: 'km_max',
  fuel: 'carburant',
  gearbox: 'boite',
  body: 'carrosserie',
  condition: 'etat',
  damage: 'etat_vehicule',
  location: 'lieu',
  goodDeals: 'bonnes_affaires',
  offer: 'offre',
  leasingType: 'type_location',
  monthlyMin: 'mensualite_min',
  monthlyMax: 'mensualite_max',
  durationMin: 'duree_min',
  durationMax: 'duree_max',
  allowanceMin: 'km_an_min',
  sort: 'tri',
  critAirMax: 'critair_max',
} as const;

/** Sort order to its slug in the URL. The default has none: it is simply absent. */
const OFFER_LEASING_SLUG = 'location';

const SORT_SLUGS: Record<SearchSort, string> = {
  [SearchSort.NEWEST]: 'recentes',
  [SearchSort.PRICE_ASC]: 'prix-croissant',
  [SearchSort.PRICE_DESC]: 'prix-decroissant',
  [SearchSort.MILEAGE_ASC]: 'km-croissant',
  [SearchSort.MONTHLY_ASC]: 'mensualite-croissante',
  [SearchSort.MONTHLY_DESC]: 'mensualite-decroissante',
  [SearchSort.DURATION_ASC]: 'duree-croissante',
  [SearchSort.MILEAGE_ALLOWANCE_DESC]: 'km-annuel-decroissant',
};

/** The sorts that only mean something for a leasing offer. */
const LEASING_SORTS: ReadonlySet<SearchSort> = new Set([
  SearchSort.MONTHLY_ASC,
  SearchSort.MONTHLY_DESC,
  SearchSort.DURATION_ASC,
  SearchSort.MILEAGE_ALLOWANCE_DESC,
]);

/** The sorts that only mean something for a sale: a leasing offer has no price to sort by here. */
const SALE_SORTS: ReadonlySet<SearchSort> = new Set([
  SearchSort.PRICE_ASC,
  SearchSort.PRICE_DESC,
  SearchSort.MILEAGE_ASC,
]);

export function isLeasing(query: Pick<SearchQuery, 'offer'>): boolean {
  return query.offer === OfferType.LEASING;
}

/**
 * The other search, from this one: what describes the car stays, what only means something in the
 * search being left goes, so nothing lingers that the new one cannot show or undo.
 */
export function switchOffer(query: SearchQuery, offer: OfferType): SearchQuery {
  return {
    ...query,
    offer,
    priceMin: null,
    priceMax: null,
    goodDeals: false,
    damage: DamageFilter.UNDAMAGED,
    leasingType: [],
    monthlyMin: null,
    monthlyMax: null,
    durationMin: null,
    durationMax: null,
    allowanceMin: null,
    sort: DEFAULT_SORT,
  };
}

/**
 * Whether "bonnes affaires" can find anything in this search. A damaged vehicle never gets a price
 * verdict, so among damaged vehicles alone it never can, and nor does a leasing offer. A rule of
 * the search, so a link or a change of another filter cannot bring them together and show an empty
 * page for no visible reason. The server's interpreter holds the same rule.
 */
export function allowsGoodDeals(query: Pick<SearchQuery, 'damage' | 'offer'>): boolean {
  return query.damage !== DamageFilter.DAMAGED_ONLY && query.offer !== OfferType.LEASING;
}

/** "État du véhicule" to its slug in the URL. The default has none: it is simply absent. */
const DAMAGE_SLUGS: Partial<Record<DamageFilter, string>> = {
  [DamageFilter.DAMAGED_ONLY]: 'endommages',
  [DamageFilter.ALL]: 'tous',
};

/** The bounds the API validates against — `ListingSearchCriteria` on the backend. */
const YEAR_RANGE = [1900, 2100] as const;
const MAX_PRICE = 10_000_000;
const MAX_MILEAGE = 3_000_000;
const MAX_MONTHLY = 1_000_000;
const MAX_LEASE_MONTHS = 120;
const MAX_ALLOWANCE = 200_000;
const MAX_TEXT = 80;
/** `ListingSearchCriteria.q` is `@Size(max = 120)`. */
const MAX_QUERY = 120;

/**
 * Reads a search out of the query string.
 *
 * <p><b>Everything here is untrusted.</b> The address bar is user input — hand-edited,
 * truncated by a mail client, or written by somebody hoping to break the page. So nothing
 * is passed through: numbers are parsed and bounded, enum values are checked against the
 * generated enums, and anything unrecognised is dropped rather than forwarded to the API,
 * which would answer 400 and leave the user looking at an error for a link they clicked.
 * A bad parameter costs that one filter; it never costs the page.
 */
export function parseQuery(params: ParamMap): SearchQuery {
  // "location", and nothing else: anything unknown is the default search.
  const offer = params.get(PARAM.offer) === OFFER_LEASING_SLUG ? OfferType.LEASING : OfferType.SALE;
  const leasing = offer === OfferType.LEASING;
  // Nobody leases a damaged car, so a link asking for both asks for the lease.
  const damage = leasing ? DamageFilter.UNDAMAGED : damageFromParams(params);
  const [monthlyMin, monthlyMax] = ordered(
    integer(params.get(PARAM.monthlyMin), 0, MAX_MONTHLY),
    integer(params.get(PARAM.monthlyMax), 0, MAX_MONTHLY),
  );
  const [durationMin, durationMax] = ordered(
    integer(params.get(PARAM.durationMin), 0, MAX_LEASE_MONTHS),
    integer(params.get(PARAM.durationMax), 0, MAX_LEASE_MONTHS),
  );
  const [yearMin, yearMax] = ordered(
    integer(params.get(PARAM.yearMin), YEAR_RANGE[0], YEAR_RANGE[1]),
    integer(params.get(PARAM.yearMax), YEAR_RANGE[0], YEAR_RANGE[1]),
  );
  const [priceMin, priceMax] = ordered(
    integer(params.get(PARAM.priceMin), 0, MAX_PRICE),
    integer(params.get(PARAM.priceMax), 0, MAX_PRICE),
  );
  const [mileageMin, mileageMax] = ordered(
    integer(params.get(PARAM.mileageMin), 0, MAX_MILEAGE),
    integer(params.get(PARAM.mileageMax), 0, MAX_MILEAGE),
  );

  const make = text(params.get(PARAM.make));

  return {
    text: text(params.get(PARAM.text), MAX_QUERY),
    make,
    // A model means nothing without its make, and keeping one would send the API a
    // filter the sidebar cannot show.
    model: make ? text(params.get(PARAM.model)) : null,
    yearMin,
    yearMax,
    priceMin: leasing ? null : priceMin,
    priceMax: leasing ? null : priceMax,
    mileageMin,
    mileageMax,
    fuel: enumList(params.get(PARAM.fuel), FuelType),
    gearbox: enumList(params.get(PARAM.gearbox), Gearbox),
    body: enumList(params.get(PARAM.body), BodyType),
    condition: enumList(params.get(PARAM.condition), VehicleCondition),
    damage,
    location: text(params.get(PARAM.location)),
    // "1" and nothing else: a flag in a link is on or absent.
    goodDeals: params.get(PARAM.goodDeals) === '1' && allowsGoodDeals({ damage, offer }),
    offer,
    leasingType: leasing ? enumList(params.get(PARAM.leasingType), LeasingType) : [],
    monthlyMin: leasing ? monthlyMin : null,
    monthlyMax: leasing ? monthlyMax : null,
    durationMin: leasing ? durationMin : null,
    durationMax: leasing ? durationMax : null,
    allowanceMin: leasing ? integer(params.get(PARAM.allowanceMin), 0, MAX_ALLOWANCE) : null,
    critAirMax: integer(params.get(PARAM.critAirMax), 0, 5),
    sort: sortFor(sortFromSlug(params.get(PARAM.sort)), offer),
  };
}

/**
 * Writes a search as query parameters.
 *
 * <p>Canonical: defaults are omitted and list values are sorted, so the same search
 * always produces the same URL whatever order the filters were clicked in. Two links to
 * one search being different strings is what makes "is this the page I was on?" hard to
 * answer, for people and for caches alike.
 */
export function toQueryParams(query: SearchQuery): Params {
  const params: Params = {};
  const put = (key: string, value: string | number | null) => {
    if (value !== null && value !== '') {
      params[key] = String(value);
    }
  };

  put(PARAM.text, query.text);
  put(PARAM.make, query.make);
  put(PARAM.model, query.make ? query.model : null);
  put(PARAM.yearMin, query.yearMin);
  put(PARAM.yearMax, query.yearMax);
  const leasing = isLeasing(query);
  put(PARAM.priceMin, leasing ? null : query.priceMin);
  put(PARAM.priceMax, leasing ? null : query.priceMax);
  put(PARAM.mileageMin, query.mileageMin);
  put(PARAM.mileageMax, query.mileageMax);
  put(PARAM.fuel, enumSlugs(query.fuel));
  put(PARAM.gearbox, enumSlugs(query.gearbox));
  put(PARAM.body, enumSlugs(query.body));
  put(PARAM.condition, enumSlugs(query.condition));
  put(PARAM.damage, leasing ? null : (DAMAGE_SLUGS[query.damage] ?? null));
  put(PARAM.location, query.location);
  put(PARAM.goodDeals, query.goodDeals && allowsGoodDeals(query) ? '1' : null);
  if (leasing) {
    put(PARAM.offer, OFFER_LEASING_SLUG);
    put(PARAM.leasingType, enumSlugs(query.leasingType));
    put(PARAM.monthlyMin, query.monthlyMin);
    put(PARAM.monthlyMax, query.monthlyMax);
    put(PARAM.durationMin, query.durationMin);
    put(PARAM.durationMax, query.durationMax);
    put(PARAM.allowanceMin, query.allowanceMin);
  }
  put(PARAM.critAirMax, query.critAirMax);
  const sort = sortFor(query.sort, query.offer);
  put(PARAM.sort, sort === DEFAULT_SORT ? null : SORT_SLUGS[sort]);

  return params;
}

/** What the make and model in a query turned out to be, once the catalogue was consulted. */
export interface ResolvedVehicle {
  readonly makeId?: string;
  readonly makeText?: string;
  readonly modelId?: string;
  readonly modelText?: string;
}

/**
 * Turns a query into what the API takes.
 *
 * @param minorUnit decimal places of the currency — `Country.currencyMinorUnit`. Prices
 *     in the URL are whole units; the API compares minor units. Multiplying by a
 *     hard-coded 100 would be right for the euro and wrong by a factor of a hundred for
 *     a currency without cents.
 */
export function toCriteria(
  query: SearchQuery,
  vehicle: ResolvedVehicle,
  currency: string,
  minorUnit: number,
): ListingSearchCriteria {
  const scale = 10 ** minorUnit;
  const leasing = isLeasing(query);
  const hasPrice = !leasing && (query.priceMin !== null || query.priceMax !== null);
  const hasMonthly = leasing && (query.monthlyMin !== null || query.monthlyMax !== null);

  return compact({
    q: query.text ?? undefined,
    makeId: vehicle.makeId,
    make: vehicle.makeText,
    modelId: vehicle.modelId,
    model: vehicle.modelText,
    yearFrom: query.yearMin ?? undefined,
    yearTo: query.yearMax ?? undefined,
    priceMin: leasing || query.priceMin === null ? undefined : query.priceMin * scale,
    priceMax: leasing || query.priceMax === null ? undefined : query.priceMax * scale,
    // Only alongside a price bound. A number means nothing without its currency —
    // "under 15 000" must not match a listing priced at 15 000 of something else — but
    // with no price filter there is no reason to hide other currencies at all.
    currency: hasPrice || hasMonthly ? currency : undefined,
    mileageMin: query.mileageMin ?? undefined,
    mileageMax: query.mileageMax ?? undefined,
    fuelType: query.fuel.length ? [...query.fuel] : undefined,
    gearbox: query.gearbox.length ? [...query.gearbox] : undefined,
    bodyType: query.body.length ? [...query.body] : undefined,
    condition: query.condition.length ? [...query.condition] : undefined,
    // Left out for the default, which is also the API's.
    damage: leasing || query.damage === DamageFilter.UNDAMAGED ? undefined : query.damage,
    offer: leasing ? OfferType.LEASING : undefined,
    leasingType: leasing && query.leasingType.length ? [...query.leasingType] : undefined,
    monthlyMin: !leasing || query.monthlyMin === null ? undefined : query.monthlyMin * scale,
    monthlyMax: !leasing || query.monthlyMax === null ? undefined : query.monthlyMax * scale,
    durationMin: leasing ? (query.durationMin ?? undefined) : undefined,
    durationMax: leasing ? (query.durationMax ?? undefined) : undefined,
    mileageAllowanceMin: leasing ? (query.allowanceMin ?? undefined) : undefined,
    location: query.location ?? undefined,
    priceLevel: query.goodDeals ? [PriceLevel.GOOD_DEAL] : undefined,
    critAirMax: query.critAirMax ?? undefined,
  });
}

/** How many filters are set — the number on the "Filtres" button. Sort is not a filter. */
export function activeFilterCount(query: SearchQuery): number {
  return [
    query.text,
    query.make,
    query.model,
    query.yearMin ?? query.yearMax,
    query.priceMin ?? query.priceMax,
    query.mileageMin ?? query.mileageMax,
    query.fuel.length || null,
    query.gearbox.length || null,
    query.body.length || null,
    query.condition.length || null,
    query.damage === DamageFilter.UNDAMAGED ? null : query.damage,
    query.location,
    query.goodDeals || null,
    query.leasingType.length || null,
    query.monthlyMin ?? query.monthlyMax,
    query.durationMin ?? query.durationMax,
    query.allowanceMin,
    query.critAirMax,
  ].filter((value) => value !== null && value !== undefined).length;
}

/** The same search with every filter cleared and the sort order kept. */
export function withoutFilters(query: SearchQuery): SearchQuery {
  return { ...EMPTY_QUERY, offer: query.offer, sort: query.sort };
}

// --- parsing helpers ------------------------------------------------------

function integer(raw: string | null, min: number, max: number): number | null {
  if (raw === null || !/^\d{1,9}$/.test(raw.trim())) {
    // Digits only: no sign, no decimal point, no exponent. `Number('1e9')` and
    // `Number(' 12 ')` both succeed, and neither is something a filter should accept.
    return null;
  }
  const value = Number(raw.trim());
  return value >= min && value <= max ? value : null;
}

/** A reversed range is put the right way round rather than returning nothing. */
function ordered(min: number | null, max: number | null): [number | null, number | null] {
  return min !== null && max !== null && min > max ? [max, min] : [min, max];
}

function text(raw: string | null, max = MAX_TEXT): string | null {
  // Runs of whitespace collapsed, so "golf   tdi" and "golf tdi" are one URL.
  const value = raw?.trim().replace(/\s+/g, ' ') ?? '';
  return value.length > 0 ? value.slice(0, max) : null;
}

/** `plugin-hybrid,diesel` to `[DIESEL, PLUGIN_HYBRID]`: known values only, once each. */
/**
 * The filter, or what an older link asked for: "damaged" used to be a value of `etat`, and a link
 * once shared has to keep meaning the same thing.
 */
function damageFromParams(params: ParamMap): DamageFilter {
  const slug = params.get(PARAM.damage);
  const chosen = (Object.keys(DAMAGE_SLUGS) as DamageFilter[]).find((filter) => DAMAGE_SLUGS[filter] === slug);
  if (chosen) {
    return chosen;
  }
  const legacy = (params.get(PARAM.condition) ?? '').toUpperCase().split(',');
  return legacy.includes('DAMAGED') ? DamageFilter.DAMAGED_ONLY : DamageFilter.UNDAMAGED;
}

function enumList<T extends string>(raw: string | null, type: Record<string, T>): T[] {
  if (!raw) {
    return [];
  }
  const known = new Set<string>(Object.values(type));
  const found = new Set<T>();
  for (const part of raw.split(',')) {
    const candidate = part.trim().toUpperCase().replace(/-/g, '_');
    if (known.has(candidate)) {
      found.add(candidate as T);
    }
  }
  return [...found].sort();
}

function enumSlugs(values: readonly string[]): string | null {
  if (values.length === 0) {
    return null;
  }
  return [...values]
    .sort()
    .map((value) => value.toLowerCase().replace(/_/g, '-'))
    .join(',');
}

/** A sort that means nothing in this search is the default one, rather than an error from the API. */
function sortFor(sort: SearchSort, offer: OfferType): SearchSort {
  const offTopic = offer === OfferType.LEASING ? SALE_SORTS : LEASING_SORTS;
  return offTopic.has(sort) ? DEFAULT_SORT : sort;
}

function sortFromSlug(raw: string | null): SearchSort {
  const entry = Object.entries(SORT_SLUGS).find(([, slug]) => slug === raw);
  return entry ? (entry[0] as SearchSort) : DEFAULT_SORT;
}

function compact<T extends object>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => entry !== undefined),
  ) as T;
}
