import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DEFAULT_VIEW, ViewPreference } from './view-preference';

const KEY = 'autox.search.view';

/**
 * The remembered choice of list or grid.
 *
 * <p>Browser storage is the one dependency here that fails in the field rather than in
 * development: private windows and blocked site data make every access throw. A results
 * page must not depend on a convenience, so each failure is tested for what the user sees
 * — the toggle works, and is simply not remembered.
 */
describe('ViewPreference', () => {
  const create = () => {
    TestBed.resetTestingModule();
    return TestBed.inject(ViewPreference);
  };

  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('is the list until the visitor says otherwise', () => {
    expect(DEFAULT_VIEW).toBe('liste');
    expect(create().view()).toBe('liste');
  });

  it('remembers the choice across visits', () => {
    create().set('grille');

    // A new instance, as after a reload.
    expect(create().view()).toBe('grille');
    expect(localStorage.getItem(KEY)).toBe('grille');
  });

  it('switches back', () => {
    const preference = create();
    preference.set('grille');
    preference.set('liste');

    expect(preference.view()).toBe('liste');
    expect(create().view()).toBe('liste');
  });

  it.each(['mosaïque', '', '{"view":"grille"}', 'GRILLE'])(
    'ignores a stored value it does not recognise (%j)',
    (stored) => {
      // Storage is writable by anything on the origin, and a value from another release
      // must not select a view that does not exist.
      localStorage.setItem(KEY, stored);
      expect(create().view()).toBe('liste');
    },
  );

  it('still works when storage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });

    expect(create().view()).toBe('liste');
  });

  it('still switches when storage cannot be written — it is just not remembered', () => {
    const preference = create();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });

    expect(() => preference.set('grille')).not.toThrow();
    // The view the user asked for is on screen for this visit.
    expect(preference.view()).toBe('grille');
  });
});
