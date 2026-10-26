import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { legacySearchRedirect } from '../search/legacy-search-redirect';
import { BUDGETS, CATEGORIES } from './home-shortcuts';
import { HomePage } from './home-page';

const BASE = 'http://api.test';
const COUNT = `${BASE}/api/v1/listings/count`;

const MAKES = [
  { id: 'm-renault', slug: 'renault', name: 'Renault', popular: true },
  { id: 'm-peugeot', slug: 'peugeot', name: 'Peugeot', popular: true },
  { id: 'm-tesla', slug: 'tesla', name: 'Tesla', popular: false },
  { id: 'm-noslug', name: 'Sans lien', popular: true },
];
const PEUGEOT_MODELS = [
  { id: 'p-208', slug: '208', name: '208' },
  { id: 'p-308', slug: '308', name: '308' },
];
const COUNTRIES = [{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }];

@Component({ selector: 'app-results-stub', template: '<p>résultats</p>' })
class ResultsStub {}

@Component({ selector: 'app-sell-stub', template: '<p>vendre</p>' })
class SellStub {}

/**
 * The home page, through a real router and a fake backend.
 *
 * <p>What it promises: every tile and the form lead to an ordinary search link, the
 * counts are the counts of exactly those searches, and a count that fails costs only the
 * number. Recent searches come from the browser and only appear when there are some.
 */
