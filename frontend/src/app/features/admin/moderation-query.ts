import { Params } from '@angular/router';

import { ModerationListRequestParams, ModerationSort, ModerationView, OfferType, UserRole } from '../../core/api';

/**
 * What a moderation list is narrowed to, as the page holds it. Everything here is in the URL, in
 * French, so a filtered list can be reloaded, bookmarked and sent to a colleague:
 *
 * | Query        | Values                                                    |
 * |--------------|-----------------------------------------------------------|
 * | `q`          | the words searched                                        |
 * | `marque`     | a make, by name                                           |
 * | `vendeur`    | `particulier`, `pro`                                      |
 * | `etat`       | `endommage`, `sain`                                       |
 * | `offre`      | `vente`, `leasing`                                        |
 * | `origine`    | `reelle`, `demo`                                          |
 * | `prix-min`   | whole euros                                               |
 * | `prix-max`   | whole euros                                               |
 * | `tri`        | `anciens`, `recents`, `prix-croissant`, `prix-decroissant` |
 *
 * Anything else, or a value this page does not know, reads as not set: an old or mistyped link
 * opens the list, not an error.
 */
export interface ModerationQuery {
  readonly q: string;
  readonly make: string;
  readonly sellerRole: UserRole | null;
  readonly damaged: boolean | null;
  readonly offer: OfferType | null;
  readonly demo: boolean | null;
  /** Whole euros. */
  readonly priceMin: number | null;
  readonly priceMax: number | null;
  /** Null for the list's own order. */
  readonly sort: ModerationSort | null;
}

export const EMPTY_QUERY: ModerationQuery = {
  q: '',
  make: '',
  sellerRole: null,
  damaged: null,
  offer: null,
  demo: null,
  priceMin: null,
  priceMax: null,
  sort: null,
};

/** A choice, by the word it has in the URL and the label it has on screen. */
export interface Choice<T> {
  readonly slug: string;
  readonly value: T;
  readonly label: string;
}

export const SELLER_CHOICES: readonly Choice<UserRole>[] = [
  { slug: 'particulier', value: UserRole.PARTICULIER, label: 'Particulier' },
  { slug: 'pro', value: UserRole.PROFESSIONNEL, label: 'Professionnel' },
];

export const DAMAGE_CHOICES: readonly Choice<boolean>[] = [
  { slug: 'endommage', value: true, label: 'Endommagé' },
  { slug: 'sain', value: false, label: 'Non endommagé' },
];

export const OFFER_CHOICES: readonly Choice<OfferType>[] = [
  { slug: 'vente', value: OfferType.SALE, label: 'Vente' },
  { slug: 'leasing', value: OfferType.LEASING, label: 'Leasing' },
];

export const ORIGIN_CHOICES: readonly Choice<boolean>[] = [
  { slug: 'reelle', value: false, label: 'Réelles' },
  { slug: 'demo', value: true, label: 'Démo' },
];

export const SORT_CHOICES: readonly Choice<ModerationSort>[] = [
  { slug: 'anciens', value: ModerationSort.OLDEST, label: 'Plus anciennes d’abord' },
  { slug: 'recents', value: ModerationSort.NEWEST, label: 'Plus récentes d’abord' },
  { slug: 'prix-croissant', value: ModerationSort.PRICE_ASC, label: 'Prix croissant' },
  { slug: 'prix-decroissant', value: ModerationSort.PRICE_DESC, label: 'Prix décroissant' },
];

/** The queue is worked from the front; the two other lists show what changed last. */
export function defaultSort(view: ModerationView): ModerationSort {
  return view === ModerationView.PENDING ? ModerationSort.OLDEST : ModerationSort.NEWEST;
}

/** The query a URL describes. */
export function queryFromParams(params: Params): ModerationQuery {
  return {
    q: text(params['q'], 100),
    make: text(params['marque'], 80),
    sellerRole: valueOf(SELLER_CHOICES, params['vendeur']),
    damaged: valueOf(DAMAGE_CHOICES, params['etat']),
    offer: valueOf(OFFER_CHOICES, params['offre']),
    demo: valueOf(ORIGIN_CHOICES, params['origine']),
    priceMin: euros(params['prix-min']),
    priceMax: euros(params['prix-max']),
    sort: valueOf(SORT_CHOICES, params['tri']),
  };
}

/**
 * The URL's part of a query, every key present so that a merge clears what is unset. The list's
 * own order is left out, so the plain address stays plain.
 */
export function paramsFromQuery(query: ModerationQuery, view: ModerationView): Params {
  return {
    q: query.q.trim() || null,
    marque: query.make.trim() || null,
    vendeur: slugOf(SELLER_CHOICES, query.sellerRole),
    etat: slugOf(DAMAGE_CHOICES, query.damaged),
    offre: slugOf(OFFER_CHOICES, query.offer),
    origine: slugOf(ORIGIN_CHOICES, query.demo),
    'prix-min': query.priceMin,
    'prix-max': query.priceMax,
    tri: query.sort === null || query.sort === defaultSort(view) ? null : slugOf(SORT_CHOICES, query.sort),
  };
}

/** The API's parameters for a query. Prices go in minor units; a range given backwards is turned round. */
export function requestFromQuery(
  query: ModerationQuery,
  view: ModerationView,
  minorUnit: number,
): Omit<ModerationListRequestParams, 'page' | 'size'> {
  const scale = 10 ** minorUnit;
  let [low, high] = [query.priceMin, query.priceMax];
  if (low !== null && high !== null && low > high) {
    [low, high] = [high, low];
  }
  return {
    view,
    q: query.q.trim() || undefined,
    make: query.make.trim() || undefined,
    sellerRole: query.sellerRole ?? undefined,
    damaged: query.damaged ?? undefined,
    offer: query.offer ?? undefined,
    demo: query.demo ?? undefined,
    priceMin: low === null ? undefined : low * scale,
    priceMax: high === null ? undefined : high * scale,
    sort: query.sort ?? defaultSort(view),
  };
}

/** How many filters narrow the list, the search and the order not counted. */
export function activeFilters(query: ModerationQuery): number {
  return [
    query.make.trim(),
    query.sellerRole,
    query.damaged,
    query.offer,
    query.demo,
    query.priceMin,
    query.priceMax,
  ].filter((value) => value !== null && value !== '').length;
}

/** Whether anything at all narrows the list: a search or a filter. */
export function isNarrowed(query: ModerationQuery): boolean {
  return query.q.trim() !== '' || activeFilters(query) > 0;
}

function text(value: unknown, max: number): string {
  return typeof value === 'string' ? value.slice(0, max) : '';
}

function valueOf<T>(choices: readonly Choice<T>[], slug: unknown): T | null {
  return choices.find((choice) => choice.slug === slug)?.value ?? null;
}

function slugOf<T>(choices: readonly Choice<T>[], value: T | null): string | null {
  return value === null ? null : (choices.find((choice) => choice.value === value)?.slug ?? null);
}

/** Whole, non-negative euros, below what the API accepts as a price. */
function euros(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d{1,8}$/.test(value)) {
    return null;
  }
  return Number(value);
}
