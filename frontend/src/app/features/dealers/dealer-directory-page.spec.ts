import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, convertToParamMap, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, DealerCard, DealerDirectory } from '../../core/api';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { initialsOf } from '../../shared/dealer/dealer-logo';
import { DealerDirectoryPage, parseDirectoryQuery, toDirectoryParams } from './dealer-directory-page';

const BASE = 'http://api.test';
const DIRECTORY = `${BASE}/api/v1/dealers`;

const card = (slug: string, over: Partial<DealerCard> = {}): DealerCard => ({
  slug,
  name: 'Garage Bellevue',
  city: 'Lyon',
  postalCode: '69003',
  departement: '69',
  saleListings: 12,
  leasingListings: 0,
  memberSince: '2021-03-04T10:00:00Z',
  ...over,
});

const page = (items: DealerCard[], over: Partial<DealerDirectory> = {}): DealerDirectory => ({
  items,
  page: 0,
  size: 24,
  totalElements: items.length,
  totalPages: items.length ? 1 : 0,
  hasNext: false,
  departements: [
    { code: '33', dealers: 2 },
    { code: '69', dealers: 1 },
  ],
  ...over,
});

/** The directory of professionals: its cards, and its filters, which are the URL. */
describe('DealerDirectoryPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const open = (): TestRequest[] =>
    backend.match((request) => request.url === DIRECTORY).filter((request) => !request.cancelled);
  const settle = () => harness.fixture.whenStable();
  const url = () => TestBed.inject(Router).url;

  async function visit(address: string, answer: DealerDirectory = page([card('garage-bellevue')])): Promise<TestRequest> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'professionnels', component: DealerDirectoryPage }]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(address);
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    const [request] = open();
    request.flush(answer);
    await settle();
    return request;
  }

  it('shows each dealer: initials without a logo, the town, what is online, and since when', async () => {
    await visit('/professionnels', page([
      card('garage-bellevue'),
      card('auto-atlantique', { name: 'Auto Atlantique', city: 'Bordeaux', departement: '33', saleListings: 1,
        leasingListings: 4, logoUrl: 'https://cdn.test/logo.jpg' }),
    ]));

    const cards = Array.from(root.querySelectorAll('[data-dealer]'));
    expect(cards).toHaveLength(2);
    expect(cards[0].getAttribute('href')).toBe('/professionnels/garage-bellevue');
    expect(cards[0].querySelector('[data-initials]')?.textContent?.trim()).toBe('GB');
    const first = cards[0].textContent?.replace(/\s+/g, ' ');
    expect(first).toContain('Garage Bellevue');
    expect(first).toContain('Lyon (69)');
    expect(first).toContain('12 annonces');
    expect(first).toContain('Membre depuis mars 2021');
    expect(cards[1].querySelector('img')?.getAttribute('src')).toBe('https://cdn.test/logo.jpg');
    expect(cards[1].querySelector('[data-listings]')?.textContent?.trim()).toBe('1 annonce · 4 offres de location');
    expect(root.querySelector('h2')?.textContent?.trim()).toBe('2 professionnels');
  });

  it('reads its filters from the URL and asks the directory for exactly those', async () => {
    const request = await visit('/professionnels?q=bellevue&departement=33&ville=merignac&tri=nom&page=2');

    expect(request.request.params.get('q')).toBe('bellevue');
    expect(request.request.params.get('departement')).toBe('33');
    expect(request.request.params.get('city')).toBe('merignac');
    expect(request.request.params.get('sort')).toBe('NAME');
    expect(request.request.params.get('page')).toBe('1');
    expect((root.querySelector('#pro-nom') as HTMLInputElement).value).toBe('bellevue');
    expect((root.querySelector('#pro-departement') as HTMLSelectElement).value).toBe('33');
    // The département choices are the ones with dealers, named.
    expect(Array.from(root.querySelectorAll('#pro-departement option')).map((o) => o.textContent?.trim())).toEqual([
      'Tous',
      '33 · Gironde (2)',
      '69 · Rhône (1)',
    ]);
  });

  it('puts a search, a département and the sort in the URL, back on the first page', async () => {
    await visit('/professionnels?page=3');

    (root.querySelector('#pro-nom') as HTMLInputElement).value = 'Écluse';
    (root.querySelector('#pro-ville') as HTMLInputElement).value = 'Mérignac';
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    expect(url()).toBe('/professionnels?q=%C3%89cluse&ville=M%C3%A9rignac');
    open()[0].flush(page([]));
    await settle();

    const departement = root.querySelector('#pro-departement') as HTMLSelectElement;
    departement.value = '33';
    departement.dispatchEvent(new Event('change'));
    await settle();
    expect(url()).toContain('departement=33');
    open()[0].flush(page([]));
    await settle();

    const sort = root.querySelector('#pro-tri') as HTMLSelectElement;
    sort.value = 'nom';
    sort.dispatchEvent(new Event('change'));
    await settle();
    expect(url()).toContain('tri=nom');
  });

  it('pages through the list', async () => {
    await visit('/professionnels', page([card('a')], { totalElements: 30, totalPages: 2, hasNext: true }));

    expect(root.querySelector('[data-page-of]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Page 1 / 2');
    expect((root.querySelector('[data-page="previous"]') as HTMLButtonElement).disabled).toBe(true);
    (root.querySelector('[data-page="next"]') as HTMLButtonElement).click();
    await settle();
    expect(url()).toBe('/professionnels?page=2');
    expect(open()[0].request.params.get('page')).toBe('1');
  });

  it('says when nothing matches, and offers to clear the search', async () => {
    await visit('/professionnels?q=personne', page([]));

    expect(root.querySelector('[data-empty]')?.textContent).toContain('Aucun professionnel ne correspond');
    (Array.from(root.querySelectorAll('[data-empty] button'))[0] as HTMLButtonElement).click();
    await settle();
    expect(url()).toBe('/professionnels');
  });

  it('reads a URL leniently and writes only what differs from the defaults', () => {
    expect(parseDirectoryQuery(convertToParamMap({ tri: 'n’importe', page: '-4', departement: '2a' }))).toEqual({
      q: '',
      departement: '2A',
      ville: '',
      tri: 'annonces',
      page: 1,
    });
    expect(toDirectoryParams({ q: '', departement: '', ville: '', tri: 'annonces', page: 1 })).toEqual({});
  });

  it('makes initials from the first two words', () => {
    expect(initialsOf('Garage Bellevue')).toBe('GB');
    expect(initialsOf('Nord VO')).toBe('NV');
    expect(initialsOf('Écluse & Fils')).toBe('ÉF');
    expect(initialsOf('Autosphère')).toBe('A');
    expect(initialsOf('')).toBe('?');
  });
});
