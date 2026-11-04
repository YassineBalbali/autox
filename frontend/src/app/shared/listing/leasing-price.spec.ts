import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { LeasingType, PublicListingSummary } from '../../core/api';
import { ListingCard } from './listing-card';
import { ListingRow } from './listing-row';

const OFFER: PublicListingSummary = {
  id: 'l-1',
  title: 'Peugeot 3008 en LOA',
  make: 'Peugeot',
  model: '3008',
  // The vehicle's cash price, which a result does not lead with.
  priceAmount: 3_200_000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2024,
  mileageKm: 12_000,
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:44:20Z',
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
  photoCount: 0,
  leasing: {
    type: LeasingType.LOA,
    monthlyPayment: 29_900,
    firstPayment: 250_000,
    durationMonths: 37,
    mileagePerYear: 10_000,
    optionPrice: 1_300_000,
    totalCost: 2_626_400,
  },
};

@Component({
  imports: [ListingCard, ListingRow],
  template: `
    <app-listing-card [listing]="listing()" fuelLabel="Diesel" gearboxLabel="Manuelle" />
    <app-listing-row [listing]="listing()" fuelLabel="Diesel" gearboxLabel="Manuelle" />
  `,
})
class Host {
  readonly listing = signal<PublicListingSummary>(OFFER);
}

/** A leasing offer among results: the card and the row have to say the same thing. */
describe('a leasing offer as a result', () => {
  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const compact = (node: Element | null) => node?.textContent?.replace(/\s/g, '') ?? '';

  it.each(['app-listing-card', 'app-listing-row'])('reads "à partir de X €/mois" on %s', async (view) => {
    const root = await render();
    const prices = root.querySelectorAll(`${view} [data-leasing-price]`);

    expect(prices.length).toBeGreaterThan(0);
    expect(compact(prices[0])).toBe('àpartirde299€/mois');
    expect(compact(root.querySelector(`${view} [data-leasing-terms]`))).toBe('LOA·37mois·10000km/an');
  });

  it.each(['app-listing-card', 'app-listing-row'])('shows no cash price and no verdict on %s', async (view) => {
    const root = await render();

    expect(root.querySelector(`${view} app-price`)).toBeNull();
    expect(compact(root.querySelector(view))).not.toContain('32000€');
  });
});
