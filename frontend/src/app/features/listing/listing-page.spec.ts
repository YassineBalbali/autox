import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode, FuelType, Gearbox, PriceLevel, PublicListingDetail, UserRole } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE } from '../../core/auth/token-store';
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

  /** A signed-in visitor, for the tests that need one; signed out otherwise. */
  let session: StoredTokens | null = null;

  function configure(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'recherche', component: SearchStub },
            { path: 'connexion', component: SearchStub },
            { path: 'annonces/:id', component: ListingPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
        // Signed out: the page is public, and contacting the seller is ContactSeller's spec.
        { provide: TOKEN_STORE, useValue: { read: () => session, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    TestBed.inject(Router).setUpLocationChangeListener();
    TestBed.inject(AuthService).restore();
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
      expect(panel.querySelector('app-contact-seller')).not.toBeNull();
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

    it('gives the price verdict with what it rests on', async () => {
      await load({
        ...LISTING,
        vehicle: { ...LISTING.vehicle, make: 'Peugeot', model: '308' },
        priceIndicator: { level: PriceLevel.GOOD_DEAL, medianPriceAmount: 2149000, comparableCount: 7 },
      });
      const market = root.querySelector('.summary__market') as HTMLElement;

      expect(market.querySelector('app-price-badge')?.textContent).toContain('Bonne affaire');
      expect(market.querySelector('.summary__basis')?.textContent?.trim()).toBe(
        `Comparé à 7 Peugeot 308 de même motorisation de 2019 à 2023 au kilométrage proche. Prix médian, ramené à l’âge et au kilométrage de cette voiture${NBSP}: 21${NBSP}490${NBSP}€.`,
      );
    });

    it('gives the adjusted median to the euro', async () => {
      await load({
        ...LISTING,
        vehicle: { ...LISTING.vehicle, make: 'Tesla', model: 'Model 3' },
        priceIndicator: { level: PriceLevel.MARKET, medianPriceAmount: 2345969, comparableCount: 6 },
      });

      expect(root.querySelector('.summary__basis')?.textContent).toContain(`: 23${NBSP}460${NBSP}€.`);
    });

    it('says nothing about the market when the API has no verdict', async () => {
      await load();

      expect(root.querySelector('.summary__market')).toBeNull();
      expect(root.textContent).not.toContain('Comparé à');
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

  describe('contacting the seller', () => {
    it('asks a visitor who is not signed in to sign in, and to come back to this listing', async () => {
      await load();
      const link = root.querySelector('app-contact-seller a.btn') as HTMLAnchorElement;

      expect(link.textContent?.trim()).toBe('Contacter le vendeur');
      expect(link.getAttribute('href')).toBe(`/connexion?suivant=%2Fannonces%2F${LISTING.id}`);
      // Nothing about the seller is revealed to get there.
      expect(root.querySelector('app-contact-seller')?.textContent).toContain('ni votre adresse e-mail ni');
    });
  });

  describe('similar listings', () => {
    it('asks for them once the listing is there, and puts them under both columns', async () => {
      await load();
      const request = open(`${DETAIL}/similar`)[0];
      expect(request.request.method).toBe('GET');
      request.flush(
        ['a', 'b', 'c'].map((id) => ({ id, title: `Peugeot 208 ${id}`, make: 'Peugeot', model: '208', priceAmount: 1_000_000 })),
      );
      await settle();

      const section = root.querySelector('app-similar-listings') as HTMLElement;
      // A sibling of the article, not inside a column of it: the page's full width.
      expect(section.parentElement).toBe(root.querySelector('article.listing')?.parentElement);
      expect(section.querySelectorAll('app-listing-card')).toHaveLength(3);
    });

    it('is not asked for a listing that is not there', async () => {
      await visit();
      open(DETAIL)[0].flush({ code: ErrorCode.NOT_FOUND }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(open(`${DETAIL}/similar`)).toHaveLength(0);
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

  describe('on a phone and a tablet', () => {
    const bar = () => root.querySelector('app-contact-bar') as HTMLElement | null;

    afterEach(() => {
      session = null;
    });

    async function signInAs(id: string): Promise<void> {
      session = { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 };
      await visit();
      TestBed.inject(AuthService).setUser({ id, email: 'moi@autox.test', role: UserRole.PARTICULIER });
      open(DETAIL)[0].flush({ ...LISTING, priceIndicator: { level: PriceLevel.GOOD_DEAL, medianPriceAmount: 2100000, comparableCount: 9 } });
      await settle();
    }

    it('holds the price, its verdict and the way to the seller in a bar at the foot', async () => {
      await load({ ...LISTING, priceIndicator: { level: PriceLevel.GOOD_DEAL, medianPriceAmount: 2100000, comparableCount: 9 } });

      const words = bar()?.textContent?.replace(/\s+/g, ' ');
      // \s took the narrow no-break space in the figure with it.
      expect(words).toContain('18 990 €');
      expect(words).toContain('Bonne affaire');
      expect(bar()?.querySelector('button')?.textContent?.trim()).toBe('Contacter');
    });

    it('sends a visitor to sign in from the bar, and back to this listing afterwards', async () => {
      await load();

      (bar()?.querySelector('button') as HTMLButtonElement).click();
      await settle();

      expect(TestBed.inject(Router).url).toBe(`/connexion?suivant=%2Fannonces%2F${ID}`);
    });

    it('opens the message form from the bar, and steps out of the way while it is open', async () => {
      await signInAs('u-buyer');

      (bar()?.querySelector('button') as HTMLButtonElement).click();
      await settle();

      expect(root.querySelector('#contact-message')).not.toBeNull();
      expect(bar()).toBeNull();

      // Cancelled: the bar comes back.
      (Array.from(root.querySelectorAll('app-contact-seller button')).find((button) =>
        button.textContent?.includes('Annuler'),
      ) as HTMLButtonElement).click();
      await settle();
      expect(bar()).not.toBeNull();
    });

    it('has no bar on the seller’s own listing, where there is nobody to contact', async () => {
      await signInAs('s-1');

      expect(bar()).toBeNull();
    });

    describe('the long sections', () => {
      const toggle = (title: string) => root.querySelector(`#${title} .section__toggle`) as HTMLButtonElement;
      const body = (button: HTMLButtonElement) =>
        root.querySelector('#' + button.getAttribute('aria-controls')) as HTMLElement;

      it('fold the specification and the equipment to their headings', async () => {
        await load();

        for (const title of ['titre-caracteristiques', 'titre-equipements']) {
          const button = toggle(title);
          expect(button.parentElement?.tagName, title).toBe('H2');
          expect(button.getAttribute('aria-expanded'), title).toBe('false');
          expect(body(button).classList, title).not.toContain('section__body--open');
        }
        expect(toggle('titre-caracteristiques').textContent?.trim()).toBe('Caractéristiques');
        expect(toggle('titre-equipements').textContent?.trim()).toBe('Équipements');
      });

      it('open and close each on its own', async () => {
        await load();
        const specification = toggle('titre-caracteristiques');

        specification.click();
        await settle();
        expect(specification.getAttribute('aria-expanded')).toBe('true');
        expect(body(specification).classList).toContain('section__body--open');
        expect(toggle('titre-equipements').getAttribute('aria-expanded')).toBe('false');

        specification.click();
        await settle();
        expect(specification.getAttribute('aria-expanded')).toBe('false');
      });

      it('keep the description open: it is what the seller wrote, and short', async () => {
        await load();

        expect(root.querySelector('#titre-description .section__toggle')).toBeNull();
      });

      it('keep the plain heading for a wide screen, where nothing folds', async () => {
        await load();

        expect(root.querySelector('#titre-caracteristiques .section__title-text')?.textContent?.trim()).toBe(
          'Caractéristiques',
        );
      });
    });
  });
});
