import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode, FuelType, Gearbox, PublicListingDetail } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { SearchMemory } from '../search/search-memory';
import { ListingPage } from './listing-page';

const BASE = 'http://api.test';
const ID = '01a1306d-db66-7b93-b280-f3992929d5d3';
const DETAIL = `${BASE}/api/v1/listings/${ID}`;

const LISTING: PublicListingDetail = {
  id: ID,
  title: 'Peugeot 308 BlueHDi 130 Allure Pack',
  description: 'Première main.\nEntretien à jour.\n\n<script>alert(1)</script>',
  priceAmount: 1899000,
  currencyCode: 'EUR',
  mileageKm: 62400,
  condition: 'USED',
  exteriorColor: 'GREY',
  co2EmissionsGKm: 118,
  emissionClass: 'EURO_6D',
  vehicle: {
    firstRegistrationYear: 2021,
    firstRegistrationMonth: 3,
    fuelType: FuelType.DIESEL,
    gearbox: Gearbox.MANUAL,
    powerHp: 130,
    powerKw: 96,
    doors: 5,
    seats: 5,
  },
  location: { city: 'Lyon', postalCode: '69003', countryCode: 'FR' },
  seller: { id: 's-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL', memberSince: '2026-03-04T10:00:00Z' },
  featureCodes: ['ABS', 'AIR_CONDITIONING'],
  images: [
    { id: 'i-1', position: 0, variants: { THUMBNAIL: { url: 'http://img.test/1-t.jpg' }, CARD: { url: 'http://img.test/1-c.jpg' }, FULL: { url: 'http://img.test/1-f.jpg' } } },
    { id: 'i-2', position: 1, variants: { THUMBNAIL: { url: 'http://img.test/2-t.jpg' }, CARD: { url: 'http://img.test/2-c.jpg' }, FULL: { url: 'http://img.test/2-f.jpg' } } },
  ],
};

const FEATURES = [
  { code: 'ABS', category: 'SAFETY', displayOrder: 10, labels: { 'fr-FR': 'ABS' } },
  { code: 'AIR_CONDITIONING', category: 'COMFORT', displayOrder: 10, labels: { 'fr-FR': 'Climatisation' } },
];
const COUNTRIES = [{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }];

@Component({ template: '<p>résultats</p>' })
class SearchStub {}

/**
 * The listing page, through a real router.
 *
 * <p>Three things about it are more than rendering. A listing that is gone is a different
 * state from a request that failed, and each gets its own screen. The description is text
 * a stranger wrote and must never be interpreted. And "back to the results" has to go
 * back in history when there is a results page behind it, and somewhere sensible when
 * there is not.
 */
