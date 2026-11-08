import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  BASE_PATH,
  ImageStatus,
  ListingStatus,
  ModeratedListing,
  ModerationActionType,
  ModerationReason,
  ModerationStatus,
  UserRole,
} from '../../core/api';
import { SEARCH_DELAY_MS } from './moderation-filters';
import { ModerationPage } from './moderation-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/admin/listings`;
const MAKES = `${BASE}/api/v1/reference/makes`;
const LAYOUT_KEY = 'autox.admin.moderation.layout';

const { APPROVE, REJECT, REMOVE } = ModerationActionType;

const item = (id: string, over: Partial<ModeratedListing> = {}, listing: object = {}): ModeratedListing => ({
  listing: {
    id,
    status: ListingStatus.ACTIVE,
    moderationStatus: ModerationStatus.NOT_REVIEWED,
    title: `Annonce ${id}`,
    priceAmount: 1_250_000,
    currencyCode: 'EUR',
    createdAt: '2026-10-20T09:00:00Z',
    publishedAt: '2026-10-21T09:00:00Z',
    vehicle: { make: 'Peugeot', model: 'Peugeot 308', version: 'GT Line' },
    images: [{ id: `${id}-1`, position: 0, status: ImageStatus.READY, variants: { THUMBNAIL: { url: `http://img.test/${id}.jpg` } } }],
    ...listing,
  },
  seller: { id: 'u1', displayName: 'Garage Bellevue', email: 'contact@bellevue.test', role: UserRole.PROFESSIONNEL },
  allowedActions: [APPROVE, REJECT, REMOVE] as unknown as Set<ModerationActionType>,
  trail: [],
  editedSinceReview: false,
  demo: false,
  ...over,
});

const page = (items: ModeratedListing[], over: object = {}) => ({
  items,
  page: 0,
  size: 10,
  totalElements: items.length,
  totalPages: 1,
  hasNext: false,
  ...over,
});

/**
 * Finding a listing on the moderation page: the search, the filters, the order, the list of
 * rows, and that all of it is in the address.
 */
