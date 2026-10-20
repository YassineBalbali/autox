import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, FuelType, Gearbox } from '../../core/api';
import { SearchFilters } from './search-filters';
import { EMPTY_QUERY, SearchQuery } from './search-query';

const BASE = 'http://api.test';

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
  template: `<app-search-filters [query]="query()" (changed)="patches.push($event)" />`,
})
class Host {
  readonly query = signal<SearchQuery>(EMPTY_QUERY);
  readonly patches: Partial<SearchQuery>[] = [];
}

/**
 * The filter sidebar.
 *
 * <p>What is checked here is the contract in both directions: the sidebar shows exactly
 * the query it is given, and reports a change as the fields that should change — it keeps
 * no state of its own to drift from the URL. And its options are the reference
 * endpoint's, not a list in the component.
 */
describe('SearchFilters', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let backend: HttpTestingController;

  async function render(query: SearchQuery = EMPTY_QUERY, enums: object = ENUMS): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    host.query.set(query);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/reference/enums`).flush(enums);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  const chips = (root: HTMLElement, legend: string) => {
    const group = Array.from(root.querySelectorAll('fieldset')).find((fieldset) =>
      fieldset.querySelector('legend')?.textContent?.includes(legend),
    );
    return Array.from(group?.querySelectorAll('app-filter-chip button') ?? []) as HTMLButtonElement[];
  };

  async function change(element: HTMLInputElement, value: string): Promise<void> {
    element.value = value;
    element.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  describe('options come from the reference endpoint', () => {
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
        values: { ...ENUMS.values, FuelType: ['PETROL', 'DIESEL'] },
      });

      expect(chips(root, 'Carburant')).toHaveLength(2);
    });

    it('does not offer a value newer than this build can filter on', async () => {
      // The URL parser only accepts values the generated enum knows, so a chip for an
      // unknown one would do nothing when pressed. Absent until the client is regenerated
      // is the honest state.
      const root = await render(EMPTY_QUERY, {
        values: { ...ENUMS.values, FuelType: ['PETROL', 'ANTIMATTER'] },
      });

      expect(chips(root, 'Carburant').map((chip) => chip.textContent?.trim())).toEqual(['Essence']);
    });

    it('does not fetch the catalogue, which it no longer shows', async () => {
      // Make and model moved to the search bar. The sidebar must not go on requesting
      // makes for a control it does not have.
      await render();
      backend.expectNone((request) => request.url.includes('/reference/makes'));
    });
  });

  describe('it shows the query it is given', () => {
    it('reflects every kind of filter it has', async () => {
      const root = await render({
        ...EMPTY_QUERY,
        priceMax: 30000,
        yearMin: 2020,
        location: 'Lyon',
        fuel: [FuelType.ELECTRIC],
      });

      expect((root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement).value).toBe('30000');
      expect((root.querySelector('[aria-label="Année minimum"]') as HTMLInputElement).value).toBe('2020');
      expect((root.querySelector('[aria-label="Ville ou code postal"]') as HTMLInputElement).value).toBe('Lyon');

      const pressed = chips(root, 'Carburant').filter((chip) => chip.getAttribute('aria-pressed') === 'true');
      expect(pressed.map((chip) => chip.textContent?.trim())).toEqual(['Électrique']);
    });

    it('shows the good-deals chip pressed when the query asks for good deals, and toggles it', async () => {
      const root = await render({ ...EMPTY_QUERY, goodDeals: true });
      const [chip] = chips(root, 'Prix du marché');

      expect(chip.textContent?.trim()).toBe('Bonnes affaires uniquement');
      expect(chip.getAttribute('aria-pressed')).toBe('true');

      chip.click();
      await fixture.whenStable();
      expect(host.patches.at(-1)).toEqual({ goodDeals: false });
    });

    it('follows the query when it changes from outside — the back button', async () => {
      const root = await render({ ...EMPTY_QUERY, priceMax: 30000 });
      const price = root.querySelector('[aria-label="Prix maximum en euros"]') as HTMLInputElement;

      host.query.set(EMPTY_QUERY);
      await fixture.whenStable();

      expect(price.value).toBe('');
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

  describe('layout and collapse', () => {
    it('has the refining filters, and leaves make and model to the search bar', async () => {
      // One control per filter on the screen. Make and model are composed in the bar;
      // showing the same dropdown here too would only raise the question of which wins.
      const root = await render();
      const legends = Array.from(root.querySelectorAll('legend')).map((legend) => legend.textContent?.trim());

      expect(legends).toEqual([
        'Année',
        'Prix (€)',
        'Kilométrage (km)',
        'Prix du marché',
        'Carburant',
        'Boîte de vitesses',
        'Carrosserie',
        'État',
        'Localisation',
      ]);
      expect(root.querySelector('#f-marque')).toBeNull();
    });

    it('leaves "tout effacer" to the chips above the results', async () => {
      // One of them on the screen, next to the list of what it would clear.
      const root = await render({ ...EMPTY_QUERY, priceMax: 9000 });
      expect(root.textContent).not.toContain('Tout effacer');
    });

    it('shows how many filters are active on the collapse button', async () => {
      const root = await render({ ...EMPTY_QUERY, location: 'Lyon', priceMin: 1000, priceMax: 9000 });

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
  });
});
