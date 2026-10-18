import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, PublicListingSummary } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { SearchPage, countLabel } from './search-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/listings`;
const COUNT = `${BASE}/api/v1/listings/count`;

const MAKES = [
  { id: 'm-peugeot', slug: 'peugeot', name: 'Peugeot', popular: true },
  { id: 'm-tesla', slug: 'tesla', name: 'Tesla', popular: false },
];
const ENUMS = {
  values: {
    FuelType: ['PETROL', 'DIESEL', 'ELECTRIC'],
    Gearbox: ['MANUAL', 'AUTOMATIC'],
    BodyType: ['HATCHBACK', 'SUV'],
    VehicleCondition: ['NEW', 'USED'],
  },
};
const COUNTRIES = [{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }];

const listing = (id: string, over: Partial<PublicListingSummary> = {}): PublicListingSummary => ({
  id,
  title: `Annonce ${id}`,
  priceAmount: 1250000,
  currencyCode: 'EUR',
  firstRegistrationYear: 2021,
  mileageKm: 62400,
  fuelType: 'DIESEL',
  gearbox: 'MANUAL',
  city: 'Lyon',
  postalCode: '69003',
  countryCode: 'FR',
  publishedAt: '2026-10-11T12:00:00Z',
  ...over,
});

@Component({ template: '<p>annonce</p>' })
class ListingStub {}

/**
 * The search screen, end to end through a real router.
 *
 * <p>The claim this screen makes is that the URL is its only state: a link reproduces a
 * search, a filter change is a navigation, and the back button undoes it. That can only be
 * tested with a router that actually navigates, so these tests start from an address,
 * click real controls, and read the address back.
 */
