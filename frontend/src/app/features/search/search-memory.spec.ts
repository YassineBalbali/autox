import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { SearchMemory, isSearchUrl } from './search-memory';

/**
 * Which URLs count as "the search".
 *
 * <p>Strict on purpose: `isSearchUrl` decides whether a listing's back link may call
 * `history.back()`. A false positive steps the visitor back to wherever they came from —
 * another site, possibly — when they asked for the results.
 */
describe('isSearchUrl', () => {
  it.each(['/', '/?carburant=diesel', '/?q=golf%20tdi&tri=prix-croissant'])('accepts %s', (url) => {
    expect(isSearchUrl(url)).toBe(true);
  });

  it.each([
    '/annonces/01a1306d-db66-7b93-b280-f3992929d5d3',
    '/mon-compte',
    '/connexion?suivant=%2F',
    '',
    null,
    undefined,
    'https://ailleurs.example/',
    '//ailleurs.example/?x=1',
  ])('refuses %s', (url) => {
    expect(isSearchUrl(url)).toBe(false);
  });
});

describe('SearchMemory', () => {
  it('knows nothing until a search has been made', () => {
    expect(TestBed.inject(SearchMemory).lastUrl()).toBeNull();
  });

  it('remembers the last search', () => {
    const memory = TestBed.inject(SearchMemory);
    memory.remember('/?carburant=diesel');
    memory.remember('/?marque=tesla');

    expect(memory.lastUrl()).toBe('/?marque=tesla');
  });

  it('ignores a URL that is not a search, keeping the one it had', () => {
    // Otherwise "back to the results" could be made to lead anywhere at all.
    const memory = TestBed.inject(SearchMemory);
    memory.remember('/?carburant=diesel');
    memory.remember('/mon-compte');
    memory.remember('https://ailleurs.example/');

    expect(memory.lastUrl()).toBe('/?carburant=diesel');
  });
});
