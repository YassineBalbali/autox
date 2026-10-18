import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FuelType, SearchSort } from '../../core/api';
import { RECENT_LIMIT, RecentSearches } from './recent-searches';
import { EMPTY_QUERY, SearchQuery } from './search-query';

const KEY = 'autox.search.recent';
const query = (patch: Partial<SearchQuery>): SearchQuery => ({ ...EMPTY_QUERY, ...patch });
const stored = () => JSON.parse(localStorage.getItem(KEY) ?? 'null');

/** A fresh service, reading whatever is in storage now. */
function service(): RecentSearches {
  TestBed.resetTestingModule();
  return TestBed.inject(RecentSearches);
}

describe('RecentSearches', () => {
  beforeEach(() => localStorage.clear());

  it('starts empty', () => {
    expect(service().isEmpty()).toBe(true);
  });

  it('keeps a search, newest first, as the query parameters of its link', () => {
    const recent = service();
    recent.record(query({ make: 'peugeot', priceMax: 15000 }));
    recent.record(query({ fuel: [FuelType.ELECTRIC] }));

    expect(stored()).toEqual([{ carburant: 'electric' }, { marque: 'peugeot', prix_max: '15000' }]);
    expect(recent.entries().map((entry) => entry.make)).toEqual([null, 'peugeot']);
  });

  it('keeps no more than the home page shows', () => {
    const recent = service();
    for (let year = 2015; year < 2015 + RECENT_LIMIT + 2; year++) {
      recent.record(query({ yearMin: year }));
    }

    expect(recent.entries()).toHaveLength(RECENT_LIMIT);
    expect(recent.entries()[0].yearMin).toBe(2015 + RECENT_LIMIT + 1);
  });

  it('moves a repeated search to the front instead of listing it twice — a re-sort is the same search', () => {
    const recent = service();
    recent.record(query({ make: 'tesla' }));
    recent.record(query({ priceMax: 9000 }));
    recent.record(query({ make: 'tesla', sort: SearchSort.PRICE_ASC }));

    expect(stored()).toEqual([{ marque: 'tesla' }, { prix_max: '9000' }]);
  });

  it('ignores a search with no filters: that is the results page itself', () => {
    const recent = service();
    recent.record(query({ sort: SearchSort.PRICE_DESC }));

    expect(recent.isEmpty()).toBe(true);
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('removes one, and forgets it', () => {
    const recent = service();
    recent.record(query({ make: 'tesla' }));
    recent.record(query({ priceMax: 9000 }));
    recent.remove(query({ make: 'tesla' }));

    expect(stored()).toEqual([{ prix_max: '9000' }]);
  });

  it('survives a reload', () => {
    service().record(query({ make: 'tesla' }));

    expect(service().entries()[0].make).toBe('tesla');
  });

  it('reads stored entries as untrusted, exactly as it would a URL', () => {
    localStorage.setItem(
      KEY,
      JSON.stringify([
        { prix_max: 'banana', carburant: 'plutonium' }, // nothing valid left: dropped
        { marque: 'peugeot', annee_min: '1850' }, // the year is out of bounds and goes
        'not an object',
        { marque: 7 }, // not strings
        { marque: 'peugeot' }, // the same search again: once
        { boite: 'automatic' },
        { carrosserie: 'suv' },
      ]),
    );

    const entries = service().entries();
    expect(entries.map((entry) => [entry.make, entry.yearMin, entry.gearbox, entry.body])).toEqual([
      ['peugeot', null, [], []],
      [null, null, ['AUTOMATIC'], []],
      [null, null, [], ['SUV']],
    ]);
  });

  it('treats storage that is not JSON, or not a list, as empty', () => {
    localStorage.setItem(KEY, '{not json');
    expect(service().isEmpty()).toBe(true);

    localStorage.setItem(KEY, JSON.stringify({ marque: 'peugeot' }));
    expect(service().isEmpty()).toBe(true);
  });

  it('still works for the visit when storage refuses to save', () => {
    const recent = service();
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });

    recent.record(query({ make: 'tesla' }));
    expect(recent.entries()[0].make).toBe('tesla');

    setItem.mockRestore();
  });
});
