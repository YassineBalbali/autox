import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { DamageFilter, BodyType, FuelType, Gearbox, LeasingType, OfferType, SearchSort, VehicleCondition } from '../../core/api';
import {
  EMPTY_QUERY,
  SearchQuery,
  activeFilterCount,
  allowsGoodDeals,
  parseQuery,
  switchOffer,
  toCriteria,
  toQueryParams,
  withoutFilters,
} from './search-query';

const parse = (params: Record<string, string>) => parseQuery(convertToParamMap(params));

/**
 * The URL ↔ search mapping.
 *
 * <p>The search screen keeps its state in the address bar and nowhere else, so these two
 * functions are the screen's memory. They are also a trust boundary: the query string is
 * whatever somebody typed, pasted or had mangled by a mail client, and every value in it
 * reaches the API unless it is stopped here.
 */
describe('Acheter / Louer', () => {
  const leasing: SearchQuery = { ...EMPTY_QUERY, offer: OfferType.LEASING };

  it('is a sale unless a link says otherwise, and then says nothing to the API or in the address', () => {
    expect(parse({}).offer).toBe(OfferType.SALE);
    expect(parse({ offre: 'nimportequoi' }).offer).toBe(OfferType.SALE);
    expect(toCriteria(EMPTY_QUERY, {}, 'EUR', 2).offer).toBeUndefined();
    expect(toQueryParams(EMPTY_QUERY)).toEqual({});
  });

  it('asks the API for leasing offers when the address does', () => {
    expect(parse({ offre: 'location' }).offer).toBe(OfferType.LEASING);
    expect(toCriteria(parse({ offre: 'location' }), {}, 'EUR', 2).offer).toBe(OfferType.LEASING);
  });

  it('keeps a leasing link\'s terms, in the currency\'s minor units for the API', () => {
    const query = parse({
      offre: 'location',
      type_location: 'loa',
      mensualite_min: '150',
      mensualite_max: '300',
      duree_min: '24',
      duree_max: '48',
      km_an_min: '15000',
    });

    expect(query).toMatchObject({
      leasingType: [LeasingType.LOA],
      monthlyMin: 150,
      monthlyMax: 300,
      durationMin: 24,
      durationMax: 48,
      allowanceMin: 15000,
    });
    expect(toCriteria(query, {}, 'EUR', 2)).toMatchObject({
      leasingType: [LeasingType.LOA],
      monthlyMin: 15000,
      monthlyMax: 30000,
      currency: 'EUR',
      durationMin: 24,
      durationMax: 48,
      mileageAllowanceMin: 15000,
    });
  });

  it('survives a round trip', () => {
    const query: SearchQuery = {
      ...leasing,
      make: 'peugeot',
      leasingType: [LeasingType.LLD],
      monthlyMax: 300,
      durationMin: 24,
      allowanceMin: 10000,
      critAirMax: null,
      sort: SearchSort.MONTHLY_ASC,
    };

    expect(parse(toQueryParams(query) as Record<string, string>)).toEqual(query);
  });

  describe('never mixes the two', () => {
    it('ignores a leasing term in a sale search', () => {
      const query = parse({ type_location: 'loa', mensualite_max: '300', duree_min: '24', km_an_min: '15000' });

      expect(query).toEqual(EMPTY_QUERY);
      expect(activeFilterCount(query)).toBe(0);
    });

    it('ignores what only a sale has in a leasing search', () => {
      const damaged = toQueryParams({ ...EMPTY_QUERY, damage: DamageFilter.DAMAGED_ONLY }) as Record<string, string>;
      const query = parse({ ...damaged, offre: 'location', prix_max: '20000', bonnes_affaires: '1' });

      expect(query.priceMax).toBeNull();
      expect(query.goodDeals).toBe(false);
      expect(query.damage).toBe(DamageFilter.UNDAMAGED);
      const criteria = toCriteria({ ...query, priceMax: 20000, goodDeals: true, damage: DamageFilter.ALL }, {}, 'EUR', 2);
      expect(criteria.priceMax).toBeUndefined();
      expect(criteria.damage).toBeUndefined();
      expect(criteria.currency).toBeUndefined();
    });

    it('sorts by a term only where the term exists', () => {
      expect(parse({ offre: 'location', tri: 'mensualite-croissante' }).sort).toBe(SearchSort.MONTHLY_ASC);
      expect(parse({ tri: 'mensualite-croissante' }).sort).toBe(EMPTY_QUERY.sort);
      expect(parse({ offre: 'location', tri: 'prix-croissant' }).sort).toBe(EMPTY_QUERY.sort);
      expect(parse({ offre: 'location', tri: 'km-croissant' }).sort).toBe(EMPTY_QUERY.sort);
    });

    it('allows no "bonnes affaires" on a leasing offer', () => {
      expect(allowsGoodDeals(EMPTY_QUERY)).toBe(true);
      expect(allowsGoodDeals(leasing)).toBe(false);
    });
  });

  describe('switching', () => {
    it('keeps the car being looked for and drops what the other search cannot show', () => {
      const sale: SearchQuery = {
        ...EMPTY_QUERY,
        make: 'peugeot',
        location: 'Lyon',
        priceMax: 20000,
        goodDeals: true,
        damage: DamageFilter.ALL,
        sort: SearchSort.PRICE_ASC,
      };

      expect(switchOffer(sale, OfferType.LEASING)).toEqual({
        ...EMPTY_QUERY,
        make: 'peugeot',
        location: 'Lyon',
        offer: OfferType.LEASING,
      });
    });

    it('comes back without the terms', () => {
      const lease: SearchQuery = { ...leasing, make: 'peugeot', monthlyMax: 300, sort: SearchSort.MONTHLY_ASC };

      expect(switchOffer(lease, OfferType.SALE)).toEqual({ ...EMPTY_QUERY, make: 'peugeot' });
    });
  });

  it('stays on the same search when the filters are cleared, and is not itself a filter', () => {
    expect(withoutFilters({ ...leasing, monthlyMax: 300 }).offer).toBe(OfferType.LEASING);
    expect(activeFilterCount(leasing)).toBe(0);
    expect(activeFilterCount({ ...leasing, monthlyMax: 300, durationMin: 24 })).toBe(2);
  });
});

