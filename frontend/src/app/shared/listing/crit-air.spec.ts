import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, CritAir } from '../../core/api';
import { describeFilters } from '../../features/search/active-filters';
import { SearchFilters } from '../../features/search/search-filters';
import {
  EMPTY_QUERY,
  SearchQuery,
  activeFilterCount,
  parseQuery,
  toCriteria,
  toQueryParams,
} from '../../features/search/search-query';
import { CritAirBadge } from './crit-air';

const NO_VEHICLE = { makeId: undefined, makeText: undefined, modelId: undefined, modelText: undefined };

/** The estimated Crit'Air on a listing, and "Crit'Air max" in the search. The class itself is the API's. */
describe('Crit’Air', () => {
  async function badge(critAir: CritAir | null): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    const fixture = TestBed.createComponent(CritAirBadge);
    fixture.componentRef.setInput('critAir', critAir);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it.each([
    [CritAir.ZERO, '0', 'Crit’Air 0 (électrique)'],
    [CritAir.ONE, '1', 'Crit’Air 1'],
    [CritAir.TWO, '2', 'Crit’Air 2'],
    [CritAir.THREE, '3', 'Crit’Air 3'],
    [CritAir.FOUR, '4', 'Crit’Air 4'],
    [CritAir.FIVE, '5', 'Crit’Air 5'],
  ])('shows %s as its coloured vignette, says what it is based on, and what it means in a ZFE', async (value, number, label) => {
    const root = await badge(value);

    const vignette = root.querySelector('.crit-air__vignette') as HTMLElement;
    expect(vignette.getAttribute('data-class')).toBe(number);
    expect(vignette.querySelector('.crit-air__number')?.textContent?.trim()).toBe(number);
    expect(root.querySelector('.crit-air__label')?.textContent?.trim()).toBe(label);
    expect(root.textContent).toContain('Crit’Air estimée d’après l’énergie et la date de 1re immatriculation');
    const zfe = root.querySelector('[data-zfe]')?.textContent?.replace(/\s+/g, ' ');
    expect(zfe).toContain('zones à faibles émissions (ZFE)');
    expect(zfe).toContain('vérifiez les règles de la ville');
  });

  it('says "non classé" without a number for a car too old for any vignette', async () => {
    const root = await badge(CritAir.UNCLASSIFIED);

    expect(root.querySelector('.crit-air__vignette')?.getAttribute('data-class')).toBe('NC');
    expect(root.querySelector('.crit-air__number')).toBeNull();
    expect(root.querySelector('.crit-air__label')?.textContent?.trim()).toBe('Non classé');
  });

  it('shows nothing at all when the API gives no class', async () => {
    const root = await badge(null);

    expect(root.textContent?.trim()).toBe('');
  });

  describe('Crit’Air max in the search', () => {
    it('is read from the URL, 0 to 5 only, written back, sent to the API, and counted as a filter', () => {
      const query = parseQuery(convertToParamMap({ critair_max: '2' }));
      expect(query.critAirMax).toBe(2);
      expect(toQueryParams(query)).toEqual({ critair_max: '2' });
      expect(toCriteria(query, NO_VEHICLE, 'EUR', 2).critAirMax).toBe(2);
      expect(activeFilterCount(query)).toBe(1);

      for (const invalid of ['6', '-1', '2.5', 'deux', '']) {
        expect(parseQuery(convertToParamMap({ critair_max: invalid })).critAirMax, invalid).toBeNull();
      }
      expect(toCriteria(EMPTY_QUERY, NO_VEHICLE, 'EUR', 2).critAirMax).toBeUndefined();
      expect(toQueryParams(EMPTY_QUERY)).toEqual({});
    });

    it('is a chip that removes it', () => {
      const [chip] = describeFilters({ ...EMPTY_QUERY, critAirMax: 2 });
      expect(chip.label).toBe('Crit’Air 2 ou mieux');
      expect(chip.remove).toEqual({ critAirMax: null });
      expect(describeFilters({ ...EMPTY_QUERY, critAirMax: 0 })[0].label).toBe('Crit’Air 0');
    });

    @Component({
      imports: [SearchFilters],
      template: `<app-search-filters [query]="query()" [resultCount]="null" (changed)="patches.push($event)" />`,
    })
    class Host {
      readonly query = signal<SearchQuery>(EMPTY_QUERY);
      readonly patches: Partial<SearchQuery>[] = [];
    }

    it('is chosen in the sidebar, one at most, and pressed again cleared', async () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: 'http://api.test' }],
      });
      const fixture = TestBed.createComponent(Host);
      await fixture.whenStable();
      TestBed.inject(HttpTestingController).match(() => true).forEach((request) => request.flush({ values: {} }));
      await fixture.whenStable();
      const root: HTMLElement = fixture.nativeElement;
      const chip = (value: number) => root.querySelector(`[data-crit-air-max="${value}"] button`) as HTMLButtonElement;

      expect(root.querySelector('[data-filter="crit-air"] legend')?.textContent?.trim()).toBe('Crit’Air maximum');
      chip(1).click();
      expect(fixture.componentInstance.patches.at(-1)).toEqual({ critAirMax: 1 });

      fixture.componentInstance.query.set({ ...EMPTY_QUERY, critAirMax: 1 });
      await fixture.whenStable();
      expect(chip(1).getAttribute('aria-pressed')).toBe('true');
      chip(1).click();
      expect(fixture.componentInstance.patches.at(-1)).toEqual({ critAirMax: null });
    });
  });
});
