import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, PriceLevel, Valuation, ValuationScope } from '../../core/api';
import { EstimatedCar, PriceEstimate, levelForPrice, notEstimated } from './price-estimate';

const BASE = 'http://api.test';
const VALUATION = `${BASE}/api/v1/valuation`;

const CAR: EstimatedCar = {
  modelId: 'model-208',
  fuel: 'PETROL',
  powerHp: 100,
  year: 2020,
  mileageKm: 40000,
  isNew: false,
  damaged: false,
  leasing: false,
};

const ENOUGH: Valuation = {
  scope: ValuationScope.EXACT,
  comparableCount: 7,
  minimumComparables: 5,
  estimate: { lowAmount: 950_000, medianAmount: 1_000_000, highAmount: 1_050_000, currencyCode: 'EUR' },
  priceBounds: { goodDealAtMost: 900_000, aboveMarketFrom: 1_100_000 },
  widerScopes: [],
};

/** The price step's estimate: La Cote's, the badge's verdict on the price, and when there is neither. */
describe('PriceEstimate', () => {
  let fixture: ComponentFixture<PriceEstimate>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const words = (selector: string) => root.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const debounce = () => new Promise((resolve) => setTimeout(resolve, 350));

  async function show(car: EstimatedCar, price = ''): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PriceEstimate);
    fixture.componentRef.setInput('car', car);
    fixture.componentRef.setInput('price', price);
    root = fixture.nativeElement;
    await fixture.whenStable();
    await debounce();
    await fixture.whenStable();
  }

  async function answer(valuation: Valuation): Promise<TestRequest> {
    const [request] = backend.match((r) => r.url === VALUATION);
    request.flush(valuation);
    await fixture.whenStable();
    return request;
  }

  async function type(price: string): Promise<void> {
    fixture.componentRef.setInput('price', price);
    await fixture.whenStable();
  }

  it('asks La Cote itself, for this car, and shows its estimate and range', async () => {
    await show(CAR);
    const request = await answer(ENOUGH);

    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('modelId')).toBe('model-208');
    expect(request.request.params.get('fuelType')).toBe('PETROL');
    expect(request.request.params.get('powerHp')).toBe('100');
    expect(request.request.params.get('year')).toBe('2020');
    expect(request.request.params.get('mileageKm')).toBe('40000');
    // Not a loosened rule: the badge's own.
    expect(request.request.params.get('scope')).toBeNull();
    expect(words('[data-estimate-median]').replace(/\s/g, ' ')).toBe('10 000 €');
    expect(words('[data-estimate-range]').replace(/\s/g, ' ')).toContain('Entre 9 500 € et 10 500 €, d’après 7 annonces comparables');
    expect(root.textContent).toContain('Le prix reste le vôtre');
  });

  it('places the price as it is typed, with the badge’s lines, on them and either side', async () => {
    await show(CAR);
    await answer(ENOUGH);
    expect(root.querySelector('[data-estimate-level]')).toBeNull();

    const cases: [string, string][] = [
      ['8999', 'Bonne affaire'],
      ['9000', 'Bonne affaire'],
      ['9001', 'Prix du marché'],
      ['10 999', 'Prix du marché'],
      ['11000', 'Au-dessus du marché'],
      ['25000', 'Au-dessus du marché'],
    ];
    for (const [price, label] of cases) {
      await type(price);
      expect(words('[data-estimate-level]'), price).toContain(label);
    }
    // Not a price: no verdict, and the seller is not stopped.
    await type('dix mille');
    expect(root.querySelector('[data-estimate-level]')).toBeNull();
  });

  it('says plainly when there are too few comparables, and gives no figure', async () => {
    await show(CAR, '10000');
    await answer({ ...ENOUGH, comparableCount: 3, estimate: undefined, priceBounds: undefined });

    expect(root.querySelector('[data-estimate-median]')).toBeNull();
    expect(root.querySelector('[data-estimate-level]')).toBeNull();
    expect(words('[data-estimate="too-few"]')).toBe(
      'Pas assez d’annonces comparables sur AutoX pour estimer ce véhicule (3 annonces comparables trouvées, 5 nécessaires). Fixez votre prix librement.',
    );
  });

  it('shows nothing and asks nothing for a damaged car or a leasing offer', async () => {
    for (const car of [{ ...CAR, damaged: true }, { ...CAR, leasing: true }]) {
      await show(car, '10000');
      backend.expectNone(VALUATION);
      expect(root.textContent?.trim()).toBe('');
    }
  });

  it('asks nothing for a typed model or a new car, and says why', async () => {
    await show({ ...CAR, modelId: null });
    backend.expectNone(VALUATION);
    expect(root.querySelector('[data-estimate="incomplete"]')).not.toBeNull();

    await show({ ...CAR, isNew: true });
    backend.expectNone(VALUATION);
    expect(root.querySelector('[data-estimate="new"]')).not.toBeNull();
  });

  it('decides with the same rule as the badge', () => {
    const bounds = { goodDealAtMost: 900_000.4, aboveMarketFrom: 1_100_000.6 };
    expect(levelForPrice(900_000, bounds)).toBe(PriceLevel.GOOD_DEAL);
    expect(levelForPrice(900_001, bounds)).toBe(PriceLevel.MARKET);
    expect(levelForPrice(1_100_000, bounds)).toBe(PriceLevel.MARKET);
    expect(levelForPrice(1_100_001, bounds)).toBe(PriceLevel.ABOVE_MARKET);
    expect(notEstimated({ ...CAR, damaged: true, isNew: true })).toBe('excluded');
    expect(notEstimated({ ...CAR, powerHp: null })).toBe('incomplete');
    expect(notEstimated(CAR)).toBeNull();
  });
});
