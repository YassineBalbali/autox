import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, BodyType, FuelType, Gearbox, VehicleCondition } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { ActiveFilters, describeFilters } from './active-filters';
import { EMPTY_QUERY, SearchQuery } from './search-query';

const BASE = 'http://api.test';

const labels = (query: SearchQuery, names = {}) => describeFilters(query, names).map((filter) => filter.label);
const apply = (query: SearchQuery, patch: Partial<SearchQuery>): SearchQuery => ({ ...query, ...patch });

/**
 * The applied-filter chips.
 *
 * <p>The wording matters, but the part with teeth is removal. Each chip carries the exact
 * change that takes away that filter and no other — and "no other" is where the bugs
 * live: removing one fuel must keep the rest, removing a range must clear both ends,
 * removing the make must take the model with it.
 */
describe('describeFilters', () => {
  it('describes nothing for an empty search', () => {
    expect(describeFilters(EMPTY_QUERY)).toEqual([]);
  });

  describe('wording', () => {
    it('quotes the free text, so it reads as what was typed', () => {
      expect(labels({ ...EMPTY_QUERY, text: 'golf tdi' })).toEqual(['« golf tdi »']);
    });

    it('names a make and model as the catalogue does, not by their slugs', () => {
      const query = { ...EMPTY_QUERY, make: 'citroen', model: 'c5-aircross' };

      expect(labels(query, { make: 'Citroën', model: 'C5 Aircross' })).toEqual(['Citroën', 'C5 Aircross']);
    });

    it('falls back to the slug when the catalogue has no name to give', () => {
      // Wrong in its accents, right in what it removes — and better than no chip, which
      // would be a filter applied to the results and invisible to the user.
      expect(labels({ ...EMPTY_QUERY, make: 'citroen' })).toEqual(['citroen']);
    });

    it.each([
      [{ priceMin: 5000, priceMax: 15000 }, `5${NBSP}000 – 15${NBSP}000 €`],
      [{ priceMin: 5000 }, `À partir de 5${NBSP}000 €`],
      [{ priceMax: 15000 }, `Jusqu’à 15${NBSP}000 €`],
      [{ yearMin: 2018, yearMax: 2022 }, '2018 – 2022'],
      [{ yearMin: 2018 }, 'À partir de 2018'],
      [{ mileageMax: 90000 }, `Jusqu’à 90${NBSP}000 km`],
    ])('describes the range %j as "%s"', (patch, expected) => {
      expect(labels({ ...EMPTY_QUERY, ...patch })).toEqual([expected]);
    });

    it('does not group a year like a quantity', () => {
      expect(labels({ ...EMPTY_QUERY, yearMin: 2018 })[0]).not.toContain(NBSP);
    });

    it('uses the French labels for enum values', () => {
      expect(
        labels({
          ...EMPTY_QUERY,
          fuel: [FuelType.DIESEL],
          gearbox: [Gearbox.AUTOMATIC],
          body: [BodyType.SUV],
          condition: [VehicleCondition.USED],
        }),
      ).toEqual(['Diesel', 'Boîte automatique', 'SUV / 4x4', 'Occasion']);
    });

    it('says "boîte" on a gearbox chip, since "Manuelle" alone says nothing', () => {
      expect(labels({ ...EMPTY_QUERY, gearbox: [Gearbox.MANUAL] })).toEqual(['Boîte manuelle']);
    });
  });

  describe('one chip per decision', () => {
    it('shows a range as one chip, not one per end', () => {
      expect(describeFilters({ ...EMPTY_QUERY, priceMin: 5000, priceMax: 15000 })).toHaveLength(1);
    });

    it('shows each chosen enum value as its own chip', () => {
      // Each was a separate click, and each should be a separate thing to undo.
      expect(describeFilters({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL, FuelType.PETROL] })).toHaveLength(2);
    });

    it('gives every chip a distinct key', () => {
      const filters = describeFilters({
        ...EMPTY_QUERY,
        text: 'x',
        make: 'bmw',
        model: 'x1',
        yearMin: 2019,
        priceMax: 30000,
        mileageMax: 90000,
        fuel: [FuelType.DIESEL, FuelType.PETROL],
        gearbox: [Gearbox.AUTOMATIC],
        body: [BodyType.SUV],
        condition: [VehicleCondition.USED],
        location: 'Lille',
      });

      expect(new Set(filters.map((filter) => filter.key)).size).toBe(filters.length);
      expect(filters).toHaveLength(12);
    });
  });

  describe('removing', () => {
    const full: SearchQuery = {
      ...EMPTY_QUERY,
      text: 'tdi',
      make: 'volkswagen',
      model: 'golf',
      priceMin: 5000,
      priceMax: 15000,
      fuel: [FuelType.DIESEL, FuelType.PETROL],
      location: 'Lyon',
    };
    const removal = (key: string) => describeFilters(full).find((filter) => filter.key === key)!.remove;

    it('removes one enum value and keeps the others', () => {
      expect(apply(full, removal('fuel:DIESEL')).fuel).toEqual([FuelType.PETROL]);
    });

    it('clears both ends of a range', () => {
      const after = apply(full, removal('price'));
      expect([after.priceMin, after.priceMax]).toEqual([null, null]);
    });

    it('takes the model away with the make', () => {
      // A model with no make is a filter nothing on the screen can display.
      const after = apply(full, removal('make'));
      expect([after.make, after.model]).toEqual([null, null]);
    });

    it('removes the model alone and keeps the make', () => {
      const after = apply(full, removal('model'));
      expect([after.make, after.model]).toEqual(['volkswagen', null]);
    });

    it('touches nothing but its own filter', () => {
      // Every chip, removed in turn: exactly one fewer filter each time, never two.
      for (const filter of describeFilters(full)) {
        const remaining = describeFilters(apply(full, filter.remove)).map((entry) => entry.key);
        const expected = describeFilters(full)
          .map((entry) => entry.key)
          .filter((key) => key !== filter.key && !(filter.key === 'make' && key === 'model'));

        expect(remaining, `after removing ${filter.key}`).toEqual(expected);
      }
    });
  });
});