describe('parseQuery', () => {
  it('reads an empty URL as a search with no filters, newest first', () => {
    expect(parse({})).toEqual(EMPTY_QUERY);
  });

  it('reads every filter', () => {
    expect(
      parse({
        q: 'golf tdi',
        marque: 'peugeot',
        modele: '308',
        annee_min: '2018',
        annee_max: '2022',
        prix_min: '5000',
        prix_max: '20000',
        km_min: '10000',
        km_max: '90000',
        carburant: 'diesel,plugin-hybrid',
        boite: 'automatic',
        carrosserie: 'suv',
        etat: 'used',
        lieu: 'Lyon',
        bonnes_affaires: '1',
        tri: 'prix-croissant',
      }),
    ).toEqual<SearchQuery>({
      text: 'golf tdi',
      make: 'peugeot',
      model: '308',
      yearMin: 2018,
      yearMax: 2022,
      priceMin: 5000,
      priceMax: 20000,
      mileageMin: 10000,
      mileageMax: 90000,
      fuel: [FuelType.DIESEL, FuelType.PLUGIN_HYBRID],
      gearbox: [Gearbox.AUTOMATIC],
      body: [BodyType.SUV],
      condition: [VehicleCondition.USED],
      damage: DamageFilter.UNDAMAGED,
      location: 'Lyon',
      goodDeals: true,
      offer: OfferType.SALE,
      leasingType: [],
      monthlyMin: null,
      monthlyMax: null,
      durationMin: null,
      durationMax: null,
      allowanceMin: null,
      critAirMax: null,
      sort: SearchSort.PRICE_ASC,
    });
  });

  describe('État du véhicule', () => {
    it('leaves damaged vehicles out unless a link asks for them, and says nothing to the API', () => {
      const query = parse({});

      expect(query.damage).toBe(DamageFilter.UNDAMAGED);
      expect(toCriteria(query, {}, 'EUR', 2).damage).toBeUndefined();
      expect(toQueryParams(query)).toEqual({});
      expect(activeFilterCount(query)).toBe(0);
    });

    it.each([
      ['endommages', DamageFilter.DAMAGED_ONLY],
      ['tous', DamageFilter.ALL],
    ])('reads %s, sends it, keeps it in the link and counts it as a filter', (slug, filter) => {
      const query = parse({ etat_vehicule: slug });

      expect(query.damage).toBe(filter);
      expect(toCriteria(query, {}, 'EUR', 2).damage).toBe(filter);
      expect(toQueryParams(query)).toEqual({ etat_vehicule: slug });
      expect(activeFilterCount(query)).toBe(1);
    });

    it('ignores a value it does not know', () => {
      expect(parse({ etat_vehicule: 'n-importe-quoi' }).damage).toBe(DamageFilter.UNDAMAGED);
    });

    it('keeps an old link meaning what it meant: etat=DAMAGED asked for damaged vehicles', () => {
      const query = parse({ etat: 'DAMAGED' });

      expect(query.damage).toBe(DamageFilter.DAMAGED_ONLY);
      expect(query.condition).toEqual([]);
      expect(parse({ etat: 'USED,DAMAGED' }).condition).toEqual([VehicleCondition.USED]);
      expect(parse({ etat: 'USED' }).damage).toBe(DamageFilter.UNDAMAGED);
    });
  });

  describe('good deals among damaged vehicles alone, which can never find anything', () => {
    it('does not read them from a link', () => {
      expect(parse({ etat_vehicule: 'endommages', bonnes_affaires: '1' }).goodDeals).toBe(false);
      expect(parse({ etat: 'DAMAGED', bonnes_affaires: '1' }).goodDeals).toBe(false);
    });

    it('keeps them where they can find something', () => {
      expect(parse({ bonnes_affaires: '1' }).goodDeals).toBe(true);
      expect(parse({ etat_vehicule: 'tous', bonnes_affaires: '1' }).goodDeals).toBe(true);
    });

    it('drops them from the link when damaged-only is chosen with them on', () => {
      const params = toQueryParams({ ...EMPTY_QUERY, goodDeals: true, damage: DamageFilter.DAMAGED_ONLY });

      expect(params).toEqual({ etat_vehicule: 'endommages' });
    });
  });

  it('reads the good-deals flag as on only when it is 1', () => {
    // A flag in a link is on or absent; "true", "oui" or "0" are not guesses worth making.
    expect(parse({ bonnes_affaires: '1' }).goodDeals).toBe(true);
    for (const value of ['0', 'true', 'oui', '']) {
      expect(parse({ bonnes_affaires: value }).goodDeals).toBe(false);
    }
  });

  describe('treats the address bar as untrusted', () => {
    it.each([
      ['a word', 'beaucoup'],
      ['a negative', '-5'],
      ['a decimal', '12.5'],
      ['an exponent', '1e9'],
      ['a script', '<script>alert(1)</script>'],
      ['something enormous', '99999999999999999999'],
      ['SQL', "1; DROP TABLE listing"],
    ])('drops a price that is %s', (_name, value) => {
      // Dropped, not forwarded. Sent on, the API would answer 400 and the user would be
      // looking at an error page for a link they merely clicked.
      expect(parse({ prix_max: value }).priceMax).toBeNull();
    });

    it('drops a year outside the range the API accepts', () => {
      expect(parse({ annee_min: '1492' }).yearMin).toBeNull();
      expect(parse({ annee_max: '3000' }).yearMax).toBeNull();
    });

    it('drops enum values it does not know, and keeps the ones it does', () => {
      // One bad value costs that value — not the filter, and not the page.
      expect(parse({ carburant: 'diesel,vapeur,electric' }).fuel).toEqual([
        FuelType.DIESEL,
        FuelType.ELECTRIC,
      ]);
    });

    it('ignores a repeated enum value', () => {
      expect(parse({ boite: 'manual,manual,MANUAL' }).gearbox).toEqual([Gearbox.MANUAL]);
    });

    it('falls back to the default sort for an unknown one', () => {
      expect(parse({ tri: 'au-hasard' }).sort).toBe(SearchSort.NEWEST);
    });

    it('caps free text at a length the API will take', () => {
      expect(parse({ lieu: 'x'.repeat(500) }).location).toHaveLength(80);
    });

    it('treats whitespace as no value', () => {
      expect(parse({ lieu: '   ', marque: ' ' })).toEqual(EMPTY_QUERY);
    });
  });

  describe('free text', () => {
    it('collapses stray whitespace, so one search is one URL', () => {
      expect(parse({ q: '  golf    tdi ' }).text).toBe('golf tdi');
    });

    it('caps the text at the length the API accepts', () => {
      // ListingSearchCriteria.q is @Size(max = 120). Longer and the API answers 400 —
      // for a link somebody merely pasted.
      expect(parse({ q: 'x'.repeat(500) }).text).toHaveLength(120);
    });

    it('passes characters that mean something to SQL or HTML through as plain text', () => {
      // Not this layer's job to escape: the API binds the value and Angular escapes what
      // it renders. What matters here is that nothing is dropped or mangled on the way.
      const hostile = "100% <b>d'occasion</b>_";
      expect(parse({ q: hostile }).text).toBe(hostile);
    });
  });

  it('puts a reversed range the right way round', () => {
    // Somebody typed the maximum in the minimum box. Returning nothing would be correct
    // and useless; the intent is obvious.
    const query = parse({ prix_min: '20000', prix_max: '5000' });
    expect([query.priceMin, query.priceMax]).toEqual([5000, 20000]);
  });

  it('discards a model that has no make', () => {
    // A model belongs to a make. Kept, it would be a filter the sidebar cannot display —
    // applied to the results and invisible to the user.
    expect(parse({ modele: '308' }).model).toBeNull();
  });
});

