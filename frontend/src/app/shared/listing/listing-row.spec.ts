import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { PublicListingSummary } from '../../core/api';
import { NBSP } from '../format/number';
import { ListingRow, departmentOf } from './listing-row';

const LISTING: PublicListingSummary = {
  id: 'l-1',
  title: 'Peugeot 308 BlueHDi 130 Allure Pack',
  priceAmount: 1250000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2021,
  mileageKm: 62400,
  fuelType: 'DIESEL',
  gearbox: 'MANUAL',
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:44:20Z',
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
  coverImage: {
    CARD: { url: 'http://img.test/card.jpg', width: 800, height: 600 },
    THUMBNAIL: { url: 'http://img.test/thumb.jpg', width: 320, height: 240 },
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
 * date still has to be a row, at the same height, with nothing reading "undefined".
 */
describe('ListingRow', () => {
  let fixture: ComponentFixture<Host>;

  async function render(listing: PublicListingSummary = LISTING): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('shows the title, the price, the facts, the city and the posting date', async () => {
    const row = await render();

    expect(row.querySelector('h2')?.textContent).toContain('Peugeot 308 BlueHDi 130');
    // 12 500 €, with a space a reader can see and no decimals.
    expect(row.querySelector('app-price')?.textContent?.trim()).toBe(`12${NBSP}500${NBSP}€`);
    expect(
      Array.from(row.querySelectorAll('app-spec-line li')).map((item) => item.textContent?.trim()),
    ).toEqual(['2021', `62${NBSP}400${NBSP}km`, 'Diesel', 'Manuelle']);
    expect(row.textContent).toContain('Lyon (69)');
    // A French date, in words.
    expect(row.textContent).toContain('Publiée le 11 octobre 2026');
  });

  it('puts the price last, after the facts', async () => {
    // Order in the DOM is order on screen and order for a screen reader: photo, facts,
    // price — the same on every row.
    const row = await render();
    const article = row.querySelector('article') as HTMLElement;

    expect(Array.from(article.children).map((child) => child.className)).toEqual([
      'listing__photo',
      'listing__main',
      'listing__price',
    ]);
  });

  describe('the photo', () => {
    it('uses the card variant, with its dimensions so the row does not jump', async () => {
      const image = (await render()).querySelector('img') as HTMLImageElement;

      expect(image.getAttribute('src')).toBe('http://img.test/card.jpg');
      expect(image.getAttribute('width')).toBe('800');
      expect(image.getAttribute('height')).toBe('600');
      expect(image.getAttribute('alt')).toContain('Peugeot 308');
    });

    it('is lazy by default and eager for the rows on screen at once', async () => {
      const row = await render();
      expect(row.querySelector('img')?.getAttribute('loading')).toBe('lazy');

      fixture.componentInstance.eager.set(true);
      await fixture.whenStable();
      expect(row.querySelector('img')?.getAttribute('loading')).toBe('eager');
    });

    it('falls back to the thumbnail when there is no card variant', async () => {
      const row = await render({
        ...LISTING,
        coverImage: { THUMBNAIL: { url: 'http://img.test/thumb.jpg', width: 320, height: 240 } },
      });

      expect(row.querySelector('img')?.getAttribute('src')).toBe('http://img.test/thumb.jpg');
    });

    it('shows a placeholder, in the same box, when the listing has none', async () => {
      const row = await render({ ...LISTING, coverImage: {} });

      expect(row.querySelector('img')).toBeNull();
      expect(row.querySelector('.listing__photo')?.textContent).toContain('Pas de photo');
    });

    it('shows the placeholder when the image fails to load', async () => {
      // A deleted object, an expired signed URL. A broken-image icon in a results list
      // looks like the whole site is broken.
      const row = await render();
      (row.querySelector('img') as HTMLImageElement).dispatchEvent(new Event('error'));
      await fixture.whenStable();

      expect(row.querySelector('img')).toBeNull();
      expect(row.textContent).toContain('Pas de photo');
    });
  });

  it('marks a professional seller, and only a professional one', async () => {
    expect((await render()).querySelector('.tag')?.textContent).toContain('Pro');

    const row = await render({ ...LISTING, seller: { role: 'PARTICULIER' } });
    expect(row.querySelector('.tag')).toBeNull();
  });

  it('survives a listing with almost nothing on it', async () => {
    const row = await render({ id: 'bare', title: 'Sans détail' });

    expect(row.querySelector('h2')?.textContent).toContain('Sans détail');
    expect(row.querySelector('app-price')).toBeNull();
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
      expect(links[0].textContent?.trim()).toBe('Peugeot 308 BlueHDi 130 Allure Pack');
      expect(links[0].closest('h2')).not.toBeNull();
    });

    it('is not offered for a listing that has no id to link to', async () => {
      const row = await render({ ...LISTING, id: undefined });

      expect(row.querySelector('a')).toBeNull();
      expect(row.querySelector('h2')?.textContent).toContain('Peugeot 308');
    });
  });

  describe('departmentOf', () => {
    it.each([
      ['69003', 'FR', '69'],
      ['75011', 'FR', '75'],
      ['01000', 'FR', '01'],
      // Overseas: three digits.
      ['97400', 'FR', '974'],
      ['98800', 'FR', '988'],
    ])('reads %s (%s) as %s', (postalCode, country, expected) => {
      expect(departmentOf(postalCode, country)).toBe(expected);
    });

    it.each([
      // Another country's postal code is not a département.
      ['10115', 'DE'],
      ['6900', 'FR'],
      ['SW1A 1AA', 'GB'],
      [undefined, 'FR'],
      ['69003', undefined],
    ])('gives nothing for %s (%s)', (postalCode, country) => {
      expect(departmentOf(postalCode, country)).toBeNull();
    });
  });
});