describe('ModerationPage, finding a listing', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let router: Router;
  let root: HTMLElement;

  beforeEach(() => localStorage.removeItem(LAYOUT_KEY));
  afterEach(() => localStorage.removeItem(LAYOUT_KEY));

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const last = () => {
    const requests = open(LIST);
    return requests[requests.length - 1];
  };
  // The generated client encodes each value itself and sends it as it is: read it back decoded.
  const param = (request: TestRequest, key: string) => {
    const value = request.request.params.get(key);
    return value === null ? null : decodeURIComponent(value);
  };
  const sent = (request: TestRequest) =>
    Object.fromEntries(request.request.params.keys().map((key) => [key, param(request, key)]));
  const text = () => (root.textContent ?? '').replace(/\s+/g, ' ');
  const query = () => router.parseUrl(router.url).queryParams;

  async function visit(url = '/admin/moderation'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'admin/moderation', component: ModerationPage }], withComponentInputBinding()),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    open(`${BASE}/api/v1/reference/countries`).forEach((request) =>
      request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
    open(MAKES).forEach((request) => request.flush([{ id: 'p', name: 'Peugeot' }, { id: 'r', name: 'Renault' }]));
    await settle();
  }

  async function show(items: ModeratedListing[], url = '/admin/moderation', over: object = {}): Promise<void> {
    await visit(url);
    last().flush(page(items, over));
    await settle();
  }

  /** Answers the fetch that followed a change, with these listings. */
  async function answer(items: ModeratedListing[], over: object = {}): Promise<TestRequest> {
    await settle();
    const request = last();
    request.flush(page(items, over));
    await settle();
    return request;
  }

  const select = async (filter: string, value: string) => {
    const element = root.querySelector(`[data-filter=${filter}]`) as HTMLSelectElement | HTMLInputElement;
    element.value = value;
    element.dispatchEvent(new Event('change'));
    await settle();
  };
  const type = async (words: string) => {
    const box = root.querySelector('#moderation-q') as HTMLInputElement;
    box.value = words;
    box.dispatchEvent(new Event('input'));
    await settle();
  };
  const enter = async () => {
    (root.querySelector('.filters__search') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
  };

  describe('the address', () => {
    it('asks the API for the search, every filter and the order named in it', async () => {
      await visit(
        '/admin/moderation?vue=en-ligne&q=bellevue%20308&marque=Peugeot&vendeur=pro&etat=sain&offre=vente&origine=reelle&prix-min=5000&prix-max=20000&tri=prix-croissant&page=2',
      );

      expect(sent(last())).toEqual({
        view: 'LIVE',
        q: 'bellevue 308',
        make: 'Peugeot',
        sellerRole: 'PROFESSIONNEL',
        damaged: 'false',
        offer: 'SALE',
        demo: 'false',
        priceMin: '500000',
        priceMax: '2000000',
        sort: 'PRICE_ASC',
        page: '1',
        size: '10',
      });
    });

    it('shows what the address says in the controls', async () => {
      await show([item('a')], '/admin/moderation?q=golf&vendeur=particulier&etat=endommage&offre=leasing&origine=demo&prix-max=9000&tri=recents');

      expect((root.querySelector('#moderation-q') as HTMLInputElement).value).toBe('golf');
      const value = (filter: string) => (root.querySelector(`[data-filter=${filter}]`) as HTMLSelectElement).value;
      expect(value('seller')).toBe('particulier');
      expect(value('damage')).toBe('endommage');
      expect(value('offer')).toBe('leasing');
      expect(value('origin')).toBe('demo');
      expect(value('price-max')).toBe('9000');
      expect(value('sort')).toBe('recents');
    });

    it('opens each list in its own order, and says so in the order chosen', async () => {
      await visit('/admin/moderation');
      expect(last().request.params.get('sort')).toBe('OLDEST');
      expect((root.querySelector('[data-filter=sort]') as HTMLSelectElement).value).toBe('anciens');

      await visit('/admin/moderation?vue=retirees');
      expect(last().request.params.get('sort')).toBe('NEWEST');
      expect((root.querySelector('[data-filter=sort]') as HTMLSelectElement).value).toBe('recents');
    });

    it('keeps the search and the filters on the other tabs, but not the page or the order', async () => {
      await show([item('a')], '/admin/moderation?q=golf&vendeur=pro&tri=prix-croissant&page=2', { page: 1, totalPages: 2 });

      const links = Array.from(root.querySelectorAll('.moderation__view')).map((link) => link.getAttribute('href'));
      expect(links).toEqual([
        '/admin/moderation?q=golf&vendeur=pro',
        '/admin/moderation?q=golf&vendeur=pro&vue=en-ligne',
        '/admin/moderation?q=golf&vendeur=pro&vue=retirees',
      ]);
    });
  });

  describe('the search', () => {
    it('goes into the address on Enter, from the first page, and is sent to the API', async () => {
      await show([item('a')], '/admin/moderation?vue=en-ligne&page=3', { page: 2, totalPages: 3 });

      await type('Hélène  dupré');
      await enter();

      expect(query()).toEqual({ vue: 'en-ligne', q: 'Hélène  dupré' });
      const request = await answer([item('b')]);
      expect(param(request, 'q')).toBe('Hélène  dupré');
      expect(request.request.params.get('page')).toBe('0');
    });

    it('follows typing after a pause, not at every letter', async () => {
      await show([item('a')]);
      await type('pe');
      await type('peu');
      expect(query()['q']).toBeUndefined();

      await new Promise((done) => setTimeout(done, SEARCH_DELAY_MS + 50));
      await settle();

      expect(query()['q']).toBe('peu');
      expect(open(LIST)).toHaveLength(1);
    });

    it('counts what it found, in French', async () => {
      await show([item('a')], '/admin/moderation?q=golf');
      expect(root.querySelector('[data-result]')?.textContent?.trim()).toBe('1 annonce trouvée');

      await show([item('a'), item('b')], '/admin/moderation?q=golf', { totalElements: 174, totalPages: 18 });
      expect(root.querySelector('[data-result]')?.textContent?.trim()).toBe(`174 annonces trouvées`);

      await show([item('a')], '/admin/moderation?vue=en-ligne', { totalElements: 174 });
      expect(root.querySelector('[data-result]')?.textContent?.trim()).toBe('174 annonces');
    });

    it('says when nothing matches, and offers to clear the search and the filters', async () => {
      await show([], '/admin/moderation?vue=en-ligne&q=ferrari&vendeur=pro&tri=prix-croissant');

      expect(text()).toContain('Aucune annonce ne correspond à cette recherche dans « En ligne ».');
      (root.querySelector('[data-action=clear-empty]') as HTMLButtonElement).click();
      await settle();

      // The order is not a filter: it stays.
      expect(query()).toEqual({ vue: 'en-ligne', tri: 'prix-croissant' });
    });
  });

  describe('the filters', () => {
    it.each([
      ['make', 'Renault', { marque: 'Renault' }, { make: 'Renault' }],
      ['seller', 'particulier', { vendeur: 'particulier' }, { sellerRole: 'PARTICULIER' }],
      ['seller', 'pro', { vendeur: 'pro' }, { sellerRole: 'PROFESSIONNEL' }],
      ['damage', 'endommage', { etat: 'endommage' }, { damaged: 'true' }],
      ['damage', 'sain', { etat: 'sain' }, { damaged: 'false' }],
      ['offer', 'vente', { offre: 'vente' }, { offer: 'SALE' }],
      ['offer', 'leasing', { offre: 'leasing' }, { offer: 'LEASING' }],
      ['origin', 'demo', { origine: 'demo' }, { demo: 'true' }],
      ['origin', 'reelle', { origine: 'reelle' }, { demo: 'false' }],
      ['price-min', '8000', { 'prix-min': '8000' }, { priceMin: '800000' }],
      ['price-max', ' 15000 ', { 'prix-max': '15000' }, { priceMax: '1500000' }],
      ['sort', 'prix-decroissant', { tri: 'prix-decroissant' }, { sort: 'PRICE_DESC' }],
      ['sort', 'recents', { tri: 'recents' }, { sort: 'NEWEST' }],
    ])('%s = %s goes into the address and to the API', async (filter, value, inUrl, toApi) => {
      await show([item('a')], '/admin/moderation?page=2', { page: 1, totalPages: 2 });

      await select(filter, value);

      expect(query()).toEqual(inUrl);
      expect(sent(await answer([item('a')]))).toMatchObject(toApi);
    });

    it('leaves a filter out of the address when set back to everything', async () => {
      await show([item('a')], '/admin/moderation?vendeur=pro&prix-min=500');

      await select('seller', '');
      await select('price-min', 'abc');

      expect(query()).toEqual({});
    });

    it('keeps a make the catalogue does not list, as a seller typed it', async () => {
      await show([item('a')], '/admin/moderation?marque=Lada');

      const options = Array.from(root.querySelectorAll('[data-filter=make] option')).map((option) => option.textContent?.trim());
      expect(options).toEqual(['Toutes', 'Lada', 'Peugeot', 'Renault']);
      expect((root.querySelector('[data-filter=make]') as HTMLSelectElement).value).toBe('Lada');
    });

    it('sends what is typed but not yet searched along with a filter', async () => {
      await show([item('a')]);
      await type('golf');
      await select('offer', 'vente');

      expect(query()).toEqual({ q: 'golf', offre: 'vente' });
    });

    it('folds behind one button on a phone, which says how many are on', async () => {
      await show([item('a')], '/admin/moderation?vendeur=pro&etat=sain&q=golf');
      const toggle = root.querySelector('.filters__toggle') as HTMLButtonElement;

      expect(toggle.textContent?.trim()).toBe('Filtres (2)');
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      toggle.click();
      await settle();
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(root.querySelector('#moderation-filters')?.classList).toContain('filters__panel--open');
    });

    it('clears the search and every filter at once', async () => {
      await show([item('a')], '/admin/moderation?vue=retirees&q=golf&marque=Peugeot&origine=demo&page=2', { page: 1, totalPages: 2 });

      (root.querySelector('[data-action=clear-filters]') as HTMLButtonElement).click();
      await settle();

      expect(query()).toEqual({ vue: 'retirees' });
      expect((root.querySelector('#moderation-q') as HTMLInputElement).value).toBe('');
    });
  });

  describe('the list of rows', () => {
    const rows = () => Array.from(root.querySelectorAll('app-moderation-row article')).map((row) => row.getAttribute('data-listing'));
    const row = (id: string) => root.querySelector(`app-moderation-row [data-listing="${id}"]`) as HTMLElement;

    it('opens as cards, and turns into rows of 25 on request', async () => {
      await show([item('a')]);
      expect(root.querySelectorAll('app-moderation-card').length).toBe(1);
      expect(open(LIST)).toHaveLength(0);

      (root.querySelector('[data-layout=liste]') as HTMLButtonElement).click();
      const request = await answer([item('a'), item('b')]);

      expect(request.request.params.get('size')).toBe('25');
      expect(rows()).toEqual(['a', 'b']);
      expect(root.querySelector('[data-layout=liste]')?.getAttribute('aria-pressed')).toBe('true');
      expect(localStorage.getItem(LAYOUT_KEY)).toBe('liste');
    });

    it('is remembered: the next visit opens on rows', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await visit();

      const request = last();
      expect(request.request.params.get('size')).toBe('25');
      request.flush(page([item('a')]));
      await settle();
      expect(rows()).toEqual(['a']);
    });

    it('falls back to cards when the remembered value is not one it knows', async () => {
      localStorage.setItem(LAYOUT_KEY, 'grille');
      await visit();

      expect(last().request.params.get('size')).toBe('10');
    });

    it('shows a listing on one line: photo, title, vehicle, price, seller and type, badges, status and date', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await show([
        item('a', { demo: true, editedSinceReview: true }, { damage: { types: ['BODYWORK'], description: 'Choc' } }),
        item('b', {}, { leasing: { type: 'LLD', monthlyPayment: 16900 } }),
      ]);
      const a = row('a');
      const b = row('b');

      expect(a.querySelector('img')?.getAttribute('src')).toBe('http://img.test/a.jpg');
      expect(a.querySelector('.mrow__title a')?.getAttribute('href')).toBe('/annonces/a');
      // "Peugeot 308", not "Peugeot Peugeot 308".
      expect(a.querySelector('.mrow__vehicle')?.textContent?.trim()).toBe('Peugeot 308 GT Line');
      expect(a.querySelector('app-price')?.textContent).toContain('12');
      const seller = a.querySelector('.mrow__seller')?.textContent?.replace(/\s+/g, ' ');
      expect(seller).toContain('Garage Bellevue');
      expect(seller).toContain('Professionnel');
      expect(seller).toContain('contact@bellevue.test');
      expect(Array.from(a.querySelectorAll('[data-badge]')).map((badge) => badge.getAttribute('data-badge'))).toEqual([
        'edited',
        'damaged',
        'demo',
      ]);
      expect(Array.from(b.querySelectorAll('[data-badge]')).map((badge) => badge.getAttribute('data-badge'))).toEqual(['leasing']);
      expect(a.querySelector('[data-tag=status]')?.textContent?.trim()).toBe('En ligne');
      expect(a.querySelector('.mrow__date')?.textContent?.trim()).toMatch(/^Déposée le 20 oct/);
    });

    it('prices a leasing offer by the month, as the public results do, and a sale by its price', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await show([item('a'), item('b', {}, { leasing: { type: 'LLD', monthlyPayment: 16900 } })]);

      const monthly = row('b').querySelector('[data-price=monthly]')?.textContent?.replace(/\s/g, ' ');
      expect(monthly).toBe('169 €/mois');
      expect(row('b').querySelector('app-price')).toBeNull();
      expect(row('a').querySelector('[data-price=monthly]')).toBeNull();
      expect(row('a').querySelector('app-price')).not.toBeNull();
    });

    it('dates a row by what its list is about', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await show([item('a')], '/admin/moderation?vue=en-ligne');

      expect(row('a').querySelector('.mrow__date')?.textContent?.trim()).toMatch(/^En ligne depuis le 21 oct/);
    });

    it('decides from a row as from a card: approving is one click', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await show([item('a'), item('b')]);

      (row('b').querySelector('[data-action=approve]') as HTMLButtonElement).click();
      await settle();
      open(`${LIST}/b/approve`)[0].flush(item('b'));
      await answer([item('a')]);

      expect(text()).toContain('Annonce approuvée.');
      expect(rows()).toEqual(['a']);
    });

    it('refuses from a row with a reason, the form taking the row’s width', async () => {
      localStorage.setItem(LAYOUT_KEY, 'liste');
      await show([item('a')]);

      (row('a').querySelector('[data-action=refuse]') as HTMLButtonElement).click();
      await settle();
      expect(row('a').classList).toContain('mrow--deciding');
      const reason = row('a').querySelector('select') as HTMLSelectElement;
      reason.value = ModerationReason.DUPLICATE;
      reason.dispatchEvent(new Event('change'));
      await settle();
      (row('a').querySelector('[data-action=confirm-refusal]') as HTMLButtonElement).click();
      await settle();

      expect(open(`${LIST}/a/reject`)[0].request.body).toEqual({ reasonCode: ModerationReason.DUPLICATE });
    });
  });
});
