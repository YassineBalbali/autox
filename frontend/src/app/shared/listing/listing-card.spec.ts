import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { PriceLevel, PublicListingSummary } from '../../core/api';
import { NBSP } from '../format/number';
import { ListingCard } from './listing-card';
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
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:44:20Z',
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
  photoCount: 5,
  coverImage: { CARD: { url: 'http://img.test/card.jpg', width: 800, height: 600 } },
};

@Component({
  imports: [ListingCard, ListingRow],
  template: `
    <app-listing-card [listing]="listing()" fuelLabel="Diesel" gearboxLabel="Manuelle" />
    <app-listing-row [listing]="listing()" fuelLabel="Diesel" gearboxLabel="Manuelle" />
  `,
})
class Host {
  readonly listing = signal<PublicListingSummary>(LISTING);
}

/**
 * The card view of a result.
 *
 * <p>Rendered beside the row for the same listing, because the promise of the view toggle
 * is that it changes the arrangement and nothing else: same price, same facts, same
 * picture. A card that formatted the price its own way, or chose a different image, would
 * make the two views disagree about one car.
 */
describe('ListingCard', () => {
  let fixture: ComponentFixture<Host>;

  afterEach(() => localStorage.clear());

  async function render(listing: PublicListingSummary = LISTING) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    return {
      card: root.querySelector('app-listing-card') as HTMLElement,
      row: root.querySelector('app-listing-row') as HTMLElement,
    };
  }

  it('reads photo, heading, facts, price, then place and seller — in that order', async () => {
    const { card } = await render();
    const body = card.querySelector('.card__body') as HTMLElement;

    expect((card.querySelector('article') as HTMLElement).firstElementChild?.tagName.toLowerCase()).toBe(
      'app-listing-thumb',
    );
    expect(Array.from(body.children).map((child) => child.classList[0] ?? child.tagName.toLowerCase())).toEqual([
      'card__title',
      'app-spec-facts',
      'card__price',
      'card__foot',
    ]);
  });

  it('lays the facts out two by two, and the price large', async () => {
    const { card } = await render();

    expect(card.querySelector('.facts')?.classList).toContain('facts--grid');
    expect(card.querySelector('app-price')?.textContent?.trim()).toBe(`12${NBSP}500${NBSP}€`);
    expect(card.querySelector('.price')?.classList).toContain('price--result');
  });

  it('says where the car is, and what kind of seller has it', async () => {
    const { card } = await render();
    const foot = card.querySelector('.card__foot') as HTMLElement;

    expect(foot.querySelector('.card__place')?.textContent?.trim()).toBe('Lyon (69)');
    expect(foot.querySelector('app-seller-badge')?.textContent?.trim()).toBe('Pro');

    const { card: private_ } = await render({ ...LISTING, seller: { role: 'PARTICULIER' } });
    expect(private_.querySelector('app-seller-badge')?.textContent?.trim()).toBe('Particulier');
  });

  it('keeps the version line when there is no version, so the facts line up across a row', async () => {
    const { card } = await render({ ...LISTING, version: undefined });

    expect(card.querySelector('.card__version')).not.toBeNull();
    expect(card.querySelector('.card__version')?.textContent?.trim()).toBe('');
  });

  describe('agrees with the row about the same listing', () => {
    it('shows the same heading, price, facts, picture, count and place', async () => {
      const { card, row } = await render();
      const same = (selector: string) =>
        expect(card.querySelector(selector)?.textContent?.trim()).toBe(row.querySelector(selector)?.textContent?.trim());

      same('h2 a');
      same('app-price');
      same('.thumb__count');
      same('app-seller-badge');
      expect(card.querySelector('.card__place')?.textContent?.trim()).toBe(
        row.querySelector('.listing__place')?.textContent?.trim(),
      );
      // The card has room for four facts; the row adds the power.
      const facts = (root: HTMLElement) =>
        Array.from(root.querySelectorAll('.facts__text')).map((fact) => fact.textContent);
      expect(facts(row).slice(0, 4)).toEqual(facts(card));
      expect(card.querySelector('img')?.getAttribute('src')).toBe(row.querySelector('img')?.getAttribute('src'));
    });

    it('shares the heart: marking one marks the other', async () => {
      const { card, row } = await render();

      (card.querySelector('app-favourite-button button') as HTMLButtonElement).click();
      await fixture.whenStable();

      expect(row.querySelector('app-favourite-button button')?.getAttribute('aria-pressed')).toBe('true');
    });
  });

  it('puts the price verdict beside the price, and the row puts it under it', async () => {
    const { card, row } = await render({
      ...LISTING,
      priceIndicator: { level: PriceLevel.ABOVE_MARKET, medianPriceAmount: 1100000, comparableCount: 5 },
    });

    expect(card.querySelector('.card__price app-price-badge')?.textContent).toContain('Au-dessus du marché');
    expect(row.querySelector('.listing__aside app-price-badge')?.textContent).toContain('Au-dessus du marché');
  });

  it('survives a listing with almost nothing on it', async () => {
    const { card } = await render({ id: 'bare', title: 'Sans détail' });

    expect(card.querySelector('h2')?.textContent).toContain('Sans détail');
    expect(card.querySelector('app-price')).toBeNull();
    expect(card.querySelector('.card__place')).toBeNull();
    expect(card.textContent).not.toContain('undefined');
    expect(card.textContent).not.toContain('null');
  });

  it('links to the listing through its title, and to the same place as the row', async () => {
    const { card, row } = await render();
    const links = card.querySelectorAll('a');

    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe('/annonces/l-1');
    expect(links[0].getAttribute('href')).toBe(row.querySelector('a')?.getAttribute('href'));
  });
});
