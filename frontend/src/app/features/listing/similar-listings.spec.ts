import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH, PublicListingSummary } from '../../core/api';
import { PHONE_SCREEN, stubMedia } from '../../core/ui/dom-testing';
import { SimilarListings } from './similar-listings';

const BASE = 'http://api.test';
const similarTo = (id: string) => `${BASE}/api/v1/listings/${id}/similar`;

const card = (id: string, model: string): PublicListingSummary => ({
  id,
  title: `Peugeot ${model}`,
  make: 'Peugeot',
  model,
  priceAmount: 1_000_000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2020,
  mileageKm: 40_000,
  fuelType: 'PETROL',
  gearbox: 'MANUAL',
  city: 'Lyon',
  postalCode: '69003',
  seller: { id: 's1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
  photoCount: 1,
});

@Component({
  imports: [SimilarListings],
  template: `<app-similar-listings [listingId]="id()" />`,
})
class Host {
  readonly id = signal('l1');
}

/**
 * The section under a listing: what the API suggests, as result cards, or nothing at all.
 */
describe('SimilarListings', () => {
  let fixture: ComponentFixture<Host>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => fixture.whenStable();
  const pending = (id: string): TestRequest[] =>
    backend.match((request) => request.url === similarTo(id)).filter((request) => !request.cancelled);
  const titles = () => Array.from(root.querySelectorAll('.card__link')).map((a) => a.textContent?.trim());

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement;
    await settle();
  });

  it('shows the suggestions as result cards, in the order given, each a link to its listing', async () => {
    pending('l1')[0].flush([card('a', '208'), card('b', '2008'), card('c', '308')]);
    await settle();

    expect(root.querySelector('h2#titre-similaires')?.textContent?.trim()).toBe('Annonces similaires');
    expect(titles()).toEqual(['Peugeot 208', 'Peugeot 2008', 'Peugeot 308']);
    expect(root.querySelector('.card__link')?.getAttribute('href')).toBe('/annonces/a');
    // Under the section's heading in the outline, not beside it.
    expect(root.querySelector('.card__title')?.getAttribute('aria-level')).toBe('3');
    expect(root.querySelectorAll('ul[role=list] > li')).toHaveLength(3);
  });

  it('leaves no trace when there is nothing to suggest: no heading over an empty space', async () => {
    pending('l1')[0].flush([]);
    await settle();

    expect(root.querySelector('section')).toBeNull();
  });

  it('says nothing when the suggestions cannot be had: the listing is what the visitor came for', async () => {
    pending('l1')[0].flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
    await settle();

    expect(root.querySelector('section')).toBeNull();
    expect(root.querySelector('.alert')).toBeNull();
  });

  it('on another listing, drops the last one’s suggestions at once and asks for the new one’s', async () => {
    pending('l1')[0].flush([card('a', '208'), card('b', '2008'), card('c', '308')]);
    await settle();

    fixture.componentInstance.id.set('l2');
    await settle();
    expect(root.querySelector('section')).toBeNull();

    pending('l2')[0].flush([card('d', 'Clio'), card('e', 'Captur'), card('f', 'Mégane')]);
    await settle();
    expect(titles()).toEqual(['Peugeot Clio', 'Peugeot Captur', 'Peugeot Mégane']);
  });

  it('uses the search’s compact row on a phone, still under the section’s heading', async () => {
    // One card per screen means nobody scrolls that far: the rows put four or five there.
    stubMedia(PHONE_SCREEN);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement;
    await settle();
    pending('l1').forEach((request) => request.flush([card('a', '208'), card('b', '2008'), card('c', '308')]));
    await settle();

    expect(root.querySelector('app-listing-card')).toBeNull();
    const rows = Array.from(root.querySelectorAll('app-listing-row'));
    expect(rows).toHaveLength(3);
    expect(rows.map((row) => row.querySelector('.listing__link')?.textContent?.trim())).toEqual([
      'Peugeot 208',
      'Peugeot 2008',
      'Peugeot 308',
    ]);
    expect(rows[0].querySelector('.listing__link')?.getAttribute('href')).toBe('/annonces/a');
    expect(rows[0].querySelector('.listing__title')?.getAttribute('aria-level')).toBe('3');
  });
});
