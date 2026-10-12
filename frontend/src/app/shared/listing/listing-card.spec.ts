import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { PublicListingSummary } from '../../core/api';
import { NBSP } from '../format/number';
import { ListingCard } from './listing-card';
import { ListingRow } from './listing-row';

const LISTING: PublicListingSummary = {
  id: 'l-1',
  title: 'Peugeot 308 BlueHDi 130 Allure Pack',
  priceAmount: 1250000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2021,
  mileageKm: 62400,
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:44:20Z',
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
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

  async function render(listing: PublicListingSummary = LISTING) {
    fixture = TestBed.createComponent(Host);
    fixture.componentInstance.listing.set(listing);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    return {
      card: root.querySelector('app-listing-card') as HTMLElement,
      row: root.querySelector('app-listing-row') as HTMLElement,
    };
  }

  it('reads photo, price, title, spec line, seller — in that order', async () => {
    const { card } = await render();
    const body = card.querySelector('.card__body') as HTMLElement;

    expect((card.querySelector('article') as HTMLElement).firstElementChild?.className).toBe('card__photo');
    expect(Array.from(body.children).map((child) => child.tagName.toLowerCase())).toEqual([
      'app-price',
      'h2',
      'app-spec-line',
      'p',
    ]);
  });

  it('puts the price above the title, the most prominent place a card has', async () => {
    // A row gives the price its own column on the right. A card has no right, so the
    // price takes the first line under the photo — on every card.
    const { card } = await render();
    const price = card.querySelector('app-price') as HTMLElement;
    const title = card.querySelector('h2') as HTMLElement;

    expect(price.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(price.textContent?.trim()).toBe(`12${NBSP}500${NBSP}€`);
  });

  it('says who is selling, and from where', async () => {
    const { card } = await render();
    const seller = card.querySelector('.card__seller') as HTMLElement;

    expect(seller.textContent).toContain('Garage Bellevue');
    expect(seller.textContent).toContain('Lyon (69)');
    expect(seller.querySelector('.tag')?.textContent).toContain('Pro');
  });

  it('does not mark a private seller as a professional', async () => {
    const { card } = await render({ ...LISTING, seller: { displayName: 'Camille R.', role: 'PARTICULIER' } });

    expect(card.querySelector('.tag')).toBeNull();
    expect(card.textContent).toContain('Camille R.');
  });

  describe('agrees with the row about the same listing', () => {
    it('shows the same price, the same facts and the same picture', async () => {
      const { card, row } = await render();

      expect(card.querySelector('app-price')?.textContent).toBe(row.querySelector('app-price')?.textContent);
      expect(card.querySelector('app-spec-line')?.textContent).toBe(
        row.querySelector('app-spec-line')?.textContent,
      );
      expect(card.querySelector('img')?.getAttribute('src')).toBe(row.querySelector('img')?.getAttribute('src'));
      expect(card.querySelector('h2')?.textContent?.trim()).toBe(row.querySelector('h2')?.textContent?.trim());
    });
  });

  it('shows a placeholder in the same box when there is no photo', async () => {
    const { card } = await render({ ...LISTING, coverImage: {} });

    expect(card.querySelector('img')).toBeNull();
    expect(card.querySelector('.card__photo')?.textContent).toContain('Pas de photo');
  });

  it('falls back to the placeholder when the image fails to load', async () => {
    const { card } = await render();
    (card.querySelector('img') as HTMLImageElement).dispatchEvent(new Event('error'));
    await fixture.whenStable();

    expect(card.querySelector('img')).toBeNull();
  });

  it('survives a listing with almost nothing on it', async () => {
    const { card } = await render({ id: 'bare', title: 'Sans détail' });

    expect(card.querySelector('h2')?.textContent).toContain('Sans détail');
    expect(card.querySelector('app-price')).toBeNull();
    expect(card.textContent).not.toContain('undefined');
    expect(card.textContent).not.toContain('null');
  });

  it('is not a link, because there is no listing page to go to yet', async () => {
    expect((await render()).card.querySelector('a')).toBeNull();
  });
});
