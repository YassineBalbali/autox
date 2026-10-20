import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FAVOURITES_LIMIT, Favourites } from './favourites';

const KEY = 'autox.favourites';

/** The hearts, remembered in this browser and nowhere else. */
describe('Favourites', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  function create(): Favourites {
    TestBed.resetTestingModule();
    return TestBed.inject(Favourites);
  }

  it('starts empty', () => {
    expect(create().ids()).toEqual([]);
  });

  it('marks and unmarks a listing', () => {
    const favourites = create();

    favourites.toggle('a');
    expect(favourites.has('a')).toBe(true);

    favourites.toggle('a');
    expect(favourites.has('a')).toBe(false);
    expect(favourites.ids()).toEqual([]);
  });

  it('keeps the most recently marked first', () => {
    const favourites = create();
    favourites.toggle('a');
    favourites.toggle('b');

    expect(favourites.ids()).toEqual(['b', 'a']);
  });

  it('remembers them for the next visit', () => {
    create().toggle('a');

    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual(['a']);
    expect(create().has('a')).toBe(true);
  });

  it('ignores a stored entry that is not a list of ids', () => {
    // Written by hand, or by a future version: read defensively, never trusted.
    localStorage.setItem(KEY, '{"a":1}');
    expect(create().ids()).toEqual([]);

    localStorage.setItem(KEY, '["a", 3, null, "b"]');
    expect(create().ids()).toEqual(['a', 'b']);

    localStorage.setItem(KEY, 'not json');
    expect(create().ids()).toEqual([]);
  });

  it('keeps no more than the limit', () => {
    const favourites = create();
    for (let i = 0; i <= FAVOURITES_LIMIT; i++) {
      favourites.toggle(`l-${i}`);
    }

    expect(favourites.ids()).toHaveLength(FAVOURITES_LIMIT);
    // The oldest is the one that goes.
    expect(favourites.has('l-0')).toBe(false);
    expect(favourites.has(`l-${FAVOURITES_LIMIT}`)).toBe(true);
  });

  it('still works for the visit when storage refuses', () => {
    // A private window, blocked site data: the heart still fills, it is only not remembered.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    const favourites = create();

    expect(() => favourites.toggle('a')).not.toThrow();
    expect(favourites.has('a')).toBe(true);
  });
});
