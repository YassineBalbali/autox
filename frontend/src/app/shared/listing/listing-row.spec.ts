import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { PublicListingSummary } from '../../core/api';
import { NBSP } from '../format/number';
import { ListingRow } from './listing-row';

const LISTING: PublicListingSummary = {
  id: 'l-1',
  title: 'Peugeot 308 BlueHDi 130 Allure Pack, première main',
  make: 'Peugeot',
  model: '308',
  version: 'BlueHDi 130 Allure Pack',
  priceAmount: 1250000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2021,
  mileageKm: 62400,
  fuelType: 'DIESEL',
  gearbox: 'MANUAL',
  powerHp: 130,
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:44:20Z',
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
  photoCount: 5,
  coverImage: {
    CARD: { url: 'http://img.test/card.jpg', width: 800, height: 600 },
  },
};

@Component({
  imports: [ListingRow],
  template: `
    <app-listing-row [listing]="listing()" fuelLabel="Diesel" gearboxLabel="Manuelle" [eager]="eager()" />
  `,
})
class Host {
  readonly listing = signal<PublicListingSummary>(LISTING);
  readonly eager = signal(false);
}

/**
 * One row of search results.
 *
 * <p>Every field on a generated response model is optional, so the row is exercised with
 * things missing as much as with things present: a listing with no photo, no city, no
 * date still has to be a row, at the same height, with nothing reading "undefined". The
 * picture, its photo count and its heart are `ListingThumb`'s, and tested there.
 */
describe('ListingRow', () => {
  let fixture: ComponentFixture<Host>;

  afterEach(() => localStorage.clear());

  async function render(listing: PublicListingSummary = LISTING): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('is headed by the make and model, with the version under it', async () => {
    const heading = (await render()).querySelector('h2') as HTMLElement;

    expect(heading.querySelector('a')?.textContent?.trim()).toBe('Peugeot 308');
    expect(heading.querySelector('.listing__version')?.textContent?.trim()).toBe('BlueHDi 130 Allure Pack');
  });

  it('shows the price large, the facts with their icons, the place and the date', async () => {
    const row = await render();

    // 12 500 €, with a space a reader can see and no decimals.
    expect(row.querySelector('app-price')?.textContent?.trim()).toBe(`12${NBSP}500${NBSP}€`);
    expect(row.querySelector('app-price .price')?.classList).toContain('price--result');
    expect(
      Array.from(row.querySelectorAll('app-spec-facts .facts__text')).map((item) => item.textContent),
    ).toEqual(['2021', `62${NBSP}400${NBSP}km`, 'Diesel', 'Manuelle', `130${NBSP}ch`]);
    expect(row.querySelector('.listing__place')?.textContent?.trim()).toBe('Lyon (69)');
    // A French date, in words.
    expect(row.textContent).toContain('Publiée le 11 octobre 2026');
  });

  it('says who is selling: the kind of seller and the name', async () => {
    const row = await render();

    expect(row.querySelector('app-seller-badge')?.textContent?.trim()).toBe('Pro');
    expect(row.querySelector('.listing__name')?.textContent).toBe('Garage Bellevue');

    const private_ = await render({ ...LISTING, seller: { displayName: 'Camille R.', role: 'PARTICULIER' } });
    expect(private_.querySelector('app-seller-badge')?.textContent?.trim()).toBe('Particulier');
  });

  it('shows the photo with its count and the heart', async () => {
    const row = await render();

    expect(row.querySelector('app-listing-thumb img')?.getAttribute('src')).toBe('http://img.test/card.jpg');
    // The seller's full title is the photo's description: it says more than "Peugeot 308".
    expect(row.querySelector('img')?.getAttribute('alt')).toBe(LISTING.title);
    expect(row.querySelector('.thumb__count')?.textContent).toContain('5');
    expect(row.querySelector('app-favourite-button')).not.toBeNull();
  });

  it('puts the price last, after the facts', async () => {
    // Order in the DOM is order on screen and order for a screen reader: photo, facts,
    // price — the same on every row.
    const article = (await render()).querySelector('article') as HTMLElement;

    expect(Array.from(article.children).map((child) => child.classList[0])).toEqual([
      'listing__thumb',
      'listing__main',
      'listing__aside',
    ]);
  });

  it('survives a listing with almost nothing on it', async () => {
    const row = await render({ id: 'bare', title: 'Sans détail' });

    expect(row.querySelector('h2')?.textContent).toContain('Sans détail');
    expect(row.querySelector('.listing__version')).toBeNull();
    expect(row.querySelector('app-price')).toBeNull();
    expect(row.querySelector('.listing__place')).toBeNull();
    expect(row.querySelector('.thumb__count')).toBeNull();
    expect(row.textContent).not.toContain('undefined');
    expect(row.textContent).not.toContain('null');
    expect(row.textContent).not.toContain('Publiée le');
  });

  describe('the link to the listing', () => {
    it('is the title, and leads to the listing page', async () => {
      const row = await render();
      const links = row.querySelectorAll('a');

      // One link, not one per element and not a link wrapped round the whole row: a
      // screen reader then announces the title as the link's name, rather than reading
      // out the price, the mileage and the town as one enormous name.
      expect(links).toHaveLength(1);
      expect(links[0].getAttribute('href')).toBe('/annonces/l-1');
      expect(links[0].textContent?.trim()).toBe('Peugeot 308');
      expect(links[0].closest('h2')).not.toBeNull();
    });

    it('is not offered for a listing that has no id to link to', async () => {
      const row = await render({ ...LISTING, id: undefined });

      expect(row.querySelector('a')).toBeNull();
      expect(row.querySelector('h2')?.textContent).toContain('Peugeot 308');
    });
  });
});