describe('toQueryParams', () => {
  it('writes nothing at all for the default search', () => {
    // So the home page is `/`, not `/?tri=recentes`.
    expect(toQueryParams(EMPTY_QUERY)).toEqual({});
  });

  it('omits the default sort and writes any other', () => {
    expect(toQueryParams({ ...EMPTY_QUERY, sort: SearchSort.MILEAGE_ASC })).toEqual({
      tri: 'km-croissant',
    });
  });

  it('writes enum lists in a fixed order, whatever order they were chosen in', () => {
    // The same search must always be the same URL — otherwise two people looking at one
    // result set hold two different links to it.
    const a = toQueryParams({ ...EMPTY_QUERY, fuel: [FuelType.PETROL, FuelType.DIESEL] });
    const b = toQueryParams({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL, FuelType.PETROL] });

    expect(a).toEqual(b);
    expect(a['carburant']).toBe('diesel,petrol');
  });

  it('survives a round trip', () => {
    const query: SearchQuery = {
      text: 'tdi 150',
      make: 'volkswagen',
      model: 'golf',
      yearMin: 2019,
      yearMax: null,
      priceMin: null,
      priceMax: 25000,
      mileageMin: null,
      mileageMax: 120000,
      fuel: [FuelType.DIESEL],
      gearbox: [Gearbox.AUTOMATIC, Gearbox.MANUAL],
      body: [],
      condition: [VehicleCondition.USED],
      damage: DamageFilter.ALL,
      location: 'Villeurbanne',
      goodDeals: true,
      offer: OfferType.SALE,
      leasingType: [],
      monthlyMin: null,
      monthlyMax: null,
      durationMin: null,
      durationMax: null,
      allowanceMin: null,
      critAirMax: null,
      sort: SearchSort.PRICE_DESC,
    };

    expect(parse(toQueryParams(query) as Record<string, string>)).toEqual(query);
  });

  it('keeps a zero, which is a value', () => {
    // km_min=0 is a meaningless filter but a legitimate one; dropping it because zero is
    // falsy is the bug that later drops a real `0`.
    expect(toQueryParams({ ...EMPTY_QUERY, mileageMin: 0 })).toEqual({ km_min: '0' });
  });
});