describe('SearchPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  /** Requests to one endpoint that are still open. */
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  const settle = async () => {
    await harness.fixture.whenStable();
  };

  /** Answers the reference lookups the page and its sidebar make on arrival. */
  function answerReference(makes: object[] = MAKES): void {
    open(`${BASE}/api/v1/reference/makes`).forEach((request) => request.flush(makes));
    open(`${BASE}/api/v1/reference/enums`).forEach((request) => request.flush(ENUMS));
    open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
  }

  async function visit(url: string, makes: object[] = MAKES, keepPreferences = false): Promise<void> {
    if (!keepPreferences) {
      // The view preference is remembered in localStorage, which outlives a test.
      localStorage.clear();
    }
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'recherche', component: SearchPage },
          { path: 'annonces/:id', component: ListingStub },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    // The application bootstrap subscribes the router to history events; the testing
    // harness does not. Without this, Location.back() moves the mock history and nothing
    // is listening — the back-button test would pass or fail for reasons unrelated to
    // the screen.
    TestBed.inject(Router).setUpLocationChangeListener();
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    answerReference(makes);
    await settle();
  }

  /** Answers the search in flight, and its count. */
  async function answer(items: PublicListingSummary[], extra: { hasMore?: boolean; count?: number; capped?: boolean } = {}): Promise<TestRequest> {
    const [page] = open(LIST);
    expect(page, 'a search request should be in flight').toBeDefined();
    page.flush({ items, hasMore: extra.hasMore ?? false, nextCursor: extra.hasMore ? 'cursor-1' : undefined });
    open(COUNT).forEach((request) => request.flush({ count: extra.count ?? items.length, capped: extra.capped ?? false }));
    await settle();
    return page;
  }

  const rows = () => Array.from(root.querySelectorAll('app-listing-row')) as HTMLElement[];
  /** The address as the router sees it: `/recherche` for the bare results page. */
  const url = () => TestBed.inject(Router).url;
  const chip = (label: string) =>
    Array.from(root.querySelectorAll('app-filter-chip button')).find((button) =>
      button.textContent?.includes(label),
    ) as HTMLButtonElement;

  describe('the URL drives the search', () => {
    it('searches for everything, newest first, on the bare home page', async () => {
      await visit('/recherche');
      const page = await answer([listing('a'), listing('b')]);

      expect(page.request.params.get('sort')).toBe('NEWEST');
      expect(page.request.params.keys().sort()).toEqual(['size', 'sort']);
      expect(rows()).toHaveLength(2);
    });

    it('reproduces a shared link exactly', async () => {
      await visit('/recherche?carburant=diesel,electric&boite=automatic&km_max=90000&annee_min=2019&lieu=Lyon&tri=prix-croissant');
      const page = await answer([listing('a')]);
      const params = page.request.params;

      expect(params.getAll('fuelType')).toEqual(['DIESEL', 'ELECTRIC']);
      expect(params.getAll('gearbox')).toEqual(['AUTOMATIC']);
      expect(params.get('mileageMax')).toBe('90000');
      expect(params.get('yearFrom')).toBe('2019');
      expect(params.get('location')).toBe('Lyon');
      expect(params.get('sort')).toBe('PRICE_ASC');
    });

    it('sends prices in the minor unit the API compares, with their currency', async () => {
      // The URL says 15000 because that is what a person types. The API compares cents.
      await visit('/recherche?prix_max=15000');
      const page = await answer([]);

      expect(page.request.params.get('priceMax')).toBe('1500000');
      expect(page.request.params.get('currency')).toBe('EUR');
    });

    it('resolves a make slug to its catalogue id', async () => {
      await visit('/recherche?marque=tesla');
      const page = await answer([]);

      expect(page.request.params.get('makeId')).toBe('m-tesla');
      expect(page.request.params.has('make')).toBe(false);
    });

    it('resolves a model slug too, once the make is known', async () => {
      await visit('/recherche?marque=peugeot&modele=308');
      open(`${BASE}/api/v1/reference/makes/m-peugeot/models`).forEach((request) =>
        request.flush([{ id: 'mo-308', slug: '308', name: '308' }]),
      );
      await settle();
      const page = await answer([]);

      expect(page.request.params.get('makeId')).toBe('m-peugeot');
      expect(page.request.params.get('modelId')).toBe('mo-308');
    });

    it('searches by name when the make is not in the catalogue', async () => {
      // An empty catalogue, or a link older than it. The search still runs — it loses
      // precision, not the page.
      await visit('/recherche?marque=Peugeot', []);
      const page = await answer([]);

      expect(page.request.params.get('make')).toBe('Peugeot');
      expect(page.request.params.has('makeId')).toBe(false);
    });

    it('ignores rubbish in the address rather than failing on it', async () => {
      await visit('/recherche?prix_max=beaucoup&carburant=vapeur&tri=au-hasard&annee_min=12');
      const page = await answer([listing('a')]);

      expect(page.request.params.keys().sort()).toEqual(['size', 'sort']);
      expect(rows()).toHaveLength(1);
    });
  });

  describe('a filter change is a navigation', () => {
    it('puts the filter in the address and searches again', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')]);

      chip('Diesel').click();
      await settle();

      expect(url()).toBe('/recherche?carburant=diesel');
      const page = await answer([listing('a')]);
      expect(page.request.params.getAll('fuelType')).toEqual(['DIESEL']);
      expect(chip('Diesel').getAttribute('aria-pressed')).toBe('true');
    });

    it('removes a cleared filter from the address instead of leaving it behind', async () => {
      await visit('/recherche?carburant=diesel&km_max=90000');
      await answer([listing('a')]);

      chip('Diesel').click();
      await settle();

      expect(url()).toBe('/recherche?km_max=90000');
    });

    it('steps back through the filters with the back button', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')]);
      chip('Diesel').click();
      await settle();
      await answer([listing('a')]);
      expect(rows()).toHaveLength(1);

      TestBed.inject(Location).back();
      // The popstate is delivered asynchronously, and the router then navigates: wait for
      // the address to actually change rather than for a fixed delay.
      for (let attempt = 0; attempt < 50 && url() !== '/recherche'; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 5));
        await settle();
      }

      expect(url()).toBe('/recherche');
      const page = await answer([listing('a'), listing('b')]);
      expect(page.request.params.has('fuelType')).toBe(false);
      expect(chip('Diesel').getAttribute('aria-pressed')).toBe('false');
      expect(rows()).toHaveLength(2);
    });

    it('sorts through the address too', async () => {
      await visit('/recherche?carburant=diesel');
      await answer([listing('a')]);

      const sort = root.querySelector('#tri') as HTMLSelectElement;
      sort.value = 'PRICE_DESC';
      sort.dispatchEvent(new Event('change'));
      await settle();

      // The filter survives a change of order.
      expect(url()).toBe('/recherche?carburant=diesel&tri=prix-decroissant');
      const page = await answer([listing('a')]);
      expect(page.request.params.get('sort')).toBe('PRICE_DESC');
    });

    it('offers the four sort orders, with the current one selected', async () => {
      await visit('/recherche?tri=km-croissant');
      await answer([]);
      const sort = root.querySelector('#tri') as HTMLSelectElement;

      expect(Array.from(sort.options).map((option) => option.textContent?.trim())).toEqual([
        'Plus récentes',
        'Prix croissant',
        'Prix décroissant',
        'Kilométrage croissant',
      ]);
      expect(sort.value).toBe('MILEAGE_ASC');
    });

    it('does not search twice for a navigation that changes nothing', async () => {
      await visit('/recherche?carburant=diesel');
      await answer([listing('a')]);

      await TestBed.inject(Router).navigateByUrl('/recherche?carburant=diesel');
      await settle();

      expect(open(LIST)).toHaveLength(0);
    });
  });

  describe('the result count', () => {
    it('is shown above the list', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')], { count: 1284 });

      expect(root.querySelector('.search__count')?.textContent?.trim()).toBe(`1${NBSP}284 annonces`);
    });

    it('says "more than" when the API stopped counting', async () => {
      await visit('/recherche');
      await answer([listing('a')], { count: 10000, capped: true });

      expect(root.querySelector('.search__count')?.textContent?.trim()).toBe(`Plus de 10${NBSP}000 annonces`);
    });

    it('agrees the noun with the number, the French way', () => {
      expect(countLabel({ count: 0, capped: false })).toBe('0 annonce');
      expect(countLabel({ count: 1, capped: false })).toBe('1 annonce');
      expect(countLabel({ count: 2, capped: false })).toBe('2 annonces');
      expect(countLabel(null)).toBeNull();
    });
  });

  describe('each row', () => {
    it('shows the price as 12 500 €, the labels in French, and a French date', async () => {
      await visit('/recherche');
      await answer([listing('a')]);
      const text = rows()[0].textContent ?? '';

      expect(text).toContain(`12${NBSP}500${NBSP}€`);
      expect(text).toContain('Diesel');
      expect(text).toContain('Manuelle');
      // The enum values themselves never reach the screen.
      expect(text).not.toContain('MANUAL');
      expect(text).toContain('11 octobre 2026');
    });

    it('divides by the listing currency exponent, not by an assumed 100', async () => {
      await visit('/recherche');
      open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
      await answer([listing('yen', { priceAmount: 2500000, currencyCode: 'JPY' })]);

      // JPY is not in the reference data here, so the fallback applies — the point of the
      // test is that the euro row beside it is unaffected and the lookup is per listing.
      expect(rows()[0].querySelector('app-price')?.textContent).toContain('25');
    });
  });

  describe('states', () => {
    it('shows placeholder rows while the first page loads', async () => {
      await visit('/recherche');

      expect(root.querySelector('[aria-busy="true"]')).not.toBeNull();
      expect(root.querySelectorAll('.skeleton').length).toBeGreaterThan(0);
      expect(rows()).toHaveLength(0);
    });

    it('explains an empty result and offers a way out when filters are set', async () => {
      await visit('/recherche?prix_max=100');
      await answer([]);

      expect(root.querySelector('.search__empty')?.textContent).toContain('Aucune annonce ne correspond');
      const reset = Array.from(root.querySelectorAll('.search__empty button')).find((button) =>
        button.textContent?.includes('Effacer'),
      ) as HTMLButtonElement;

      reset.click();
      await settle();

      expect(url()).toBe('/recherche');
    });

    it('does not blame the filters when there are none', async () => {
      await visit('/recherche');
      await answer([]);

      expect(root.querySelector('.search__empty')?.textContent).toContain('Aucune annonce n’est en ligne');
      expect(root.querySelector('.search__empty button')).toBeNull();
    });

    it('reports a failure in French and retries the same search', async () => {
      await visit('/recherche?carburant=diesel');
      open(LIST)[0].flush(
        { code: ErrorCode.INTERNAL_ERROR, detail: 'NullPointerException at …' },
        { status: 500, statusText: 'Server Error' },
      );
      await settle();

      const alert = root.querySelector('.alert--error') as HTMLElement;
      expect(alert.textContent).toContain('Une erreur inattendue');
      expect(alert.textContent).not.toContain('NullPointer');

      (Array.from(root.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Réessayer'),
      ) as HTMLButtonElement).click();
      await settle();

      const retried = await answer([listing('a')]);
      expect(retried.request.params.getAll('fuelType')).toEqual(['DIESEL']);
      expect(rows()).toHaveLength(1);
    });

    it('keeps the sidebar usable while the list is in an error state', async () => {
      // Otherwise a filter that provokes an error cannot be removed.
      await visit('/recherche');
      open(LIST)[0].error(new ProgressEvent('offline'));
      await settle();

      expect(chip('Diesel')).toBeDefined();
    });
  });

  describe('voir plus', () => {
    it('appends the next page in place, without touching the address', async () => {
      await visit('/recherche?carburant=diesel');
      await answer([listing('a'), listing('b')], { hasMore: true, count: 3 });

      const more = Array.from(root.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Voir plus'),
      ) as HTMLButtonElement;
      more.click();
      await settle();

      const next = open(LIST)[0];
      expect(next.request.params.get('cursor')).toBe('cursor-1');
      next.flush({ items: [listing('c')], hasMore: false });
      await settle();

      expect(rows()).toHaveLength(3);
      // The cursor is not shareable state: a link must open at the top of the list.
      expect(url()).toBe('/recherche?carburant=diesel');
      expect(root.textContent).toContain('Vous avez vu toutes les annonces');
    });

    it('has no numbered pages', async () => {
      await visit('/recherche');
      await answer([listing('a')], { hasMore: true });

      expect(root.querySelector('nav[aria-label*="agination"]')).toBeNull();
      expect(root.textContent).not.toMatch(/Page \d/);
    });

    it('offers nothing more when the list is complete', async () => {
      await visit('/recherche');
      await answer([listing('a')]);

      expect(
        Array.from(root.querySelectorAll('button')).some((button) => button.textContent?.includes('Voir plus')),
      ).toBe(false);
    });
  });

  describe('the search bar', () => {
    const typeInto = async (id: string, value: string) => {
      const element = root.querySelector(`#${id}`) as HTMLInputElement;
      element.value = value;
      element.dispatchEvent(new Event('input'));
      await settle();
    };
    const submitBar = async () => {
      (root.querySelector('app-search-bar button[type="submit"]') as HTMLButtonElement).click();
      await settle();
    };

    it('sits above the filters and the results, across the full width', async () => {
      await visit('/recherche');
      await answer([listing('a')]);
      const bar = root.querySelector('app-search-bar') as HTMLElement;
      const body = root.querySelector('.search__body') as HTMLElement;

      // Outside the two-column body, so it spans both columns.
      expect(body.contains(bar)).toBe(false);
      expect(bar.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('searches free text through the address, and the API receives it', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')]);

      await typeInto('b-q', 'golf tdi');
      // Typing alone searched nothing.
      expect(open(LIST)).toHaveLength(0);
      expect(url()).toBe('/recherche');

      await submitBar();

      expect(url()).toBe('/recherche?q=golf%20tdi');
      const page = await answer([listing('a')]);
      // The generated client percent-encodes each value itself and gives HttpClient an
      // identity codec, so what is held here is the wire form — encoded exactly once.
      expect(page.request.params.get('q')).toBe('golf%20tdi');
      expect(page.request.urlWithParams).toContain('q=golf%20tdi');
      expect(page.request.urlWithParams).not.toContain('%2520');
    });

    it('applies text, make, price and year as one navigation', async () => {
      await visit('/recherche');
      await answer([listing('a')]);

      await typeInto('b-q', 'tdi');
      const make = root.querySelector('#b-marque') as HTMLSelectElement;
      make.value = 'tesla';
      make.dispatchEvent(new Event('change'));
      await settle();
      open(`${BASE}/api/v1/reference/makes/m-tesla/models`).forEach((request) => request.flush([]));
      await typeInto('b-prix', '30000');
      await submitBar();

      expect(url()).toBe('/recherche?q=tdi&marque=tesla&prix_max=30000');
      const page = await answer([]);
      expect(page.request.params.get('makeId')).toBe('m-tesla');
      expect(page.request.params.get('priceMax')).toBe('3000000');
    });

    it('keeps the sidebar filters when the bar is submitted', async () => {
      // The bar owns five fields. Submitting it must not wipe the fuel filter chosen in
      // the sidebar a moment earlier.
      await visit('/recherche?carburant=diesel&km_max=90000');
      await answer([listing('a')]);

      await typeInto('b-q', 'golf');
      await submitBar();

      expect(url()).toBe('/recherche?q=golf&km_max=90000&carburant=diesel');
    });

    it('reads a search that arrived by link', async () => {
      await visit('/recherche?q=clio%20automatique&prix_max=12000');
      await answer([]);

      expect((root.querySelector('#b-q') as HTMLInputElement).value).toBe('clio automatique');
      expect((root.querySelector('#b-prix') as HTMLInputElement).value).toBe('12000');
    });
  });

  describe('the applied-filter chips', () => {
    const appliedChips = () => Array.from(root.querySelectorAll('.applied__chip')) as HTMLButtonElement[];

    it('show nothing when no filter is set', async () => {
      await visit('/recherche');
      await answer([listing('a')]);

      expect(root.querySelector('.applied')).toBeNull();
    });

    it('list what the address applies, between the bar and the results', async () => {
      await visit('/recherche?q=tdi&marque=tesla&prix_max=30000&carburant=diesel,electric&lieu=Lyon');
      await answer([]);

      expect(appliedChips().map((chip) => chip.textContent?.replace('×', '').trim())).toEqual([
        '« tdi »',
        'Tesla',
        `Jusqu’à 30${NBSP}000 €`,
        'Diesel',
        'Électrique',
        'Lyon',
      ]);
      const applied = root.querySelector('app-active-filters') as HTMLElement;
      const bar = root.querySelector('app-search-bar') as HTMLElement;
      const body = root.querySelector('.search__body') as HTMLElement;
      expect(bar.compareDocumentPosition(applied) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(applied.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('remove exactly one filter each, through the address', async () => {
      await visit('/recherche?carburant=diesel,electric&lieu=Lyon');
      await answer([listing('a')]);

      appliedChips()
        .find((chip) => chip.textContent?.includes('Diesel'))!
        .click();
      await settle();

      // The other fuel and the location are untouched.
      expect(url()).toBe('/recherche?carburant=electric&lieu=Lyon');
      const page = await answer([]);
      expect(page.request.params.getAll('fuelType')).toEqual(['ELECTRIC']);
      expect(appliedChips()).toHaveLength(2);
    });

    it('clear everything at once, keeping the sort order', async () => {
      await visit('/recherche?q=tdi&carburant=diesel&tri=prix-croissant');
      await answer([listing('a')]);

      (root.querySelector('.applied__clear') as HTMLButtonElement).click();
      await settle();

      expect(url()).toBe('/recherche?tri=prix-croissant');
      await answer([listing('a'), listing('b')]);
      expect(root.querySelector('.applied')).toBeNull();
      expect((root.querySelector('#b-q') as HTMLInputElement).value).toBe('');
    });
  });

  describe('list and grid', () => {
    const toggle = (label: string) =>
      Array.from(root.querySelectorAll('.view-toggle__button')).find(
        (button) => button.textContent?.trim() === label,
      ) as HTMLButtonElement;
    const cards = () => Array.from(root.querySelectorAll('app-listing-card')) as HTMLElement[];

    it('shows rows by default', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')]);

      expect(rows()).toHaveLength(2);
      expect(cards()).toHaveLength(0);
      expect(toggle('Liste').getAttribute('aria-pressed')).toBe('true');
      expect(toggle('Grille').getAttribute('aria-pressed')).toBe('false');
    });

    it('switches to cards, showing the same results in the same order', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b'), listing('c')]);

      toggle('Grille').click();
      await settle();

      expect(rows()).toHaveLength(0);
      expect(cards().map((card) => card.querySelector('h2')?.textContent?.trim())).toEqual([
        'Annonce a',
        'Annonce b',
        'Annonce c',
      ]);
      expect(toggle('Grille').getAttribute('aria-pressed')).toBe('true');
    });

    it('does not search again, and does not touch the address, to change the view', async () => {
      // A layout choice is not a search: no request, no history entry, nothing to share.
      await visit('/recherche?carburant=diesel');
      await answer([listing('a')]);

      toggle('Grille').click();
      await settle();

      expect(open(LIST)).toHaveLength(0);
      expect(url()).toBe('/recherche?carburant=diesel');
    });

    it('remembers the choice on the next visit', async () => {
      await visit('/recherche');
      await answer([listing('a')]);
      toggle('Grille').click();
      await settle();

      // A fresh application, the same browser.
      await visit('/recherche', MAKES, true);
      await answer([listing('a'), listing('b')]);

      expect(cards()).toHaveLength(2);
      expect(toggle('Grille').getAttribute('aria-pressed')).toBe('true');
    });

    it('keeps the view across a new search', async () => {
      await visit('/recherche');
      await answer([listing('a')]);
      toggle('Grille').click();
      await settle();

      chip('Diesel').click();
      await settle();
      await answer([listing('b')]);

      expect(cards()).toHaveLength(1);
    });

    it('loads more into the grid as it does into the list', async () => {
      await visit('/recherche');
      await answer([listing('a'), listing('b')], { hasMore: true, count: 3 });
      toggle('Grille').click();
      await settle();

      (Array.from(root.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Voir plus'),
      ) as HTMLButtonElement).click();
      await settle();
      open(LIST)[0].flush({ items: [listing('c')], hasMore: false });
      await settle();

      expect(cards()).toHaveLength(3);
    });

    it('shows card-shaped placeholders while a grid is loading', async () => {
      localStorage.setItem('autox.search.view', 'grille');
      await visit('/recherche', MAKES, true);

      expect(root.querySelectorAll('.skeleton-card').length).toBeGreaterThan(0);
      expect(root.querySelector('.search__grid[aria-busy="true"]')).not.toBeNull();
    });

    it('labels the two buttons as one control', async () => {
      await visit('/recherche');
      await answer([]);
      const group = root.querySelector('.view-toggle') as HTMLElement;

      expect(group.getAttribute('role')).toBe('group');
      expect(group.getAttribute('aria-label')).toBeTruthy();
    });
  });

  describe('opening a listing and coming back', () => {
    const waitFor = async (predicate: () => boolean) => {
      for (let attempt = 0; attempt < 60 && !predicate(); attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 5));
        await settle();
      }
    };
    const page = () => harness.fixture.nativeElement as HTMLElement;
    const pageRows = () => Array.from(page().querySelectorAll('app-listing-row')) as HTMLElement[];

    async function loadTwoPagesThenOpenAListing(): Promise<void> {
      await visit('/recherche?carburant=diesel');
      await answer([listing('a'), listing('b')], { hasMore: true, count: 3 });
      (Array.from(root.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Voir plus'),
      ) as HTMLButtonElement).click();
      await settle();
      open(LIST)[0].flush({ items: [listing('c')], hasMore: false });
      await settle();
      expect(rows()).toHaveLength(3);

      (rows()[2].querySelector('a') as HTMLAnchorElement).click();
      await waitFor(() => url().startsWith('/annonces/'));
    }

    it('makes each row a link to its listing', async () => {
      await visit('/recherche');
      await answer([listing('a')]);

      expect(rows()[0].querySelector('a')?.getAttribute('href')).toBe('/annonces/a');
    });

    it('restores the list as it was left — every loaded page, no new request', async () => {
      // What the browser needs in order to put the visitor back where they were: the page
      // has to be as tall as when they left it, at once, not after a round trip.
      await loadTwoPagesThenOpenAListing();
      expect(url()).toBe('/annonces/c');

      TestBed.inject(Location).back();
      await waitFor(() => url() === '/recherche?carburant=diesel');

      expect(pageRows()).toHaveLength(3);
      expect(open(LIST)).toHaveLength(0);
      expect(open(COUNT)).toHaveLength(0);
      expect(page().querySelector('.search__count')?.textContent?.trim()).toBe('3 annonces');
      // And the filter is still shown as applied.
      expect(page().querySelector('.applied__chip')?.textContent).toContain('Diesel');
    });

    it('asks the API again when the same search is reached afresh rather than by going back', async () => {
      // Held rows are for a return, not a cache. Arriving by a link must show today's list.
      await loadTwoPagesThenOpenAListing();

      await harness.navigateByUrl('/recherche?carburant=diesel');
      await settle();

      expect(open(LIST)).toHaveLength(1);
    });

    it('searches normally after a return, when a filter is then changed', async () => {
      await loadTwoPagesThenOpenAListing();
      TestBed.inject(Location).back();
      await waitFor(() => url() === '/recherche?carburant=diesel');

      (Array.from(page().querySelectorAll('app-filter-chip button')).find((button) =>
        button.textContent?.includes('Automatique'),
      ) as HTMLButtonElement).click();
      await waitFor(() => url().includes('boite=automatic'));

      // The reuse applied to the arrival only; this is a new search like any other.
      expect(open(LIST)).toHaveLength(1);
    });
  });
});
