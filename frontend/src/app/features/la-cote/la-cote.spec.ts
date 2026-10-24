import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { FuelType, Gearbox, ValuationScope } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import {
  CoteQuery,
  basisMileage,
  basisYears,
  coteParams,
  engineLabel,
  isComplete,
  parseCote,
  scopeLabel,
  sellParams,
  similarSearchParams,
  windowSentence,
} from './la-cote';

const NOW = new Date('2026-10-24T12:00:00Z');

const CLIO: CoteQuery = {
  make: 'renault',
  model: 'clio',
  fuel: FuelType.PETROL,
  power: 90,
  year: 2022,
  mileage: 45_000,
  gearbox: Gearbox.MANUAL,
  version: 'TCe 90 Evolution',
  scope: ValuationScope.EXACT,
};

const EXACT_WINDOW = {
  yearFrom: 2020,
  yearTo: 2024,
  mileageFromKm: 30_000,
  mileageToKm: 60_000,
  fuelType: 'PETROL',
  powerFromHp: 77,
  powerToHp: 103,
};

/** La Cote in the address, and the sentences and links made from an answer. */
describe('la-cote', () => {
  describe('the address', () => {
    it('round-trips a description, the scope as a French word and the codes in lower case', () => {
      const params = coteParams({ ...CLIO, fuel: FuelType.HYBRID_PETROL, scope: ValuationScope.ANY_MILEAGE });

      expect(params).toEqual({
        marque: 'renault',
        modele: 'clio',
        carburant: 'hybrid-petrol',
        puissance: '90',
        annee: '2022',
        km: '45000',
        boite: 'manual',
        version: 'TCe 90 Evolution',
        elargir: 'kilometrage',
      });
      expect(parseCote(convertToParamMap(params), NOW)).toEqual({
        ...CLIO,
        fuel: FuelType.HYBRID_PETROL,
        scope: ValuationScope.ANY_MILEAGE,
      });
    });

    it('leaves the scope out when it is the indicator’s own rule', () => {
      expect(coteParams(CLIO)['elargir']).toBeUndefined();
    });

    it('drops whatever it cannot trust, rather than passing it on', () => {
      const query = parseCote(
        convertToParamMap({
          marque: 'Renault <b>',
          modele: 'clio',
          carburant: 'water',
          puissance: '1e3',
          annee: '2099',
          km: '-4',
          boite: 'manual',
          elargir: 'tout',
        }),
        NOW,
      );

      expect(query).toEqual({
        ...CLIO,
        make: null,
        fuel: null,
        power: null,
        year: null,
        mileage: null,
        version: null,
      });
      expect(isComplete(query)).toBe(false);
    });

    it('needs everything but the gearbox and version to ask for an estimate', () => {
      expect(isComplete(CLIO)).toBe(true);
      expect(isComplete({ ...CLIO, gearbox: null, version: null })).toBe(true);
      expect(isComplete({ ...CLIO, power: null })).toBe(false);
    });
  });

  describe('the links', () => {
    it('searches the comparables’ window: model, years, mileage band, fuel, and the gearbox given', () => {
      expect(similarSearchParams(CLIO, EXACT_WINDOW)).toEqual({
        marque: 'renault',
        modele: 'clio',
        annee_min: '2020',
        annee_max: '2024',
        km_min: '30000',
        km_max: '60000',
        carburant: 'petrol',
        boite: 'manual',
      });
    });

    it('searches every fuel when every engine was counted, and every mileage when the band was dropped', () => {
      const params = similarSearchParams(CLIO, {
        yearFrom: 2020,
        yearTo: 2024,
        fuelType: undefined,
        mileageFromKm: undefined,
        mileageToKm: undefined,
      });

      expect(params['carburant']).toBeUndefined();
      expect(params['km_min']).toBeUndefined();
      expect(params['km_max']).toBeUndefined();
    });

    it('starts a listing with the catalogue ids and the codes as the API has them', () => {
      expect(sellParams(CLIO, 'make-1', 'model-1')).toEqual({
        marque: 'make-1',
        modele: 'model-1',
        version: 'TCe 90 Evolution',
        annee: '2022',
        km: '45000',
        carburant: 'PETROL',
        boite: 'MANUAL',
      });
    });
  });

  describe('the words', () => {
    it('says what was searched, engine, years and mileage band', () => {
      expect(windowSentence(EXACT_WINDOW)).toBe(
        `Même motorisation (Essence, 77 à 103 ch), mise en circulation de 2020 à 2024, entre 30${NBSP}000 et 60${NBSP}000 km.`,
      );
    });

    it('says when an engine or a mileage was not matched, and words the end bands', () => {
      expect(windowSentence({ ...EXACT_WINDOW, fuelType: undefined, mileageFromKm: undefined })).toBe(
        'Toutes motorisations, mise en circulation de 2020 à 2024, tous kilométrages.',
      );
      expect(windowSentence({ ...EXACT_WINDOW, mileageFromKm: 150_000, mileageToKm: undefined })).toContain(
        `150${NBSP}000 km et plus`,
      );
      expect(windowSentence({ ...EXACT_WINDOW, mileageFromKm: 0, mileageToKm: 30_000 })).toContain(
        `moins de 30${NBSP}000 km`,
      );
    });

    it('gives the comparables’ years and mileages, once when they are all the same', () => {
      expect(basisYears({ yearFrom: 2020, yearTo: 2020 })).toBe('2020');
      expect(basisYears({ yearFrom: 2019, yearTo: 2022 })).toBe('2019 à 2022');
      expect(basisMileage({ mileageFromKm: 32_000, mileageToKm: 58_000 })).toBe(`32${NBSP}000 à 58${NBSP}000 km`);
    });

    it('names an engine by fuel and power, then by what sellers call it', () => {
      expect(engineLabel({ fuelType: 'PETROL', powerHp: 90, exampleVersion: 'TCe 90 Evolution', listingCount: 8 })).toBe(
        'Essence 90 ch — TCe 90 Evolution',
      );
      expect(engineLabel({ fuelType: 'DIESEL', powerHp: 130, listingCount: 1 })).toBe('Diesel 130 ch');
    });

    it('names each loosening by what it changes', () => {
      expect(scopeLabel(ValuationScope.WIDER_YEARS, 2021)).toBe('Années 2017 à 2025');
      expect(scopeLabel(ValuationScope.ALL_ENGINES, 2021)).toBe('Toutes les motorisations du modèle');
      expect(scopeLabel(ValuationScope.ANY_MILEAGE, 2021)).toBe('Tous les kilométrages');
    });
  });
});