describe('toCriteria', () => {
  const eur = (query: SearchQuery) => toCriteria(query, {}, 'EUR', 2);

  it('sends nothing for an empty search', () => {
    expect(eur(EMPTY_QUERY)).toEqual({});
  });

  it('converts prices to the minor unit the API compares', () => {
    expect(eur({ ...EMPTY_QUERY, priceMin: 5000, priceMax: 12500 })).toEqual({
      priceMin: 500000,
      priceMax: 1250000,
      currency: 'EUR',
    });
  });

  it('uses the exponent it is given rather than assuming cents', () => {
    // A currency with no minor unit. Multiplying by 100 regardless would ask for
    // everything under a hundred times the price the user typed.
    const criteria = toCriteria({ ...EMPTY_QUERY, priceMax: 2_000_000 }, {}, 'JPY', 0);
    expect(criteria.priceMax).toBe(2_000_000);
  });

  it('names the currency only when a price is being filtered', () => {
    // "Under 15 000" needs a currency or it matches 15 000 of anything. Without a price
    // filter there is no reason to hide listings priced in another one.
    expect(eur({ ...EMPTY_QUERY, mileageMax: 50000 }).currency).toBeUndefined();
    expect(eur({ ...EMPTY_QUERY, priceMax: 15000 }).currency).toBe('EUR');
  });

  it('sends a catalogue id when the make was found, and text when it was not', () => {
    expect(
      toCriteria({ ...EMPTY_QUERY, make: 'peugeot' }, { makeId: 'm-1' }, 'EUR', 2),
    ).toEqual({ makeId: 'm-1' });

    expect(
      toCriteria({ ...EMPTY_QUERY, make: 'Peugeot' }, { makeText: 'Peugeot' }, 'EUR', 2),
    ).toEqual({ make: 'Peugeot' });
  });

  it('sends the free text as q', () => {
    expect(eur({ ...EMPTY_QUERY, text: 'golf tdi' })).toEqual({ q: 'golf tdi' });
  });

  it('passes the enum filters through as lists', () => {
    expect(
      eur({ ...EMPTY_QUERY, fuel: [FuelType.ELECTRIC], body: [BodyType.SUV, BodyType.ESTATE] }),
    ).toEqual({ fuelType: ['ELECTRIC'], bodyType: ['SUV', 'ESTATE'] });
  });

  it('asks for the good deals as a price level, and for no level otherwise', () => {
    expect(eur({ ...EMPTY_QUERY, goodDeals: true })).toEqual({ priceLevel: ['GOOD_DEAL'] });
    expect(eur({ ...EMPTY_QUERY, goodDeals: false }).priceLevel).toBeUndefined();
  });
});

