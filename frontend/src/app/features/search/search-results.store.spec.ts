import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, FuelType, PublicListingSummary, SearchSort } from '../../core/api';
import { PAGE_SIZE, SearchResultsStore } from './search-results.store';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/listings`;
const COUNT = `${BASE}/api/v1/listings/count`;

const listing = (id: string): PublicListingSummary => ({ id, title: `Annonce ${id}` });

/**
 * The results store: one search's rows, its count, and "voir plus".
 *
 * <p>Two properties here are correctness rather than polish. A slower response to an
 * earlier search must never be shown as the answer to a later one; and the next page must
 * be appended to the list it belongs to, exactly once.
 */
describe('SearchResultsStore', () => {
  let store: SearchResultsStore;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: BASE_PATH, useValue: BASE },
        SearchResultsStore,
      ],
    });
    store = TestBed.inject(SearchResultsStore);
    backend = TestBed.inject(HttpTestingController);
  });

  /** The pending request to one endpoint — matched on the path, whatever the query. */
  const pending = (url: string) =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  const one = (url: string) => {
    const requests = pending(url);
    expect(requests, `requests to ${url}`).toHaveLength(1);
    return requests[0];
  };

  describe('a search', () => {
    it('asks for the first page and the count together, and shows a loading state', () => {
      store.search({ fuelType: [FuelType.DIESEL] }, SearchSort.PRICE_ASC);

      expect(store.status()).toBe('loading');
      const page = one(LIST);
      expect(page.request.params.get('sort')).toBe('PRICE_ASC');
      expect(page.request.params.get('size')).toBe(String(PAGE_SIZE));
      expect(page.request.params.has('cursor')).toBe(false);
      // The criteria travel as flat parameters, which is what the API binds.
      expect(page.request.params.getAll('fuelType')).toEqual(['DIESEL']);
      expect(one(COUNT).request.params.getAll('fuelType')).toEqual(['DIESEL']);
    });

    it('shows the rows as soon as they arrive, without waiting for the count', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a'), listing('b')], hasMore: false });

      // The count has not answered. The list is what the user came for.
      expect(store.status()).toBe('ready');
      expect(store.items().map((item) => item.id)).toEqual(['a', 'b']);
      expect(store.count()).toBeNull();

      one(COUNT).flush({ count: 2, capped: false });
      expect(store.count()).toEqual({ count: 2, capped: false });
    });

    it('is not an error when only the count fails', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a')], hasMore: false });
      one(COUNT).flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'x' });

      // The rows are correct; the heading simply has no number.
      expect(store.status()).toBe('ready');
      expect(store.count()).toBeNull();
      expect(store.error()).toBeNull();
    });

    it('reports a failed page in French, from the code and not the API text', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush(
        { code: ErrorCode.RATE_LIMITED, detail: 'Too many requests.', retryAfterSeconds: 30 },
        { status: 429, statusText: 'Too Many Requests' },
      );

      expect(store.status()).toBe('error');
      expect(store.error()).toContain('Trop de requêtes');
      expect(store.error()).not.toContain('Too many');
    });

    it('distinguishes an empty result from a failure', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [], hasMore: false });

      expect(store.status()).toBe('ready');
      expect(store.items()).toEqual([]);
    });

    it('can be retried after failing', () => {
      store.search({ fuelType: [FuelType.DIESEL] }, SearchSort.NEWEST);
      one(LIST).error(new ProgressEvent('offline'));
      pending(COUNT).forEach((request) => request.error(new ProgressEvent('offline')));
      expect(store.status()).toBe('error');

      store.retry();

      expect(store.status()).toBe('loading');
      // The same search, not an empty one.
      const retried = one(LIST);
      expect(retried.request.params.getAll('fuelType')).toEqual(['DIESEL']);
      retried.flush({ items: [listing('a')], hasMore: false });
      expect(store.status()).toBe('ready');
    });
  });

  describe('a new search while one is in flight', () => {
    it('cancels the old one, so a slow answer cannot overwrite a newer search', () => {
      // The bug this prevents: the user clicks "Diesel" then "Essence" quickly, the
      // diesel response arrives last, and the screen shows diesel cars under a petrol
      // filter. The later response is not necessarily the later search.
      store.search({ fuelType: [FuelType.DIESEL] }, SearchSort.NEWEST);
      const stale = backend.match((request) => request.url === LIST)[0];

      store.search({ fuelType: [FuelType.PETROL] }, SearchSort.NEWEST);

      expect(stale.cancelled).toBe(true);
      const fresh = one(LIST);
      expect(fresh.request.params.getAll('fuelType')).toEqual(['PETROL']);
      fresh.flush({ items: [listing('petrol')], hasMore: false });

      expect(store.items().map((item) => item.id)).toEqual(['petrol']);
    });

    it('discards the rows and the count of the previous search immediately', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a')], hasMore: true, nextCursor: 'c1' });
      one(COUNT).flush({ count: 40, capped: false });

      store.search({ fuelType: [FuelType.ELECTRIC] }, SearchSort.NEWEST);

      // Not left on screen under the new heading while the new request is pending.
      expect(store.items()).toEqual([]);
      expect(store.count()).toBeNull();
      expect(store.hasMore()).toBe(false);
      expect(store.status()).toBe('loading');
    });
  });

  describe('voir plus', () => {
    const firstPage = () => {
      store.search({}, SearchSort.PRICE_ASC);
      one(LIST).flush({ items: [listing('a'), listing('b')], hasMore: true, nextCursor: 'cursor-1' });
      pending(COUNT).forEach((request) => request.flush({ count: 5, capped: false }));
    };

    it('asks for the page after the cursor and appends it', () => {
      firstPage();
      expect(store.hasMore()).toBe(true);

      store.loadMore();

      expect(store.loadingMore()).toBe(true);
      const next = one(LIST);
      expect(next.request.params.get('cursor')).toBe('cursor-1');
      // The same sort: a cursor is bound to the order it was issued under.
      expect(next.request.params.get('sort')).toBe('PRICE_ASC');
      next.flush({ items: [listing('c')], hasMore: false });

      expect(store.items().map((item) => item.id)).toEqual(['a', 'b', 'c']);
      expect(store.loadingMore()).toBe(false);
      expect(store.hasMore()).toBe(false);
    });

    it('does not count again — the total has not changed', () => {
      firstPage();
      store.loadMore();

      expect(pending(COUNT)).toHaveLength(0);
      one(LIST).flush({ items: [], hasMore: false });
    });

    it('ignores a second click while a page is loading', () => {
      // A double click would otherwise request the same page twice and show its rows twice.
      firstPage();
      store.loadMore();
      store.loadMore();

      one(LIST).flush({ items: [listing('c')], hasMore: false });
      expect(store.items()).toHaveLength(3);
    });

    it('keeps the rows already shown when the next page fails, and lets it be retried', () => {
      firstPage();
      store.loadMore();
      one(LIST).error(new ProgressEvent('offline'));

      // Losing the first two pages because the third failed would be absurd.
      expect(store.items().map((item) => item.id)).toEqual(['a', 'b']);
      expect(store.status()).toBe('ready');
      expect(store.loadMoreError()).toContain('Vérifiez votre connexion');
      expect(store.hasMore()).toBe(true);

      store.loadMore();
      const retried = one(LIST);
      expect(retried.request.params.get('cursor')).toBe('cursor-1');
      retried.flush({ items: [listing('c')], hasMore: false });

      expect(store.items()).toHaveLength(3);
      expect(store.loadMoreError()).toBeNull();
    });

    it('does nothing when there is no further page', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a')], hasMore: false });
      pending(COUNT).forEach((request) => request.flush({ count: 1, capped: false }));

      store.loadMore();

      expect(pending(LIST)).toHaveLength(0);
    });

    it('never appends a page of the old search to a new one', () => {
      firstPage();
      store.loadMore();
      const stalePage = backend.match((request) => request.url === LIST)[0];

      store.search({ fuelType: [FuelType.ELECTRIC] }, SearchSort.NEWEST);

      expect(stalePage.cancelled).toBe(true);
      one(LIST).flush({ items: [listing('tesla')], hasMore: false });
      expect(store.items().map((item) => item.id)).toEqual(['tesla']);
    });
  });

  describe('coming back to a search', () => {
    const loadTwoPages = () => {
      store.search({ fuelType: [FuelType.DIESEL] }, SearchSort.PRICE_ASC);
      one(LIST).flush({ items: [listing('a'), listing('b')], hasMore: true, nextCursor: 'cursor-1' });
      one(COUNT).flush({ count: 5, capped: false });
      store.loadMore();
      one(LIST).flush({ items: [listing('c')], hasMore: true, nextCursor: 'cursor-2' });
    };

    it('shows what it already holds for the same search, without asking again', () => {
      // The visitor opened a listing and came back. They expect the list as they left it:
      // every page they had loaded, so the page is as tall as it was and the browser can
      // put them back where they were.
      loadTwoPages();

      const reused = store.restore({ fuelType: [FuelType.DIESEL] }, SearchSort.PRICE_ASC);

      expect(reused).toBe(true);
      expect(pending(LIST)).toHaveLength(0);
      expect(pending(COUNT)).toHaveLength(0);
      expect(store.status()).toBe('ready');
      expect(store.items().map((item) => item.id)).toEqual(['a', 'b', 'c']);
      expect(store.count()).toEqual({ count: 5, capped: false });
    });

    it('can still load the page after the ones it kept', () => {
      loadTwoPages();
      store.restore({ fuelType: [FuelType.DIESEL] }, SearchSort.PRICE_ASC);

      store.loadMore();

      expect(one(LIST).request.params.get('cursor')).toBe('cursor-2');
    });

    it('searches afresh when the criteria differ', () => {
      loadTwoPages();

      const reused = store.restore({ fuelType: [FuelType.PETROL] }, SearchSort.PRICE_ASC);

      expect(reused).toBe(false);
      expect(store.items()).toEqual([]);
      expect(one(LIST).request.params.getAll('fuelType')).toEqual(['PETROL']);
    });

    it('searches afresh when only the sort order differs', () => {
      // Same filters, another order: a different list, and a cursor that no longer applies.
      loadTwoPages();

      expect(store.restore({ fuelType: [FuelType.DIESEL] }, SearchSort.NEWEST)).toBe(false);
      expect(one(LIST).request.params.get('sort')).toBe('NEWEST');
    });

    it('does not mistake a differently ordered criteria object for a different search', () => {
      store.search({ fuelType: [FuelType.DIESEL], yearFrom: 2019 }, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a')], hasMore: false });
      pending(COUNT).forEach((request) => request.flush({ count: 1, capped: false }));

      expect(store.restore({ yearFrom: 2019, fuelType: [FuelType.DIESEL] }, SearchSort.NEWEST)).toBe(true);
    });

    it('searches when it holds nothing', () => {
      expect(store.restore({}, SearchSort.NEWEST)).toBe(false);
      expect(pending(LIST)).toHaveLength(1);
    });

    it('does not restore a search that had failed', () => {
      store.search({}, SearchSort.NEWEST);
      one(LIST).error(new ProgressEvent('offline'));
      pending(COUNT).forEach((request) => request.error(new ProgressEvent('offline')));

      // Coming back to an error screen with no request made would leave the visitor
      // looking at a failure that nothing is retrying.
      expect(store.restore({}, SearchSort.NEWEST)).toBe(false);
      expect(store.status()).toBe('loading');
    });

    it('is only ever used for a return: an ordinary search always asks the API', () => {
      // Not a cache. A list that showed held rows because the criteria happened to match
      // would be a list with sold cars in it.
      loadTwoPages();

      store.search({ fuelType: [FuelType.DIESEL] }, SearchSort.PRICE_ASC);

      expect(pending(LIST)).toHaveLength(1);
      expect(store.items()).toEqual([]);
    });
  });

  describe('when the search page goes away', () => {
    it('abandons a page that was still loading, so "voir plus" is not left stuck', () => {
      // The store outlives the page. Without this, a visitor who pressed "voir plus" and
      // immediately opened a listing would come back to a button frozen on "Chargement…".
      store.search({}, SearchSort.NEWEST);
      one(LIST).flush({ items: [listing('a')], hasMore: true, nextCursor: 'cursor-1' });
      pending(COUNT).forEach((request) => request.flush({ count: 3, capped: false }));
      store.loadMore();
      const inFlight = backend.match((request) => request.url === LIST)[0];

      store.release();

      expect(inFlight.cancelled).toBe(true);
      expect(store.loadingMore()).toBe(false);
      // What was loaded is kept, and the next page can be asked for again.
      expect(store.items()).toHaveLength(1);
      expect(store.hasMore()).toBe(true);
    });

    it('does not keep a first page that never arrived', () => {
      store.search({}, SearchSort.NEWEST);
      store.release();

      // Nothing to come back to: the return searches again.
      expect(store.restore({}, SearchSort.NEWEST)).toBe(false);
    });
  });
});
