import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, FuelType, Gearbox, ResultCount } from '../../core/api';
import { PHONE_SCREEN, stubDialogs, stubMedia } from '../../core/ui/dom-testing';
import { SearchFilters, showLabel } from './search-filters';
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
  template: `<app-search-filters
    [query]="query()"
    [resultCount]="count()"
    (changed)="patches.push($event)"
    (cleared)="clears = clears + 1"
  />`,
})
class Host {
  readonly query = signal<SearchQuery>(EMPTY_QUERY);
  readonly count = signal<ResultCount | null>(null);
  readonly patches: Partial<SearchQuery>[] = [];
  clears = 0;
  readonly filters = viewChild.required(SearchFilters);
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
  });

  describe('below the large breakpoint, a full-screen sheet', () => {
    stubDialogs();

    const sheet = () => fixture.nativeElement.querySelector('dialog.sheet') as HTMLDialogElement;

    async function renderPhone(query: SearchQuery = EMPTY_QUERY): Promise<HTMLElement> {
      stubMedia(PHONE_SCREEN);
      const root = await render(query);
      // The sheet's make and model ask for the catalogue.
      backend.expectOne(`${BASE}/api/v1/reference/makes`).flush([
        { id: 'm-1', slug: 'peugeot', name: 'Peugeot' },
        { id: 'm-2', slug: 'renault', name: 'Renault' },
      ]);
      await fixture.whenStable();
      return root;
    }

    async function openSheet(): Promise<void> {
      host.filters().openSheet();
      await fixture.whenStable();
    }

    it('puts nothing beside the results: the filters wait in the closed sheet', async () => {
      const root = await renderPhone();

      expect(sheet().open).toBe(false);
      // The form exists once, in the sheet, never also inline on the page.
      expect(root.querySelectorAll('form.filters')).toHaveLength(1);
      expect(sheet().querySelector('form.filters')).not.toBeNull();
    });

    it('opens as a modal, titled, with a way to close it', async () => {
      await renderPhone();
      let modal = false;
      const showModal = HTMLDialogElement.prototype.showModal;
      sheet().showModal = function (this: HTMLDialogElement) {
        modal = true;
        showModal.call(this);
      };

      await openSheet();

      expect(modal).toBe(true);
      expect(host.filters().sheetOpen()).toBe(true);
      const title = sheet().querySelector('#sheet-title') as HTMLElement;
      expect(title.textContent?.trim()).toBe('Filtres');
      expect(sheet().getAttribute('aria-labelledby')).toBe('sheet-title');
      expect(sheet().querySelector('.sheet__close')?.textContent?.trim()).toBe('Fermer les filtres');

      (sheet().querySelector('.sheet__close') as HTMLButtonElement).click();
      await fixture.whenStable();
      expect(sheet().open).toBe(false);
      expect(host.filters().sheetOpen()).toBe(false);
    });

    it('carries make and model first on a phone, and every refining filter after', async () => {
      await renderPhone();
      await openSheet();

      const legends = Array.from(sheet().querySelectorAll('legend')).map((legend) => legend.textContent?.trim());
      expect(legends[0]).toBe('Véhicule');
      expect(legends.slice(1)).toEqual([
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
      // The sheet has its own heading; the sidebar's would be a second "Filtres".
      expect(sheet().querySelector('.filters__head')).toBeNull();
    });

    it('applies a change at once, while the sheet stays open', async () => {
      await renderPhone();
      await openSheet();

      chips(sheet(), 'Carburant')[1].click();
      await fixture.whenStable();

      expect(host.patches.at(-1)).toEqual({ fuel: [FuelType.DIESEL] });
      expect(sheet().open).toBe(true);
    });

    it('changes the make, and clears the model with it', async () => {
      await renderPhone({ ...EMPTY_QUERY, make: 'peugeot', model: '308' });
      backend.expectOne(`${BASE}/api/v1/reference/makes/m-1/models`).flush([{ id: 'x-1', slug: '308', name: '308' }]);
      await openSheet();

      const make = sheet().querySelector('#f-marque') as HTMLSelectElement;
      expect(make.value).toBe('peugeot');
      expect((sheet().querySelector('#f-modele') as HTMLSelectElement).value).toBe('308');

      make.value = 'renault';
      make.dispatchEvent(new Event('change'));
      await fixture.whenStable();

      expect(host.patches.at(-1)).toEqual({ make: 'renault', model: null });
    });

    it('says how many listings its button will show, and closes on it', async () => {
      await renderPhone();
      host.count.set({ count: 120, capped: false });
      await openSheet();

      const show = sheet().querySelector('.sheet__show') as HTMLButtonElement;
      expect(show.textContent?.trim()).toBe('Voir les 120 annonces');

      host.count.set({ count: 7, capped: false });
      await fixture.whenStable();
      expect(show.textContent?.trim()).toBe('Voir les 7 annonces');

      show.click();
      await fixture.whenStable();
      expect(sheet().open).toBe(false);
    });

    it('offers "tout effacer" once something is set, since the chips are hidden behind it', async () => {
      await renderPhone();
      await openSheet();
      expect(sheet().querySelector('.sheet__clear')).toBeNull();

      host.query.set({ ...EMPTY_QUERY, priceMax: 9000 });
      await fixture.whenStable();
      (sheet().querySelector('.sheet__clear') as HTMLButtonElement).click();

      expect(host.clears).toBe(1);
    });

    it('keeps its state honest when the browser closes it, as Escape does', async () => {
      await renderPhone();
      await openSheet();

      sheet().close();
      await fixture.whenStable();

      expect(host.filters().sheetOpen()).toBe(false);
    });

    it('is never opened as a modal over the desktop sidebar', async () => {
      // No matchMedia: the desktop layout.
      await render();
      host.filters().openSheet();
      await fixture.whenStable();

      expect(sheet().open).toBe(false);
      expect(sheet().children).toHaveLength(0);
      expect(fixture.nativeElement.querySelector('form.filters')?.closest('dialog')).toBeNull();
    });
  });
});

describe('showLabel', () => {
  it('counts what the sheet will show, in French', () => {
    expect(showLabel({ count: 120, capped: false })).toBe('Voir les 120 annonces');
    expect(showLabel({ count: 1, capped: false })).toBe('Voir l’annonce');
    expect(showLabel({ count: 0, capped: false })).toBe('Aucune annonce');
    expect(showLabel({ count: 10000, capped: true })).toMatch(/^Voir plus de 10\s?000 annonces$/u);
  });

  it('says what it does without a figure while the count is unknown', () => {
    expect(showLabel(null)).toBe('Voir les annonces');
    expect(showLabel({ capped: false } as ResultCount)).toBe('Voir les annonces');
  });
});