describe('ListingPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  function configure(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'recherche', component: SearchStub },
            { path: 'annonces/:id', component: ListingPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    TestBed.inject(Router).setUpLocationChangeListener();
  }

  function answerReference(): void {
    open(`${BASE}/api/v1/reference/features`).forEach((request) => request.flush(FEATURES));
    open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
  }

  /** Opens the listing URL directly — a shared link, a bookmark. */
  async function visit(id = ID): Promise<void> {
    configure();
    harness = await RouterTestingHarness.create(`/annonces/${id}`);
    root = harness.fixture.nativeElement;
    await settle();
    answerReference();
    await settle();
  }

  async function load(listing: PublicListingDetail = LISTING): Promise<void> {
    await visit();
    open(DETAIL)[0].flush(listing);
    await settle();
  }

  const text = () => root.textContent ?? '';

  describe('a live listing', () => {
    it('is fetched by the id in the address, without any credentials', async () => {
      await visit();
      const request = open(DETAIL)[0];

      expect(request.request.method).toBe('GET');
      // Public: there is no session here and none is invented.
      expect(request.request.headers.has('Authorization')).toBe(false);
    });

    it('titles the page and the tab with the listing', async () => {
      await load();

      expect(root.querySelector('h1')?.textContent?.trim()).toBe('Peugeot 308 BlueHDi 130 Allure Pack');
      expect(TestBed.inject(Title).getTitle()).toBe('Peugeot 308 BlueHDi 130 Allure Pack · AutoX');
    });

    it('shows the price in the large accent form, through the shared component', async () => {
      await load();
      const price = root.querySelector('.summary__price app-price') as HTMLElement;

      expect(price.textContent?.trim()).toBe(`18${NBSP}990${NBSP}€`);
      // The same component as the list, in its detail size: one definition of a price.
      expect(price.querySelector('.price--detail')).not.toBeNull();
    });

    it('shows the specification table', async () => {
      await load();
      const rows = Object.fromEntries(
        Array.from(root.querySelectorAll('.spec-table__row')).map((row) => [
          row.querySelector('dt')?.textContent?.trim(),
          row.querySelector('dd')?.textContent?.trim(),
        ]),
      );

      expect(rows).toMatchObject({
        Année: '2021',
        Kilométrage: `62${NBSP}400${NBSP}km`,
        Carburant: 'Diesel',
        'Boîte de vitesses': 'Manuelle',
        Puissance: `130${NBSP}ch (96${NBSP}kW)`,
        Portes: '5',
        Places: '5',
        Couleur: 'Gris',
        État: 'Occasion',
        'Émissions de CO₂': `118${NBSP}g/km`,
      });
    });

    it('shows the equipment grouped by category, labelled from the features endpoint', async () => {
      await load();
      const groups = Array.from(root.querySelectorAll('.equipment__group')).map((group) => [
        group.querySelector('h3')?.textContent?.trim(),
        Array.from(group.querySelectorAll('li')).map((item) => item.textContent?.trim()),
      ]);

      expect(groups).toEqual([
        ['Sécurité', ['ABS']],
        ['Confort', ['Climatisation']],
      ]);
      expect(text()).not.toContain('AIR_CONDITIONING');
    });

    it('still shows the listing when the equipment catalogue cannot be read', async () => {
      configure();
      harness = await RouterTestingHarness.create(`/annonces/${ID}`);
      root = harness.fixture.nativeElement;
      await settle();
      open(`${BASE}/api/v1/reference/features`).forEach((request) => request.error(new ProgressEvent('offline')));
      open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
      open(DETAIL)[0].flush(LISTING);
      await settle();

      // The section is absent; the car, its price and its seller are not.
      expect(root.querySelector('.equipment')).toBeNull();
      expect(root.querySelector('h1')?.textContent).toContain('Peugeot 308');
    });

    it('gives the gallery the photos in order', async () => {
      await load();

      expect(root.querySelectorAll('.gallery__thumb')).toHaveLength(2);
      expect((root.querySelector('.gallery__img') as HTMLImageElement).getAttribute('src')).toBe(
        'http://img.test/1-c.jpg',
      );
    });

    it('describes the seller: name, kind, town, member since', async () => {
      await load();
      const seller = root.querySelector('.seller') as HTMLElement;

      expect(seller.querySelector('.seller__name')?.textContent?.trim()).toBe('Garage Bellevue');
      // In full here, where there is room: "Professionnel", not the results' "Pro".
      expect(seller.querySelector('app-seller-badge')?.textContent?.trim()).toBe('Professionnel');
      expect(seller.textContent).toContain('Lyon (69)');
      expect(seller.textContent).toContain('mars 2026');
    });

    it('labels a private seller as one', async () => {
      await load({ ...LISTING, seller: { displayName: 'Camille R.', role: 'PARTICULIER' } });

      expect(root.querySelector('.seller app-seller-badge')?.textContent?.trim()).toBe('Particulier');
    });

    it('keeps title, price, contact and seller together in the one panel that follows the page', async () => {
      await load({ ...LISTING, publishedAt: '2026-10-11T12:44:20Z' });
      const panel = root.querySelector('.listing__aside app-listing-summary') as HTMLElement;

      expect(panel.querySelector('h1')).not.toBeNull();
      expect(panel.querySelector('app-price')).not.toBeNull();
      expect(panel.querySelector('.summary__contact')).not.toBeNull();
      expect(panel.querySelector('.seller')).not.toBeNull();
      expect(panel.textContent).toContain('Publiée le 11 octobre 2026');
    });

    it('sums the car up in five facts under the photos, each named', async () => {
      await load();
      const facts = Array.from(root.querySelectorAll('app-key-facts .facts__tile')).map((tile) => [
        tile.querySelector('dt')?.textContent?.trim(),
        tile.querySelector('dd')?.textContent?.trim(),
      ]);

      expect(facts.map(([label]) => label)).toEqual([
        'Année',
        'Kilométrage',
        'Énergie',
        'Boîte de vitesses',
        'Puissance',
      ]);
      expect(Object.fromEntries(facts)).toMatchObject({
        Kilométrage: `62${NBSP}400${NBSP}km`,
        Énergie: 'Diesel',
        'Boîte de vitesses': 'Manuelle',
        Puissance: `130${NBSP}ch`,
      });
      // The icon only repeats the label, so it is hidden from a screen reader.
      expect(root.querySelectorAll('app-key-facts svg[aria-hidden="true"]')).toHaveLength(5);
    });

    it('ticks each piece of equipment', async () => {
      await load();
      const items = Array.from(root.querySelectorAll('.equipment__group li'));

      expect(items.length).toBeGreaterThan(0);
      expect(items.every((item) => item.querySelector('app-icon svg[aria-hidden="true"]'))).toBe(true);
    });
  });

  describe('the description', () => {
    it('is shown as text, with the seller’s line breaks', async () => {
      await load();
      const description = root.querySelector('.listing__description') as HTMLElement;

      expect(description.textContent).toContain('Première main.\nEntretien à jour.');
    });

    it('is never interpreted as markup', async () => {
      // The one field on the page a stranger wrote at length. Rendered as HTML it would be
      // a stored cross-site scripting hole on the most-visited page of the site.
      await load();
      const description = root.querySelector('.listing__description') as HTMLElement;

      expect(description.querySelector('script')).toBeNull();
      expect(description.textContent).toContain('<script>alert(1)</script>');
    });

    it('is left out entirely when the seller wrote none', async () => {
      await load({ ...LISTING, description: undefined });
      expect(root.querySelector('.listing__description')).toBeNull();
    });
  });

  describe('the contact button', () => {
    it('is present, says what it will do, and says it cannot do it yet', async () => {
      await load();
      const button = root.querySelector('.summary__contact') as HTMLButtonElement;

      expect(button.textContent?.trim()).toBe('Contacter le vendeur');
      expect(button.getAttribute('aria-disabled')).toBe('true');
      const reason = root.querySelector(`#${button.getAttribute('aria-describedby')}`);
      expect(reason?.textContent).toContain('messagerie n’est pas encore ouverte');
    });

    it('stays reachable by keyboard, so the reason can be heard', async () => {
      // The `disabled` attribute would take it out of the tab order, and a screen reader
      // user would never learn that contacting the seller is planned but not open.
      await load();
      const button = root.querySelector('.summary__contact') as HTMLButtonElement;

      expect(button.disabled).toBe(false);
      expect(button.tabIndex).toBe(0);
    });

    it('reveals nothing and sends nothing when pressed', async () => {
      await load();
      (root.querySelector('.summary__contact') as HTMLButtonElement).click();
      await settle();

      backend.expectNone(() => true);
      expect(root.querySelector('form')).toBeNull();
    });
  });

  describe('a listing that is not there', () => {
    it('gets its own page, not an error, when the API says not found', async () => {
      await visit();
      open(DETAIL)[0].flush({ code: ErrorCode.NOT_FOUND }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(root.querySelector('h1')?.textContent?.trim()).toBe('Cette annonce n’est plus disponible');
      // Nothing broke: no red, no "erreur", no retry button for something that is gone.
      expect(root.querySelector('.alert--error')).toBeNull();
      expect(text()).not.toContain('Réessayer');
      expect(TestBed.inject(Title).getTitle()).toBe('Annonce introuvable · AutoX');
    });

    it('offers the way back to what is for sale', async () => {
      await visit();
      open(DETAIL)[0].flush({ code: ErrorCode.NOT_FOUND }, { status: 404, statusText: 'Not Found' });
      await settle();

      const link = root.querySelector('app-not-found a') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/recherche');
      expect(link.textContent?.trim()).toBe('Voir les annonces');
    });

    it('treats a bare 404 from in front of the API the same way', async () => {
      await visit();
      open(DETAIL)[0].flush('<html>Not Found</html>', { status: 404, statusText: 'Not Found' });
      await settle();

      expect(root.querySelector('app-not-found')).not.toBeNull();
    });

    it.each(['pas-un-identifiant', '123', `${ID}-et-plus`, '<script>'])(
      'does not even ask the API about an id it could never have issued (%s)',
      async (id) => {
        await visit(encodeURIComponent(id));

        expect(root.querySelector('app-not-found')).not.toBeNull();
        backend.expectNone((request) => request.url.includes('/api/v1/listings/'));
      },
    );
  });

  describe('a request that failed', () => {
    it('says so in French and offers to try again — it is not "not found"', async () => {
      // A listing has not been sold because the visitor's train went into a tunnel.
      await visit();
      open(DETAIL)[0].error(new ProgressEvent('offline'));
      await settle();

      expect(root.querySelector('app-not-found')).toBeNull();
      expect(root.querySelector('.alert--error')?.textContent).toContain('Vérifiez votre connexion');

      (Array.from(root.querySelectorAll('button')).find((button) =>
        button.textContent?.includes('Réessayer'),
      ) as HTMLButtonElement).click();
      await settle();
      open(DETAIL)[0].flush(LISTING);
      await settle();

      expect(root.querySelector('h1')?.textContent).toContain('Peugeot 308');
    });

    it('never shows the API’s own error text', async () => {
      await visit();
      open(DETAIL)[0].flush(
        { code: ErrorCode.INTERNAL_ERROR, detail: 'NullPointerException in ListingSearchService' },
        { status: 500, statusText: 'Server Error' },
      );
      await settle();

      expect(text()).toContain('Une erreur inattendue');
      expect(text()).not.toContain('NullPointer');
    });
  });

  it('shows the shape of the page while it loads', async () => {
    await visit();

    expect(root.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(root.querySelector('h1')).toBeNull();
  });

  describe('back to the results', () => {
    const backLink = () => root.querySelector('.listing__back a') as HTMLAnchorElement;
    const plainClick = () =>
      backLink().dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));

    it('goes back in history when the listing was opened from the results', async () => {
      // History, not a fresh navigation: that is what brings the list back as it was,
      // with the pages already loaded and the scroll position.
      configure();
      harness = await RouterTestingHarness.create('/recherche?carburant=diesel');
      await harness.navigateByUrl(`/annonces/${ID}`);
      root = harness.fixture.nativeElement;
      answerReference();
      open(DETAIL)[0].flush(LISTING);
      await settle();
      const back = vi.spyOn(TestBed.inject(Location), 'back');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');

      plainClick();

      expect(back).toHaveBeenCalledOnce();
      expect(navigate).not.toHaveBeenCalled();
    });

    it('navigates to the results when there is no results page to go back to', async () => {
      // A shared link, a bookmark. Stepping back in history from here would leave the site.
      await load();
      const back = vi.spyOn(TestBed.inject(Location), 'back');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

      plainClick();

      expect(back).not.toHaveBeenCalled();
      expect(navigate).toHaveBeenCalledWith('/recherche');
    });

    it('returns to the last search made in this tab, when it knows one', async () => {
      configure();
      TestBed.inject(SearchMemory).remember('/recherche?marque=peugeot&prix_max=20000');
      harness = await RouterTestingHarness.create(`/annonces/${ID}`);
      root = harness.fixture.nativeElement;
      answerReference();
      open(DETAIL)[0].flush(LISTING);
      await settle();

      expect(backLink().getAttribute('href')).toBe('/recherche?marque=peugeot&prix_max=20000');
    });

    it('is a real link, so it can be opened in a new tab', async () => {
      await load();
      const back = vi.spyOn(TestBed.inject(Location), 'back');
      const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true });

      backLink().dispatchEvent(event);

      // Left to the browser: the href does the work.
      expect(event.defaultPrevented).toBe(false);
      expect(back).not.toHaveBeenCalled();
      expect(backLink().getAttribute('href')).toBe('/recherche');
    });
  });

  it('loads the next listing when the id in the address changes', async () => {
    await load();
    const other = '01a1306d-0000-7000-8000-000000000002';

    await harness.navigateByUrl(`/annonces/${other}`);
    await settle();
    open(`${BASE}/api/v1/listings/${other}`)[0].flush({ ...LISTING, id: other, title: 'Renault Clio' });
    await settle();

    expect(harness.fixture.nativeElement.querySelector('h1')?.textContent?.trim()).toBe('Renault Clio');
  });
});
