import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { FuelType, PublicListingDetail } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { KeyFacts, registration } from './key-facts';

@Component({
  imports: [KeyFacts],
  template: `<app-key-facts [listing]="listing()" [fuel]="fuel()" gearbox="Automatique" />`,
})
class Host {
  readonly listing = signal<PublicListingDetail>({});
  readonly fuel = signal<string | null>('Électrique');
}

/** The five facts under a listing's photos. */
describe('KeyFacts', () => {
  async function render(listing: PublicListingDetail, fuel: string | null = 'Électrique'): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    fixture.componentInstance.fuel.set(fuel);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const values = (root: HTMLElement) =>
    Array.from(root.querySelectorAll('dd')).map((value) => value.textContent?.trim());

  it('gives the month with the year when it is known', async () => {
    const root = await render({
      vehicle: { firstRegistrationYear: 2019, firstRegistrationMonth: 10, powerHp: 136, fuelType: FuelType.ELECTRIC },
      mileageKm: 12000,
    });

    expect(values(root)).toEqual(['10/2019', `12${NBSP}000${NBSP}km`, 'Électrique', 'Automatique', `136${NBSP}ch`]);
  });

  it('leaves out what the listing does not say, rather than showing a dash', async () => {
    const root = await render({ vehicle: { firstRegistrationYear: 2019 } }, null);

    expect(values(root)).toEqual(['2019', 'Automatique']);
    expect(root.textContent).not.toContain('undefined');
  });

  it('keeps a mileage of zero: a new car has one', async () => {
    expect(values(await render({ mileageKm: 0 }, null))).toContain(`0${NBSP}km`);
  });

  it('shows a plug for an electric car and a pump for the rest', async () => {
    const shapeOf = (root: HTMLElement) =>
      Array.from(root.querySelectorAll('.facts__tile')[0].querySelectorAll('path')).map((path) => path.getAttribute('d')).join();

    const electric = shapeOf(await render({ vehicle: { fuelType: FuelType.ELECTRIC } }));
    const diesel = shapeOf(await render({ vehicle: { fuelType: FuelType.DIESEL } }, 'Diesel'));

    expect(electric).not.toBe(diesel);
  });

  describe('registration', () => {
    it('reads month and year, or the year alone', () => {
      expect(registration(2019, 10)).toBe('10/2019');
      expect(registration(2019, 1)).toBe('01/2019');
      expect(registration(2019, undefined)).toBe('2019');
      // A month the API should never send is ignored rather than rolled into another year.
      expect(registration(2019, 13)).toBe('2019');
      expect(registration(undefined, 10)).toBeNull();
    });
  });
});
