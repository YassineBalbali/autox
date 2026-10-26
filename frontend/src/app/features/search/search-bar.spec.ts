import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { SearchBar, yearsDownTo } from './search-bar';
import { SUGGEST_DEBOUNCE_MS } from './search-typeahead';
import { EMPTY_QUERY, SearchQuery } from './search-query';

const BASE = 'http://api.test';

const MAKES = [
  { id: 'm-peugeot', slug: 'peugeot', name: 'Peugeot', popular: true },
  { id: 'm-tesla', slug: 'tesla', name: 'Tesla', popular: false },
];
const PEUGEOT_MODELS = [
  { id: 'mo-208', slug: '208', name: '208' },
  { id: 'mo-308', slug: '308', name: '308' },
];

@Component({
  imports: [SearchBar],
  template: `<app-search-bar [query]="query()" (submitted)="submissions.push($event)" />`,
})
class Host {
  readonly query = signal<SearchQuery>(EMPTY_QUERY);
  readonly submissions: Partial<SearchQuery>[] = [];
}

/**
 * The search bar.
 *
 * <p>The behaviour that distinguishes it from the sidebar is that it has a draft: nothing
 * is searched until it is submitted. The behaviour that keeps it honest is that the draft
 * always gives way to the URL.
 */
describe('SearchBar', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let backend: HttpTestingController;
  let root: HTMLElement;

  async function render(query: SearchQuery = EMPTY_QUERY, makes: object[] | 'fail' = MAKES): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.query.set(query);
    await fixture.whenStable();

    const request = backend.expectOne(`${BASE}/api/v1/reference/makes`);
    if (makes === 'fail') {
      request.error(new ProgressEvent('offline'));
    } else {
      request.flush(makes);
    }
    await fixture.whenStable();
    root = fixture.nativeElement;
  }

  const field = <T extends HTMLElement>(id: string) => root.querySelector(`#${id}`) as T;
  const options = (id: string) =>
    Array.from(field<HTMLSelectElement>(id).options).map((option) => option.textContent?.trim());

  async function type(id: string, value: string): Promise<void> {
    const element = field<HTMLInputElement>(id);
    element.value = value;
    element.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function choose(id: string, value: string): Promise<void> {
    const element = field<HTMLSelectElement>(id);
    element.value = value;
    element.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  async function submit(): Promise<void> {
    (root.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
  }

  it('has a text field, the four most-used filters, and one strong button', async () => {
    await render();

    expect(Array.from(root.querySelectorAll('label')).map((label) => label.textContent?.trim())).toEqual([
      'Recherche',
      'Marque',
      'Modèle',
      'Prix max (€)',
      'Année min.',
    ]);
    const button = root.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(button.textContent?.trim()).toBe('Rechercher');
    expect(button.classList).toContain('btn--primary');
    // A search landmark, so assistive technology can jump straight to it.
    expect(root.querySelector('form')?.getAttribute('role')).toBe('search');
  });

  describe('it is a draft until submitted', () => {
    it('searches nothing while the user is typing', async () => {
      // "volkswagen golf" is fifteen keystrokes. It should be one search.
      await render();

      await type('b-q', 'golf');
      await type('b-q', 'golf tdi');
      await type('b-prix', '15000');

      expect(host.submissions).toEqual([]);
    });

    it('submits everything it holds, as one change', async () => {
      await render();

      await type('b-q', '  golf tdi ');
      await choose('b-marque', 'peugeot');
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-peugeot/models`).flush(PEUGEOT_MODELS);
      await fixture.whenStable();
      await choose('b-modele', '308');
      await type('b-prix', '15000');
      await choose('b-annee', '2020');
      await submit();

      // One navigation, so one history entry and one search.
      expect(host.submissions).toEqual([
        { text: 'golf tdi', make: 'peugeot', model: '308', priceMax: 15000, yearMin: 2020 },
      ]);
    });

    it('submits on Enter in the text field', async () => {
      await render();
      await type('b-q', 'clio');

      (root.querySelector('form') as HTMLFormElement).dispatchEvent(
        new Event('submit', { cancelable: true }),
      );

      expect(host.submissions).toEqual([
        { text: 'clio', make: null, model: null, priceMax: null, yearMin: null },
      ]);
    });

    it('submits cleared fields as cleared, so emptying the box removes the filter', async () => {
      await render({ ...EMPTY_QUERY, text: 'golf', priceMax: 15000 });

      await type('b-q', '');
      await type('b-prix', '');
      await submit();

      expect(host.submissions.at(-1)).toMatchObject({ text: null, priceMax: null });
    });

    it('does not send a price that is not a number', async () => {
      await render();
      await type('b-prix', 'pas trop cher');
      await submit();

      expect(host.submissions.at(-1)?.priceMax).toBeNull();
    });
  });

  describe('the URL wins over the draft', () => {
    it('starts from the current search', async () => {
      await render({ ...EMPTY_QUERY, text: 'golf tdi', make: 'tesla', priceMax: 30000, yearMin: 2021 });

      expect(field<HTMLInputElement>('b-q').value).toBe('golf tdi');
      expect(field<HTMLSelectElement>('b-marque').value).toBe('tesla');
      expect(field<HTMLInputElement>('b-prix').value).toBe('30000');
      expect(field<HTMLSelectElement>('b-annee').value).toBe('2021');
    });

    it('discards an unsubmitted edit when the search changes from elsewhere', async () => {
      // The back button, a chip removed, the sidebar. Whatever was half-typed gives way:
      // the bar must never show a search that is neither the current one nor an edit of it.
      await render({ ...EMPTY_QUERY, text: 'golf' });
      await type('b-q', 'golf gti que je n’ai pas validé');

      host.query.set({ ...EMPTY_QUERY, text: 'clio' });
      await fixture.whenStable();

      expect(field<HTMLInputElement>('b-q').value).toBe('clio');
    });

    it('empties when the filters are cleared', async () => {
      await render({ ...EMPTY_QUERY, text: 'golf', priceMax: 15000 });

      host.query.set(EMPTY_QUERY);
      await fixture.whenStable();

      expect(field<HTMLInputElement>('b-q').value).toBe('');
      expect(field<HTMLInputElement>('b-prix').value).toBe('');
    });
  });

  describe('make and model', () => {
    it('lists the makes the API returns', async () => {
      await render();
      expect(options('b-marque')).toEqual(['Toutes les marques', 'Peugeot', 'Tesla']);
    });

    it('offers the models of the make chosen in the bar, before anything is searched', async () => {
      // Otherwise the user would have to search by make, wait, and only then pick a model.
      await render();
      expect(field<HTMLSelectElement>('b-modele').disabled).toBe(true);
      expect(options('b-modele')).toEqual(['Choisissez une marque']);

      await choose('b-marque', 'peugeot');
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-peugeot/models`).flush(PEUGEOT_MODELS);
      await fixture.whenStable();

      expect(options('b-modele')).toEqual(['Tous les modèles', '208', '308']);
      expect(host.submissions).toEqual([]);
    });

    it('forgets the model when the make changes', async () => {
      // A model belongs to one make: "Tesla" with model "308" matches nothing.
      await render({ ...EMPTY_QUERY, make: 'peugeot', model: '308' });
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-peugeot/models`).flush(PEUGEOT_MODELS);
      await fixture.whenStable();

      await choose('b-marque', 'tesla');
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-tesla/models`).flush([]);
      await submit();

      expect(host.submissions.at(-1)).toMatchObject({ make: 'tesla', model: null });
    });

    it('lets the make be typed when the catalogue is empty', async () => {
      // The catalogue is a dataset loaded separately from the code. An empty dropdown
      // would disable the most-used filter in exactly that state.
      await render(EMPTY_QUERY, []);

      expect(field('b-marque').tagName).toBe('INPUT');
      await type('b-marque', 'Peugeot');
      await submit();

      expect(host.submissions.at(-1)).toMatchObject({ make: 'Peugeot' });
    });

    it('does the same when the catalogue cannot be read', async () => {
      await render(EMPTY_QUERY, 'fail');
      expect(field('b-marque').tagName).toBe('INPUT');
    });

    it('keeps a make from an old link visible in the field', async () => {
      await render({ ...EMPTY_QUERY, make: 'Delorean' });

      expect(options('b-marque')).toContain('Delorean');
      expect(field<HTMLSelectElement>('b-marque').value).toBe('Delorean');
    });
  });

  describe('the year list', () => {
    it('runs from this year back, newest first', () => {
      expect(yearsDownTo(2022, new Date('2026-06-01'))).toEqual([2026, 2025, 2024, 2023, 2022]);
    });

    it('still shows a year older than the list, set from the sidebar or a link', async () => {
      await render({ ...EMPTY_QUERY, yearMin: 1995 });

      expect(field<HTMLSelectElement>('b-annee').value).toBe('1995');
    });
  });

  describe('a suggestion picked under the text field', () => {
    async function suggest(typed: string, suggestions: object[]): Promise<void> {
      await type('b-q', typed);
      // Real time: the debounce is the typeahead's to test, here it only has to pass.
      await new Promise((resolve) => setTimeout(resolve, SUGGEST_DEBOUNCE_MS + 50));
      backend
        .expectOne((request) => request.url === `${BASE}/api/v1/catalogue/suggestions` && decodeURIComponent(request.params.get('q') ?? '') === typed)
        .flush(suggestions);
      await fixture.whenStable();
    }

    const A3 = {
      type: 'MODEL', makeId: 'm-audi', makeSlug: 'audi', makeName: 'Audi',
      modelId: 'mo-a3', modelSlug: 'a3', modelName: 'A3', label: 'Audi A3', listingCount: 4,
    };
    const AUDI = { type: 'MAKE', makeId: 'm-audi', makeSlug: 'audi', makeName: 'Audi', label: 'Audi', listingCount: 9 };

    it('searches by its make and model at once, the text emptied and the rest of the draft kept', async () => {
      await render({ ...EMPTY_QUERY, make: 'tesla' });
      await type('b-prix', '20000');
      await choose('b-annee', '2019');
      await suggest('audi a', [A3]);

      (root.querySelector('[role="option"]') as HTMLElement).click();
      await fixture.whenStable();

      // The filters, not the words: "Audi A3" as text would also have to be in the title.
      expect(host.submissions).toEqual([
        { text: null, make: 'audi', model: 'a3', priceMax: 20000, yearMin: 2019 },
      ]);
    });

    it('searches a make alone, any model', async () => {
      await render({ ...EMPTY_QUERY, make: 'peugeot', model: '308' });
      backend.match(`${BASE}/api/v1/reference/makes/m-peugeot/models`).forEach((request) => request.flush(PEUGEOT_MODELS));
      await suggest('aud', [AUDI]);

      field<HTMLInputElement>('b-q').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
      field<HTMLInputElement>('b-q').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', cancelable: true }));
      await fixture.whenStable();

      expect(host.submissions).toEqual([{ text: null, make: 'audi', model: null, priceMax: null, yearMin: null }]);
    });

    it('leaves the text to search as text when Enter is pressed with nothing highlighted', async () => {
      await render();
      await suggest('audi a', [A3]);

      (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));

      expect(host.submissions).toEqual([{ text: 'audi a', make: null, model: null, priceMax: null, yearMin: null }]);
    });
  });
});
