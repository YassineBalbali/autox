import { describe, expect, it } from 'vitest';

import { BodyType, FuelType, Gearbox, ListingSearchCriteria, PriceLevel } from '../../core/api';
import { EMPTY_QUERY, toQueryParams } from './search-query';
import { INTERPRETATION_STATE, queryFromCriteria, readInterpretation } from './smart-search';

const MAKES = [
  { id: 'm-renault', slug: 'renault', name: 'Renault' },
  { id: 'm-noslug', name: 'Sans lien' },
];
const MODELS = [{ id: 'r-clio', slug: 'clio', name: 'Clio' }];

describe('queryFromCriteria', () => {
  it('turns the API criteria into the search the URL carries', () => {
    const criteria: ListingSearchCriteria = {
      makeId: 'm-renault',
      modelId: 'r-clio',
      yearFrom: 2018,
      priceMax: 1_500_000,
      currency: 'EUR',
      mileageMax: 80_000,
      fuelType: [FuelType.DIESEL],
      gearbox: [Gearbox.AUTOMATIC],
      bodyType: [BodyType.SUV, BodyType.HATCHBACK],
      location: 'Lyon',
      priceLevel: [PriceLevel.GOOD_DEAL],
    };

    const query = queryFromCriteria(criteria, MAKES, MODELS, 2);

    expect(toQueryParams(query)).toEqual({
      marque: 'renault',
      modele: 'clio',
      annee_min: '2018',
      prix_max: '15000',
      km_max: '80000',
      carburant: 'diesel',
      boite: 'automatic',
      carrosserie: 'hatchback,suv',
      lieu: 'Lyon',
      bonnes_affaires: '1',
    });
  });

  it('reads the nulls the API sends for absent fields as absent', () => {
    const fromJson = JSON.parse(
      '{"makeId":null,"modelId":null,"priceMax":null,"currency":null,"fuelType":null,"priceLevel":null}',
    ) as ListingSearchCriteria;

    expect(queryFromCriteria(fromJson, MAKES, MODELS, 2)).toEqual(EMPTY_QUERY);
  });

  it('drops a make the reference data cannot name, and its model with it', () => {
    const query = queryFromCriteria({ makeId: 'm-unknown', modelId: 'r-clio' }, MAKES, MODELS, 2);
    expect(query.make).toBeNull();
    expect(query.model).toBeNull();

    expect(queryFromCriteria({ makeId: 'm-noslug' }, MAKES, MODELS, 2).make).toBeNull();
  });

  it('uses the currency’s own minor unit, and ignores a price in another currency', () => {
    expect(queryFromCriteria({ priceMax: 15_000, currency: 'EUR' }, MAKES, MODELS, 0).priceMax).toBe(15_000);
    expect(queryFromCriteria({ priceMax: 1_500_000, currency: 'CHF' }, MAKES, MODELS, 2).priceMax).toBeNull();
  });
});

describe('readInterpretation', () => {
  it('reads the description and what was not understood', () => {
    const state = { [INTERPRETATION_STATE]: { text: 'SUV diesel', notUnderstood: ['en bon état'] } };
    expect(readInterpretation(state)).toEqual({ text: 'SUV diesel', notUnderstood: ['en bon état'] });
  });

  it('treats anything else in history state as absent, and bounds what it keeps', () => {
    expect(readInterpretation(null)).toBeNull();
    expect(readInterpretation({ navigationId: 3 })).toBeNull();
    expect(readInterpretation({ [INTERPRETATION_STATE]: { text: 42, notUnderstood: [] } })).toBeNull();
    expect(readInterpretation({ [INTERPRETATION_STATE]: { text: 'x', notUnderstood: 'rouge' } })).toBeNull();

    const loud = readInterpretation({
      [INTERPRETATION_STATE]: { text: 'a'.repeat(500), notUnderstood: [1, 'b'.repeat(100), ...Array(10).fill('c')] },
    });
    expect(loud!.text).toHaveLength(200);
    expect(loud!.notUnderstood).toHaveLength(6);
    expect(loud!.notUnderstood[0]).toHaveLength(60);
  });
});