describe('activeFilterCount', () => {
  it('counts a range once, not once per end', () => {
    expect(activeFilterCount({ ...EMPTY_QUERY, priceMin: 1000, priceMax: 9000 })).toBe(1);
  });

  it('counts a multi-choice filter once however many values are chosen', () => {
    expect(
      activeFilterCount({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL, FuelType.PETROL] }),
    ).toBe(1);
  });

  it('does not count the sort order, which is not a filter', () => {
    expect(activeFilterCount({ ...EMPTY_QUERY, sort: SearchSort.PRICE_ASC })).toBe(0);
  });

  it('counts the free text', () => {
    expect(activeFilterCount({ ...EMPTY_QUERY, text: 'golf' })).toBe(1);
  });

  it('counts the good-deals flag when it is on', () => {
    expect(activeFilterCount({ ...EMPTY_QUERY, goodDeals: true })).toBe(1);
    expect(activeFilterCount({ ...EMPTY_QUERY, goodDeals: false })).toBe(0);
  });

  it('counts a lower bound of zero', () => {
    expect(activeFilterCount({ ...EMPTY_QUERY, mileageMin: 0 })).toBe(1);
  });
});

describe('withoutFilters', () => {
  it('clears every filter and keeps the sort order', () => {
    // Clearing the filters is not a request to reorder the list.
    const cleared = withoutFilters({
      ...EMPTY_QUERY,
      make: 'bmw',
      fuel: [FuelType.DIESEL],
      sort: SearchSort.PRICE_ASC,
    });

    expect(cleared).toEqual({ ...EMPTY_QUERY, sort: SearchSort.PRICE_ASC });
  });
});
