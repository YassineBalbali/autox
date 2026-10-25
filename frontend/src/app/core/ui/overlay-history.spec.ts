import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { OverlayHistory } from './overlay-history';

@Component({ template: '' })
class Blank {}

/** jsdom moves through history asynchronously, as a browser does. */
const traversed = () => new Promise((resolve) => setTimeout(resolve, 30));

/**
 * Back closes an overlay, and nothing else.
 *
 * <p>"Nothing else" is checked the way the router would notice: a popstate listener added
 * after this file's own — as the router's is — must never hear the Back that closed an
 * overlay.
 */
describe('OverlayHistory', () => {
  let overlays: OverlayHistory;
  let router: Router;
  let heard: string[];
  const listener = () => heard.push(location.pathname + location.search);

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: 'recherche', component: Blank }])],
    });
    router = TestBed.inject(Router);
    overlays = TestBed.inject(OverlayHistory);
    // The router's location is a test double here; the real address is jsdom's, set directly.
    history.replaceState(null, '', '/recherche');
    heard = [];
    window.addEventListener('popstate', listener);
  });

  afterEach(() => window.removeEventListener('popstate', listener));

  /** A filter applied in the sheet: the page records it in place of the sheet's entry. */
  async function applyFilter(): Promise<void> {
    history.replaceState(null, '', '/recherche?carburant=diesel');
    await router.navigateByUrl('/recherche?carburant=diesel', { replaceUrl: true });
  }

  it('adds a history entry at the same address when an overlay opens', () => {
    // Spied rather than counted: history.length stops growing at the browser's cap, which
    // a long test run reaches.
    const push = vi.spyOn(history, 'pushState');

    overlays.open(() => undefined);

    expect(push).toHaveBeenCalledWith(history.state, '', location.href);
    expect(location.pathname).toBe('/recherche');
    push.mockRestore();
  });

  it('closes the overlay on Back, and the router never hears of it', async () => {
    let closed = 0;
    overlays.open(() => closed++);

    history.back();
    await traversed();

    expect(closed).toBe(1);
    expect(heard).toEqual([]);
    expect(location.pathname + location.search).toBe('/recherche');
  });

  it('keeps where the overlay left the page: Back does not undo what was done in it', async () => {
    let closed = 0;
    overlays.open(() => closed++);
    await applyFilter();

    history.back();
    await traversed();

    expect(closed).toBe(1);
    expect(heard).toEqual([]);
    expect(location.search).toBe('?carburant=diesel');
    expect(router.url).toBe('/recherche?carburant=diesel');
  });

  it('takes its entry back when closed from its own controls with nothing changed', async () => {
    const entry = overlays.open(() => {
      throw new Error('not a Back');
    });

    entry.closed();
    await traversed();

    // The Back it made to remove the entry is its own: the router does not hear it either.
    expect(heard).toEqual([]);
    expect(location.pathname).toBe('/recherche');
    // And the next Back is the visitor's again, and reaches the page.
    history.back();
    await traversed();
    expect(heard).toHaveLength(1);
  });

  it('leaves the entry as the record of a change made in the overlay', async () => {
    const entry = overlays.open(() => undefined);
    await applyFilter();

    entry.closed();
    await traversed();

    expect(location.search).toBe('?carburant=diesel');
    expect(heard).toEqual([]);
  });

  it('lets Back through once the overlay was left by a link', async () => {
    const entry = overlays.open(() => {
      throw new Error('already gone');
    });

    entry.release();
    history.back();
    await traversed();

    expect(heard).toHaveLength(1);
  });
});
