import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, FuelType, Gearbox } from '../../core/api';
import { SearchFilters } from './search-filters';
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
const ENUMS = {
  values: {
    FuelType: ['PETROL', 'DIESEL', 'ELECTRIC'],
    Gearbox: ['MANUAL', 'AUTOMATIC'],
    BodyType: ['HATCHBACK', 'SUV'],
    VehicleCondition: ['NEW', 'USED'],
  },
};

@Component({
  imports: [SearchFilters],
  template: `
    <app-search-filters [query]="query()" (changed)="patches.push($event)" (cleared)="cleared = cleared + 1" />
  `,
})
class Host {
  readonly query = signal<SearchQuery>(EMPTY_QUERY);
  readonly patches: Partial<SearchQuery>[] = [];
  cleared = 0;
}

/**
 * The filter sidebar.
 *
 * <p>What is checked here is the contract in both directions: the sidebar shows exactly
 * the query it is given, and reports a change as the fields that should change — it keeps
 * no state of its own to drift from the URL. And its options are the reference
 * endpoints', not a list in the component.
 */
describe('SearchFilters', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let backend: HttpTestingController;

  async function render(
    query: SearchQuery = EMPTY_QUERY,
    reference: { makes?: unknown; enums?: unknown; failMakes?: boolean } = {},
  ): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.query.set(query);
    await fixture.whenStable();

    const makes = backend.expectOne(`${BASE}/api/v1/reference/makes`);
    if (reference.failMakes) {
      makes.error(new ProgressEvent('offline'));
    } else {
      makes.flush(reference.makes ?? MAKES);
    }
    backend.expectOne(`${BASE}/api/v1/reference/enums`).flush(reference.enums ?? ENUMS);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const chips = (root: HTMLElement, legend: string) => {
    const group = Array.from(root.querySelectorAll('fieldset')).find((fieldset) =>
      fieldset.querySelector('legend')?.textContent?.includes(legend),
    );
    return Array.from(group?.querySelectorAll('app-filter-chip button') ?? []) as HTMLButtonElement[];
  };

  const options = (select: Element | null) =>
    Array.from(select?.querySelectorAll('option') ?? []).map((option) => option.textContent?.trim());

  async function change(element: HTMLInputElement | HTMLSelectElement, value: string): Promise<void> {
    element.value = value;
    element.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  describe('options come from the reference endpoints', () => {
    it('lists the makes the API returns', async () => {
      const root = await render();

      expect(options(root.querySelector('#f-marque'))).toEqual(['Toutes les marques', 'Peugeot', 'Tesla']);
    });

    it('lists the enum values the API returns, with French labels', async () => {
      const root = await render();

      expect(chips(root, 'Carburant').map((chip) => chip.textContent?.trim())).toEqual([
        'Essence',
        'Diesel',
        'Électrique',
      ]);
      expect(chips(root, 'Boîte').map((chip) => chip.textContent?.trim())).toEqual([
        'Manuelle',
        'Automatique',
      ]);
    });

    it('drops a value retired on the backend without a frontend change', async () => {
      const root = await render(EMPTY_QUERY, {
        enums: { values: { ...ENUMS.values, FuelType: ['PETROL', 'DIESEL'] } },
      });

      expect(chips(root, 'Carburant')).toHaveLength(2);
    });

    it('does not offer a value newer than this build can filter on', async () => {
      // The URL parser only accepts values the generated enum knows, so a chip for an
      // unknown one would do nothing when pressed. Absent until the client is regenerated
      // is the honest state.
      const root = await render(EMPTY_QUERY, {
        enums: { values: { ...ENUMS.values, FuelType: ['PETROL', 'ANTIMATTER'] } },
      });

      expect(chips(root, 'Carburant').map((chip) => chip.textContent?.trim())).toEqual(['Essence']);
    });

    it('loads the models of the selected make, and only then', async () => {
      const root = await render({ ...EMPTY_QUERY, make: 'peugeot' });

      backend.expectOne(`${BASE}/api/v1/reference/makes/m-peugeot/models`).flush(PEUGEOT_MODELS);
      await fixture.whenStable();

      expect(options(root.querySelector('#f-modele'))).toEqual(['Tous les modèles', '208', '308']);
    });

    it('asks for no models until a make is chosen, and says why the list is empty', async () => {
      const root = await render();
      const models = root.querySelector('#f-modele') as HTMLSelectElement;

      backend.expectNone((request) => request.url.includes('/models'));
      expect(models.disabled).toBe(true);
      expect(options(models)).toEqual(['Choisissez une marque']);
    });
  });

  describe('when the catalogue is empty or unreachable', () => {
    it('lets the make be typed instead of offering an empty list', async () => {
      // The catalogue is a dataset loaded separately from the code, and listings created
      // before it exists carry their make as text. An empty dropdown would disable the
      // most-used filter in exactly that state.
      const root = await render(EMPTY_QUERY, { makes: [] });
      const make = root.querySelector('#f-marque') as HTMLInputElement;

      expect(make.tagName).toBe('INPUT');
      await change(make, 'Peugeot');
      expect(host.patches.at(-1)).toEqual({ make: 'Peugeot', model: null });
    });

    it('does the same when the request fails', async () => {
      const root = await render(EMPTY_QUERY, { failMakes: true });
      expect((root.querySelector('#f-marque') as HTMLElement).tagName).toBe('INPUT');
    });
  });

  describe('it shows the query it is given', () => {
    it('reflects every kind of filter', async () => {
      const root = await render({
        ...EMPTY_QUERY,
        make: 'tesla',
        priceMax: 30000,
        yearMin: 2020,
        location: 'Lyon',
        fuel: [FuelType.ELECTRIC],
      });

      expect((root.querySelector('#f-marque') as HTMLSelectElement).value).toBe('tesla');
      expect((root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement).value).toBe('30000');
      expect((root.querySelector('[aria-label="Année minimum"]') as HTMLInputElement).value).toBe('2020');
      expect((root.querySelector('[aria-label="Ville ou code postal"]') as HTMLInputElement).value).toBe('Lyon');

      const pressed = chips(root, 'Carburant').filter((chip) => chip.getAttribute('aria-pressed') === 'true');
      expect(pressed.map((chip) => chip.textContent?.trim())).toEqual(['Électrique']);
    });

    it('follows the query when it changes from outside — the back button', async () => {
      const root = await render({ ...EMPTY_QUERY, priceMax: 30000 });
      const price = root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement;

      host.query.set(EMPTY_QUERY);
      await fixture.whenStable();

      expect(price.value).toBe('');
    });

    it('keeps a make from an old link visible, so the filter can be seen and removed', async () => {
      const root = await render({ ...EMPTY_QUERY, make: 'Delorean' });
      const make = root.querySelector('#f-marque') as HTMLSelectElement;

      expect(options(make)).toContain('Delorean');
      expect(make.value).toBe('Delorean');
    });
  });

  describe('it reports changes and holds none', () => {
    it('adds and removes a multi-choice value', async () => {
      const root = await render({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL] });
      const [petrol, diesel] = chips(root, 'Carburant');

      petrol.click();
      expect(host.patches.at(-1)).toEqual({ fuel: [FuelType.DIESEL, FuelType.PETROL] });

      diesel.click();
      expect(host.patches.at(-1)).toEqual({ fuel: [] });
    });

    it('does not change what it shows until the query does', async () => {
      // It only asked. If the navigation never happens the chip must not look selected.
      const root = await render();
      const [manual] = chips(root, 'Boîte');

      manual.click();
      await fixture.whenStable();

      expect(host.patches.at(-1)).toEqual({ gearbox: [Gearbox.MANUAL] });
      expect(manual.getAttribute('aria-pressed')).toBe('false');
    });

    it('clears the model when the make changes', async () => {
      // A model belongs to one make. "Tesla" with the model still set to "308" is a search
      // that can match nothing.
      const root = await render({ ...EMPTY_QUERY, make: 'peugeot', model: '308' });
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-peugeot/models`).flush(PEUGEOT_MODELS);

      await change(root.querySelector('#f-marque') as HTMLSelectElement, 'tesla');

      expect(host.patches.at(-1)).toEqual({ make: 'tesla', model: null });
    });

    it('reports a number once the field is committed', async () => {
      const root = await render();

      await change(root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement, '15000');

      expect(host.patches.at(-1)).toEqual({ priceMax: 15000 });
    });

    it('does not search on every keystroke', async () => {
      // Typing "15000" would otherwise be five searches and five history entries.
      const root = await render();
      const price = root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement;

      price.value = '1';
      price.dispatchEvent(new Event('input'));
      price.value = '15';
      price.dispatchEvent(new Event('input'));

      expect(host.patches).toEqual([]);
    });

    it('empties a field that does not hold a usable number', async () => {
      // The field must never appear to apply a filter the results do not have.
      const root = await render();
      const price = root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement;

      await change(price, 'pas cher');

      expect(host.patches.at(-1)).toEqual({ priceMax: null });
      expect(price.value).toBe('');
    });

    it('reports an emptied field as no filter', async () => {
      const root = await render({ ...EMPTY_QUERY, location: 'Lyon' });

      await change(root.querySelector('[aria-label="Ville ou code postal"]') as HTMLInputElement, '  ');

      expect(host.patches.at(-1)).toEqual({ location: null });
    });
  });

  describe('clearing and collapsing', () => {
    it('offers to clear only when something is set', async () => {
      expect((await render()).querySelector('.filters__reset')).toBeNull();

      const root = await render({ ...EMPTY_QUERY, make: 'tesla' });
      (root.querySelector('.filters__reset') as HTMLButtonElement).click();

      expect(host.cleared).toBe(1);
    });

    it('shows how many filters are active on the collapse button', async () => {
      const root = await render({ ...EMPTY_QUERY, make: 'tesla', priceMin: 1000, priceMax: 9000 });

      expect(root.querySelector('.filters__toggle .tag')?.textContent?.trim()).toBe('2');
    });

    it('collapses and expands, and says which it is', async () => {
      const root = await render();
      const toggle = root.querySelector('.filters__toggle') as HTMLButtonElement;
      const panel = root.querySelector('.filters') as HTMLElement;

      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(toggle.getAttribute('aria-controls')).toBe(panel.id);

      toggle.click();
      await fixture.whenStable();

      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(panel.classList).toContain('filters--open');
    });

    it('covers every filter the screen is meant to have', async () => {
      const root = await render();
      const legends = Array.from(root.querySelectorAll('legend')).map((legend) => legend.textContent?.trim());

      // marque + modèle, année, prix, kilométrage, carburant, boîte, carrosserie, état,
      // localisation.
      expect(legends).toEqual([
        'Véhicule',
        'Année',
        'Prix (€)',
        'Kilométrage (km)',
        'Carburant',
        'Boîte de vitesses',
        'Carrosserie',
        'État',
        'Localisation',
      ]);
    });
  });
});
