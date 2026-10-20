import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ResultCount } from '../../core/api';
import { EMPTY_QUERY } from './search-query';
import { COUNT_RETRIES, COUNT_RETRY_DELAY_MS, SearchResolver } from './search-resolver';

const BASE = 'http://api.test';
const COUNT = `${BASE}/api/v1/listings/count`;

/**
 * The count every screen shows beside a search. The rule under test: it never errors,
 * and a failure that may pass is tried again before the number is given up.
 */
describe('SearchResolver.count', () => {
  let backend: HttpTestingController;
  let results: (ResultCount | null)[];

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    backend = TestBed.inject(HttpTestingController);
    results = [];
    TestBed.inject(SearchResolver)
      .count(EMPTY_QUERY)
      .subscribe((count) => results.push(count));
    // The currency lookup comes first, for the price bounds.
    backend.match(`${BASE}/api/v1/reference/countries`).forEach((request) => request.flush([]));
  });

  afterEach(() => vi.useRealTimers());

  const fail = (status: number) =>
    backend.expectOne(COUNT).flush({ code: 'X' }, { status, statusText: 'Error' });
  const waitForRetry = (attempt: number) => vi.advanceTimersByTime(COUNT_RETRY_DELAY_MS * attempt);

  it('answers with the count', () => {
    backend.expectOne(COUNT).flush({ count: 60, capped: false });
    expect(results).toEqual([{ count: 60, capped: false }]);
  });

  it.each([0, 429, 500, 503])('tries again after a %s, and answers when a retry succeeds', (status) => {
    fail(status);
    waitForRetry(1);
    backend.expectOne(COUNT).flush({ count: 60, capped: false });

    expect(results).toEqual([{ count: 60, capped: false }]);
  });

  it(`gives up after ${COUNT_RETRIES} retries, with null rather than an error`, () => {
    fail(503);
    for (let attempt = 1; attempt <= COUNT_RETRIES; attempt++) {
      waitForRetry(attempt);
      fail(503);
    }

    expect(results).toEqual([null]);
    backend.verify();
  });

  it('does not retry a request the server will always refuse', () => {
    fail(400);

    expect(results).toEqual([null]);
    vi.advanceTimersByTime(COUNT_RETRY_DELAY_MS * 10);
    backend.verify();
  });
});