describe('HomePage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  /** A count request's criteria, as `key=value` pairs, for telling the requests apart. */
  const criteriaOf = (request: TestRequest): Record<string, string> =>
    Object.fromEntries(request.request.params.keys().map((key) => [key, request.request.params.getAll(key)!.join(',')]));

  const settle = async () => {
    // Past the typing debounce (250 ms) and a count retry's pause (400 ms, then 800 ms).
    await vi.advanceTimersByTimeAsync(900);
    await harness.fixture.whenStable();
  };

  async function visit(url = '/'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', pathMatch: 'full', canActivate: [legacySearchRedirect], component: HomePage },
          { path: 'recherche', component: ResultsStub },
          { path: 'vendre', component: SellStub },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    open(`${BASE}/api/v1/reference/makes`).forEach((request) => request.flush(MAKES));
    open(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush(COUNTRIES));
    await settle();
  }

  /** Answers every open count with a figure derived from its criteria, or with `answer`. */
  async function answerCounts(answer: (criteria: Record<string, string>) => number | 'fail' = () => 7): Promise<void> {
    // The counts resolve the catalogue and currency first, so they arrive in waves.
    for (let wave = 0; wave < 6; wave++) {
      for (const request of open(COUNT)) {
        const result = answer(criteriaOf(request));
        if (result === 'fail') {
          request.flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
        } else {
          request.flush({ count: result, capped: false });
        }
      }
      await settle();
    }
  }

  const text = (selector: string) => root.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const tiles = (section: string) => Array.from(root.querySelectorAll(`#${section}-title`)).flatMap((title) =>
    Array.from(title.closest('section')!.querySelectorAll<HTMLAnchorElement>('a.tile')),
  );
  const field = (index: number) => root.querySelectorAll<HTMLSelectElement | HTMLInputElement>('.finder__control')[index];

  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe('the search card', () => {
    it('shows the live count of everything on the button', async () => {
      await visit();
      await answerCounts((criteria) => (Object.keys(criteria).length === 0 ? 60 : 1));

      expect(text('.finder__submit')).toBe('Rechercher (60)');
    });

    it('says plain "Rechercher" while the count is unknown, and if it fails', async () => {
      await visit();
      expect(text('.finder__submit')).toBe('Rechercher');

      await answerCounts(() => 'fail');
      expect(text('.finder__submit')).toBe('Rechercher');
    });

    it('offers the catalogue makes that can be linked to, alphabetically, and their models once one is chosen', async () => {
      await visit();
      const makes = Array.from((field(0) as HTMLSelectElement).options).map((option) => option.textContent?.trim());
      expect(makes).toEqual(['Marque', 'Peugeot', 'Renault', 'Tesla']);
      expect((field(1) as HTMLSelectElement).disabled).toBe(true);

      field(0).value = 'peugeot';
      field(0).dispatchEvent(new Event('change'));
      await settle();
      open(`${BASE}/api/v1/reference/makes/m-peugeot/models`).forEach((request) => request.flush(PEUGEOT_MODELS));
      await settle();

      expect((field(1) as HTMLSelectElement).disabled).toBe(false);
      expect(Array.from((field(1) as HTMLSelectElement).options).map((option) => option.textContent?.trim())).toEqual([
        'Modèle',
        '208',
        '308',
      ]);
    });

    it('recounts as the form changes, for exactly the search it describes', async () => {
      await visit();
      await answerCounts(() => 60);

      field(0).value = 'peugeot';
      field(0).dispatchEvent(new Event('change'));
      field(2).value = '15000';
      field(2).dispatchEvent(new Event('change'));
      await settle();
      open(`${BASE}/api/v1/reference/makes/m-peugeot/models`).forEach((request) => request.flush(PEUGEOT_MODELS));

      const seen: Record<string, string>[] = [];
      await answerCounts((criteria) => {
        seen.push(criteria);
        return 9;
      });

      expect(seen).toContainEqual(expect.objectContaining({ makeId: 'm-peugeot', priceMax: '1500000', currency: 'EUR' }));
      expect(text('.finder__submit')).toBe('Rechercher (9)');
    });

    it('keeps only digits in the postal code, five at most', async () => {
      await visit();
      const input = field(3) as HTMLInputElement;

      input.value = '69a 0031';
      input.dispatchEvent(new Event('input'));
      await settle();

      expect(input.value).toBe('69003');
    });

    it('searches on submit, with the form in the results link — and so does "Plus de critères"', async () => {
      await visit();
      field(0).value = 'peugeot';
      field(0).dispatchEvent(new Event('change'));
      const postal = field(3) as HTMLInputElement;
      postal.value = '69';
      postal.dispatchEvent(new Event('input'));
      await settle();

      expect(root.querySelector('.finder__more')?.getAttribute('href')).toBe('/recherche?marque=peugeot&lieu=69');

      (root.querySelector('form.finder') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
      await settle();
      expect(TestBed.inject(Router).url).toBe('/recherche?marque=peugeot&lieu=69');
    });

    it('has "Acheter" as the current tab and "Vendre" as a way to /vendre', async () => {
      await visit();
      const [buy, sell] = Array.from(root.querySelectorAll<HTMLAnchorElement>('.finder__tab'));

      expect(buy.getAttribute('aria-current')).toBe('page');
      expect(sell.getAttribute('href')).toBe('/vendre');
    });
  });

  describe('the shortcut tiles', () => {
    it('links each budget to its price band, with its own count', async () => {
      await visit();
      await answerCounts((criteria) =>
        criteria['priceMin'] === '1000000' && criteria['priceMax'] === '1500000' ? 16 : 3,
      );

      const links = tiles('budget');
      expect(links).toHaveLength(BUDGETS.length);
      expect(links[0].getAttribute('href')).toBe('/recherche?prix_max=5000');
      expect(links[2].getAttribute('href')).toBe('/recherche?prix_min=10000&prix_max=15000');
      expect(links[4].getAttribute('href')).toBe('/recherche?prix_min=20000');
      expect(links[2].querySelector('.tile__count')?.textContent?.trim()).toBe('16 annonces');
      expect(links[0].querySelector('.tile__count')?.textContent?.trim()).toBe('3 annonces');
    });

    it('offers the six body types, each linked to the sidebar filter it stands for', async () => {
      await visit();
      const links = tiles('category');

      expect(links).toHaveLength(CATEGORIES.length);
      expect(links.map((link) => link.querySelector('.tile__label')?.textContent?.trim())).toEqual([
        'Citadine',
        'Berline',
        'Break',
        'SUV',
        'Monospace',
        'Utilitaire',
      ]);
      expect(links.map((link) => link.getAttribute('href'))).toEqual([
        '/recherche?carrosserie=hatchback',
        '/recherche?carrosserie=sedan',
        '/recherche?carrosserie=estate',
        '/recherche?carrosserie=suv',
        '/recherche?carrosserie=mpv',
        '/recherche?carrosserie=van',
      ]);
    });

    it('draws each body type’s own silhouette above its name, as decoration only', async () => {
      await visit();
      const links = tiles('category');
      const drawings = links.map((link) => link.querySelector('app-body-silhouette svg') as SVGElement);

      expect(drawings.every((svg) => svg !== null)).toBe(true);
      // Above the label, in the tile's reading order.
      expect(links[0].firstElementChild?.tagName).toBe('APP-BODY-SILHOUETTE');
      // The label says what it is: the drawing is not announced as well.
      expect(drawings.every((svg) => svg.getAttribute('aria-hidden') === 'true')).toBe(true);
      // Six different drawings, not one car six times.
      expect(new Set(drawings.map((svg) => svg.innerHTML)).size).toBe(6);
    });

    it('puts "1 annonce" in the singular, and a failed count costs the number, not the tile', async () => {
      await visit();
      await answerCounts((criteria) => (Object.values(criteria).some((value) => value.includes('VAN')) ? 'fail' : 1));

      const van = tiles('category')[5];
      expect(van.getAttribute('href')).toBe('/recherche?carrosserie=van');
      expect(van.querySelector('.tile__count')?.textContent?.trim()).toBe('');
      expect(tiles('category')[0].querySelector('.tile__count')?.textContent?.trim()).toBe('1 annonce');
    });

    it('formats large counts the French way', async () => {
      await visit();
      await answerCounts(() => 12345);

      expect(tiles('budget')[0].querySelector('.tile__count')?.textContent?.trim()).toBe(`12${NBSP}345 annonces`);
    });

    it('lists the popular makes first, then the others, by name only, each a link — never one without a slug', async () => {
      await visit();
      const makes = tiles('makes');

      expect(makes.map((link) => link.textContent?.trim())).toEqual(['Peugeot', 'Renault', 'Tesla']);
      expect(makes[0].getAttribute('href')).toBe('/recherche?marque=peugeot');
      expect(root.querySelector('.tile--make img, .tile--make svg[role="img"]')).toBeNull();
    });
  });

  describe('recent searches', () => {
    it('is not shown at all when there are none', async () => {
      await visit();
      expect(root.querySelector('#recent-title')).toBeNull();
    });

    it('shows the stored searches as cards, newest first, with their criteria as chips', async () => {
      localStorage.setItem(
        'autox.search.recent',
        JSON.stringify([{ marque: 'peugeot', prix_max: '20000' }, { carburant: 'electric' }]),
      );
      await visit();

      const cards = Array.from(root.querySelectorAll('.recent__card'));
      expect(cards).toHaveLength(2);
      expect(Array.from(cards[0].querySelectorAll('.recent__chip')).map((chip) => chip.textContent?.trim())).toEqual([
        'Peugeot',
        `Jusqu’à 20${NBSP}000 €`,
      ]);
      expect(cards[0].querySelector('.recent__open')?.getAttribute('href')).toBe('/recherche?marque=peugeot&prix_max=20000');
      expect(cards[1].querySelector('.recent__open')?.getAttribute('href')).toBe('/recherche?carburant=electric');
    });

    it('removes one with its × button, and forgets it', async () => {
      localStorage.setItem('autox.search.recent', JSON.stringify([{ carburant: 'electric' }, { boite: 'automatic' }]));
      await visit();

      const remove = root.querySelector<HTMLButtonElement>('.recent__remove')!;
      expect(remove.getAttribute('aria-label')).toBe('Retirer cette recherche : Électrique');
      remove.click();
      await settle();

      expect(root.querySelectorAll('.recent__card')).toHaveLength(1);
      expect(JSON.parse(localStorage.getItem('autox.search.recent')!)).toEqual([{ boite: 'automatic' }]);
    });
  });

  describe('old search links', () => {
    it('forwards /?filters to /recherche with the query string unchanged', async () => {
      await visit('/?marque=peugeot&prix_max=15000&tri=prix-croissant');

      expect(TestBed.inject(Router).url).toBe('/recherche?marque=peugeot&prix_max=15000&tri=prix-croissant');
    });

    it('treats a bare / and unrelated parameters as the home page', async () => {
      await visit('/?utm_source=newsletter');

      expect(TestBed.inject(Router).url).toBe('/?utm_source=newsletter');
      expect(root.querySelector('form.finder')).not.toBeNull();
    });
  });
});
