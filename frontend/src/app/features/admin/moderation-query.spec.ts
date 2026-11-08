import { describe, expect, it } from 'vitest';

import { ModerationSort, ModerationView, OfferType, UserRole } from '../../core/api';
import {
  EMPTY_QUERY,
  activeFilters,
  defaultSort,
  isNarrowed,
  paramsFromQuery,
  queryFromParams,
  requestFromQuery,
} from './moderation-query';

const FULL = {
  q: 'peugeot bellevue',
  marque: 'Peugeot',
  vendeur: 'pro',
  etat: 'endommage',
  offre: 'leasing',
  origine: 'demo',
  'prix-min': '5000',
  'prix-max': '20000',
  tri: 'prix-decroissant',
};

/** What the moderation page keeps in its address, and what it asks the API for. */
describe('moderation query', () => {
  it('reads every filter from the URL, in French', () => {
    expect(queryFromParams(FULL)).toEqual({
      q: 'peugeot bellevue',
      make: 'Peugeot',
      sellerRole: UserRole.PROFESSIONNEL,
      damaged: true,
      offer: OfferType.LEASING,
      demo: true,
      priceMin: 5000,
      priceMax: 20000,
      sort: ModerationSort.PRICE_DESC,
    });
    expect(queryFromParams({ vendeur: 'particulier', etat: 'sain', offre: 'vente', origine: 'reelle', tri: 'anciens' })).toMatchObject({
      sellerRole: UserRole.PARTICULIER,
      damaged: false,
      offer: OfferType.SALE,
      demo: false,
      sort: ModerationSort.OLDEST,
    });
  });

  it('reads a value it does not know as not set, rather than failing', () => {
    expect(
      queryFromParams({ vendeur: 'ADMIN', etat: 'oui', offre: 'location', origine: '1', tri: 'cheapest', 'prix-min': '-5', 'prix-max': '12,5' }),
    ).toEqual(EMPTY_QUERY);
    expect(queryFromParams({ q: ['a', 'b'] })).toEqual(EMPTY_QUERY);
    expect(queryFromParams({ q: 'x'.repeat(150) }).q).toHaveLength(100);
  });

  it('writes back what it read, and only what is set', () => {
    expect(paramsFromQuery(queryFromParams(FULL), ModerationView.LIVE)).toEqual({ ...FULL, 'prix-min': 5000, 'prix-max': 20000 });
    expect(Object.values(paramsFromQuery(EMPTY_QUERY, ModerationView.LIVE)).every((value) => value === null)).toBe(true);
  });

  it('leaves a list’s own order out of the address', () => {
    expect(defaultSort(ModerationView.PENDING)).toBe(ModerationSort.OLDEST);
    expect(defaultSort(ModerationView.LIVE)).toBe(ModerationSort.NEWEST);
    expect(defaultSort(ModerationView.REMOVED)).toBe(ModerationSort.NEWEST);

    const oldest = { ...EMPTY_QUERY, sort: ModerationSort.OLDEST };
    expect(paramsFromQuery(oldest, ModerationView.PENDING)['tri']).toBeNull();
    expect(paramsFromQuery(oldest, ModerationView.LIVE)['tri']).toBe('anciens');
  });

  it('asks the API in its own terms: enums, minor units, and the list’s order when none is chosen', () => {
    expect(requestFromQuery(queryFromParams(FULL), ModerationView.LIVE, 2)).toEqual({
      view: ModerationView.LIVE,
      q: 'peugeot bellevue',
      make: 'Peugeot',
      sellerRole: UserRole.PROFESSIONNEL,
      damaged: true,
      offer: OfferType.LEASING,
      demo: true,
      priceMin: 500_000,
      priceMax: 2_000_000,
      sort: ModerationSort.PRICE_DESC,
    });
    expect(requestFromQuery(EMPTY_QUERY, ModerationView.PENDING, 2)).toEqual({
      view: ModerationView.PENDING,
      q: undefined,
      make: undefined,
      sellerRole: undefined,
      damaged: undefined,
      offer: undefined,
      demo: undefined,
      priceMin: undefined,
      priceMax: undefined,
      sort: ModerationSort.OLDEST,
    });
    // A currency without decimals.
    expect(requestFromQuery({ ...EMPTY_QUERY, priceMin: 5000 }, ModerationView.LIVE, 0).priceMin).toBe(5000);
  });

  it('turns a price range given backwards round, and keeps a bound of zero', () => {
    const backwards = { ...EMPTY_QUERY, priceMin: 20000, priceMax: 5000 };
    expect(requestFromQuery(backwards, ModerationView.LIVE, 2)).toMatchObject({ priceMin: 500_000, priceMax: 2_000_000 });
    expect(requestFromQuery({ ...EMPTY_QUERY, priceMin: 0 }, ModerationView.LIVE, 2).priceMin).toBe(0);
  });

  it('sends a search of only spaces as no search', () => {
    expect(requestFromQuery({ ...EMPTY_QUERY, q: '   ' }, ModerationView.LIVE, 2).q).toBeUndefined();
    expect(isNarrowed({ ...EMPTY_QUERY, q: '   ' })).toBe(false);
  });

  it('counts the filters, the search and the order apart', () => {
    expect(activeFilters(EMPTY_QUERY)).toBe(0);
    expect(activeFilters(queryFromParams(FULL))).toBe(7);
    expect(activeFilters({ ...EMPTY_QUERY, q: 'golf', sort: ModerationSort.PRICE_ASC })).toBe(0);
    expect(activeFilters({ ...EMPTY_QUERY, damaged: false, demo: false })).toBe(2);
    expect(isNarrowed({ ...EMPTY_QUERY, q: 'golf' })).toBe(true);
    expect(isNarrowed({ ...EMPTY_QUERY, sort: ModerationSort.PRICE_ASC })).toBe(false);
  });
});
