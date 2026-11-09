import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Meta, Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, DealerPage as DealerPageView, ErrorCode } from '../../core/api';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { DealerPage, describe as describeDealer } from './dealer-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/listings`;

const DEALER: DealerPageView = {
  slug: 'garage-bellevue',
  sellerId: 's-1',
  name: 'Garage Bellevue',
  companyName: 'Garage Bellevue SARL',
  city: 'Lyon',
  postalCode: '69003',
  departement: '69',
  description: 'Garage indépendant à Lyon.\n\nEssai sur rendez-vous.',
  openingHours: 'Lundi au vendredi : 8 h 30 – 19 h\nSamedi : 9 h – 12 h',
  websiteUrl: 'https://www.garage-bellevue.example/',
  memberSince: '2021-03-04T10:00:00Z',
  saleListings: 12,
  leasingListings: 3,
  demo: false,
  own: false,
};

/** A dealer's page, through a real router: what it says, what it never says, and its listings. */
describe('DealerPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const settle = () => harness.fixture.whenStable();
  const words = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  async function visit(url: string, dealer: DealerPageView | null = DEALER, status = 200): Promise<void> {
    localStorage.clear();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'professionnels/:slug', component: DealerPage }], withComponentInputBinding()),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    const slug = url.split('?')[0].split('/').pop();
    const [request] = open(`${BASE}/api/v1/dealers/${slug}`);
    if (status === 200) {
      request.flush(dealer);
    } else {
      request.flush({ code: ErrorCode.NOT_FOUND, message: 'Dealer' }, { status, statusText: 'Not Found' });
    }
    await settle();
    open(`${BASE}/api/v1/reference/makes`).forEach((r) => r.flush([]));
    open(`${BASE}/api/v1/reference/enums`).forEach((r) => r.flush({ values: {} }));
    open(`${BASE}/api/v1/reference/countries`).forEach((r) =>
      r.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
    await settle();
  }

  it('says who the dealer is, where, since when, and what they have online', async () => {
    await visit('/professionnels/garage-bellevue');

    expect(root.querySelector('h1')?.textContent?.trim()).toBe('Garage Bellevue');
    expect(words(root.querySelector('[data-company]'))).toBe('Garage Bellevue SARL');
    const top = words(root.querySelector('.dealer__identity'));
    expect(top).toContain('Lyon — 69 · Rhône');
    expect(top).toContain('12 annonces en ligne · 3 offres de location');
    expect(top).toContain('Membre depuis mars 2021');
    // The dealer's own line breaks, as text, never as markup.
    expect(root.querySelector('[data-description]')?.textContent).toContain('Lyon.\n\nEssai');
    expect(root.querySelector('[data-hours]')?.textContent).toContain('Samedi : 9 h – 12 h');
    // Initials stand in for a logo.
    expect(root.querySelector('[data-initials]')?.textContent?.trim()).toBe('GB');
  });

  it('links the website as nofollow noopener, and shows no phone unless given, and never an e-mail', async () => {
    await visit('/professionnels/garage-bellevue');

    const website = root.querySelector('[data-website] a') as HTMLAnchorElement;
    expect(website.getAttribute('href')).toBe('https://www.garage-bellevue.example/');
    expect(website.getAttribute('rel')).toContain('nofollow');
    expect(website.getAttribute('rel')).toContain('noopener');
    expect(website.getAttribute('target')).toBe('_blank');
    expect(website.textContent?.trim()).toBe('www.garage-bellevue.example');
    expect(root.querySelector('[data-phone]')).toBeNull();
    expect(root.textContent).not.toContain('@');

    await visit('/professionnels/garage-bellevue', { ...DEALER, phone: '+33 4 72 00 00 00' });
    expect((root.querySelector('[data-phone] a') as HTMLAnchorElement).getAttribute('href')).toBe('tel:+33472000000');
  });

  it('lists the dealer’s listings through the search itself, sale or leasing, with its filters in the URL', async () => {
    await visit('/professionnels/garage-bellevue?prix_max=20000');

    const [search] = open(LIST);
    expect(search.request.params.get('sellerId')).toBe('s-1');
    expect(search.request.params.get('priceMax')).toBe('2000000');
    search.flush({ items: [], hasMore: false });
    open(`${LIST}/count`).forEach((r) => r.flush({ count: 0, capped: false }));
    await settle();

    // The search's own parts: the sale/leasing switch, the filters, the sort. Not the alert.
    expect(root.querySelector('app-offer-switch')).not.toBeNull();
    expect(root.querySelector('app-search-filters')).not.toBeNull();
    expect(root.querySelector('app-create-alert')).toBeNull();
    // The page's heading is the dealer's; the results' is the second level.
    expect(root.querySelectorAll('h1')).toHaveLength(1);
    expect(root.querySelector('#titre-resultats')?.tagName).toBe('H2');

    // Leasing, from the switch: the same page, the URL says so, and the search is asked again.
    (root.querySelector('app-offer-switch [data-offer="LEASING"]') as HTMLButtonElement).click();
    await settle();
    expect(TestBed.inject(Router).url).toContain('/professionnels/garage-bellevue?');
    expect(TestBed.inject(Router).url).toContain('offre=location');
    const [again] = open(LIST);
    expect(again.request.params.get('sellerId')).toBe('s-1');
    expect(again.request.params.get('offer')).toBe('LEASING');
  });

  it('describes itself to the tab and to a link preview', async () => {
    await visit('/professionnels/garage-bellevue');

    expect(TestBed.inject(Title).getTitle()).toBe('Garage Bellevue, professionnel à Lyon · AutoX');
    expect(TestBed.inject(Meta).getTag('name="description"')?.content).toContain('Garage indépendant à Lyon.');
    expect(TestBed.inject(Meta).getTag('property="og:title"')?.content).toBe('Garage Bellevue, professionnel à Lyon · AutoX');

    expect(describeDealer({ ...DEALER, description: undefined }).description).toBe(
      'Garage Bellevue, à Lyon : 15 annonces en ligne sur AutoX, coordonnées et horaires.',
    );
  });

  it('shows the dealer their hidden page with the reason, and the public a not-found', async () => {
    await visit('/professionnels/garage-bellevue', {
      ...DEALER,
      own: true,
      hiddenAt: '2026-11-08T10:00:00Z',
      hiddenReason: 'Annonces trompeuses',
    });
    expect(words(root.querySelector('[data-hidden]'))).toContain('Annonces trompeuses');
    expect(root.querySelector('a[href="/mon-compte/entreprise"]')).not.toBeNull();

    await visit('/professionnels/garage-masque', null, 404);
    expect(root.querySelector('app-not-found')).not.toBeNull();
    expect(root.textContent).toContain('Ce professionnel n’est pas sur AutoX');
  });

  it('offers AutoX’s messaging to write to the dealer, signed in first', async () => {
    await visit('/professionnels/garage-bellevue');

    const contact = root.querySelector('app-contact-seller a.btn') as HTMLAnchorElement;
    expect(contact.getAttribute('href')).toContain('/connexion');
    expect(decodeURIComponent(contact.getAttribute('href') ?? '')).toContain('/professionnels/garage-bellevue');
  });
});
