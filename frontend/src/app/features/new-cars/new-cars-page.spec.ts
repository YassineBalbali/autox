import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, PublicListingSummary } from '../../core/api';
import { NewCarsPage } from './new-cars-page';

const BASE = 'http://api.test';
const LISTINGS = `${BASE}/api/v1/listings`;
const COUNT = `${LISTINGS}/count`;
const MAKE_COUNTS = `${LISTINGS}/makes`;
const MAKES = [
  { id: 'm-peugeot', slug: 'peugeot', name: 'Peugeot', popular: true },
  { id: 'm-kia', slug: 'kia', name: 'Kia', popular: false },
];
const COUNTRIES = [{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }];

const card = (id: string): PublicListingSummary => ({
  id,
  title: `Peugeot 208 ${id}`,
  priceAmount: 2_545_000,
  currencyCode: 'EUR',
  make: 'Peugeot',
  model: '208',
  firstRegistrationYear: 2026,
  mileageKm: 8,
  fuelType: 'HYBRID_PETROL',
  condition: 'NEW',
  soldNew: true,
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
});

@Component({ selector: 'app-results-stub', template: '<p>résultats</p>' })
class ResultsStub {}

/**
 * The "Neuf" page: the search preset to new cars. Every request it makes carries the search's
 * own `condition=NEW`, every link lands on the search with `etat=new`, and a make's count is
 * the API's, for the same filters.
 */
describe('NewCarsPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const conditionOf = (request: TestRequest) => request.request.params.getAll('condition');
  const settle = async () => {
    await vi.advanceTimersByTimeAsync(900);
    await harness.fixture.whenStable();
  };

  async function visit(): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'neuf', component: NewCarsPage },
          { path: 'recherche', component: ResultsStub },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/neuf');
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    open(`${BASE}/api/v1/reference/makes`).forEach((request) => request.flush(MAKES));
    open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
    await settle();
  }

  /** Answers what the page asked, in the waves it asks in. */
  async function answer(makes: object[], items: PublicListingSummary[], total = 16): Promise<TestRequest[]> {
    const seen: TestRequest[] = [];
    for (let wave = 0; wave < 6; wave++) {
      for (const request of open(MAKE_COUNTS)) {
        seen.push(request);
        request.flush(makes);
      }
      for (const request of open(LISTINGS)) {
        seen.push(request);
        request.flush({ items });
      }
      for (const request of open(COUNT)) {
        seen.push(request);
        request.flush({ count: total, capped: false });
      }
      await settle();
    }
    return seen;
  }

  const hrefs = (selector: string) =>
    Array.from(root.querySelectorAll<HTMLAnchorElement>(selector)).map((link) => decodeURIComponent(link.getAttribute('href') ?? ''));

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('asks for new cars only, by the search’s own filter, everywhere', async () => {
    await visit();
    const requests = await answer([{ slug: 'peugeot', name: 'Peugeot', count: 3 }], [card('a')]);

    expect(requests.length).toBeGreaterThanOrEqual(3);
    expect(requests.map(conditionOf)).toEqual(requests.map(() => ['NEW']));
    // The latest first.
    expect(requests.find((request) => request.request.url === LISTINGS)?.request.params.get('sort')).toBe('NEWEST');
  });

  it('lists the makes with new stock, each with the API’s count, leading to that search', async () => {
    await visit();
    await answer(
      [
        { slug: 'peugeot', name: 'Peugeot', count: 3 },
        { slug: 'kia', name: 'Kia', count: 1 },
      ],
      [card('a')],
    );

    const tiles = Array.from(root.querySelectorAll('[data-section="makes"] a.tile'));
    expect(tiles.map((tile) => tile.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      expect.stringMatching(/^Peugeot.*3/),
      expect.stringMatching(/^Kia.*1/),
    ]);
    expect(hrefs('[data-section="makes"] a.tile')).toEqual(['/recherche?marque=peugeot&etat=new', '/recherche?marque=kia&etat=new']);
  });

  it('shows the latest arrivals as the search’s cards, marked new, and leads to all of them', async () => {
    await visit();
    await answer([{ slug: 'peugeot', name: 'Peugeot', count: 2 }], [card('a'), card('b')], 16);

    const cards = root.querySelectorAll('[data-section="latest"] app-listing-card, [data-section="latest"] app-listing-row');
    expect(cards).toHaveLength(2);
    expect(cards[0].textContent).toContain('Neuf');
    expect(cards[0].textContent).not.toMatch(/8\s*km/);
    const all = root.querySelector('[data-link="all"]');
    expect(all?.textContent?.trim()).toBe('Voir les 16 voitures neuves');
    expect(hrefs('[data-link="all"]')).toEqual(['/recherche?etat=new']);
  });

  it('runs the home page’s search card preset to new cars, without the offer tabs', async () => {
    await visit();
    await answer([], [card('a')], 16);

    expect(root.querySelector('app-home-finder .finder__tabs')).toBeNull();
    expect(root.querySelector('app-home-finder button[type="submit"]')?.textContent).toContain('Rechercher (16)');
    expect(hrefs('app-home-finder .finder__more')).toEqual(['/recherche?etat=new']);
  });

  it('says so when there is no new car online, and shows no empty sections', async () => {
    await visit();
    await answer([], [], 0);

    expect(root.querySelector('[data-state="empty"]')?.textContent?.trim()).toBe(
      'Aucune voiture neuve n’est en ligne pour le moment.',
    );
    expect(root.querySelector('[data-section="makes"]')).toBeNull();
    expect(root.querySelector('[data-section="latest"]')).toBeNull();
  });
});