describe('ActiveFilters', () => {
  @Component({
    imports: [ActiveFilters],
    template: `<app-active-filters [query]="query()" (changed)="patches.push($event)" (cleared)="cleared = cleared + 1" />`,
  })
  class Host {
    readonly query = signal<SearchQuery>(EMPTY_QUERY);
    readonly patches: Partial<SearchQuery>[] = [];
    cleared = 0;
  }

  async function render(query: SearchQuery) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    const backend = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.query.set(query);
    await fixture.whenStable();
    return { fixture, backend, root: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
  }

  it('renders nothing at all when no filter is set', async () => {
    // Not an empty bar with a lonely "tout effacer" in it.
    const { root } = await render(EMPTY_QUERY);
    expect(root.querySelector('.applied')).toBeNull();
    expect(root.textContent?.trim()).toBe('');
  });

  it('shows a removable chip per filter, and a way to clear them all', async () => {
    const { root, host } = await render({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL], location: 'Lyon' });
    const chips = Array.from(root.querySelectorAll('.applied__chip')) as HTMLButtonElement[];

    expect(chips.map((chip) => chip.textContent?.replace('×', '').trim())).toEqual(['Diesel', 'Lyon']);

    chips[0].click();
    expect(host.patches).toEqual([{ fuel: [] }]);

    (root.querySelector('.applied__clear') as HTMLButtonElement).click();
    expect(host.cleared).toBe(1);
  });

  it('says what each chip does, not just what it is', async () => {
    // "Diesel ×" is a word and a symbol to a screen reader.
    const { root } = await render({ ...EMPTY_QUERY, fuel: [FuelType.DIESEL] });
    const chip = root.querySelector('.applied__chip') as HTMLButtonElement;

    expect(chip.getAttribute('aria-label')).toBe('Retirer le filtre : Diesel');
    expect(chip.querySelector('[aria-hidden="true"]')?.textContent).toBe('×');
  });

  it('shows the catalogue name of the make once the catalogue answers', async () => {
    const { root, backend, fixture } = await render({ ...EMPTY_QUERY, make: 'citroen' });
    // Until then, the slug.
    expect(root.querySelector('.applied__chip')?.textContent).toContain('citroen');

    backend
      .expectOne(`${BASE}/api/v1/reference/makes`)
      .flush([{ id: 'm-1', slug: 'citroen', name: 'Citroën' }]);
    await fixture.whenStable();

    expect(root.querySelector('.applied__chip')?.textContent).toContain('Citroën');
  });

  it('keeps the slug when the catalogue cannot be read', async () => {
    const { root, backend, fixture } = await render({ ...EMPTY_QUERY, make: 'citroen' });
    backend.expectOne(`${BASE}/api/v1/reference/makes`).error(new ProgressEvent('offline'));
    await fixture.whenStable();

    expect(root.querySelector('.applied__chip')?.textContent).toContain('citroen');
  });
});
