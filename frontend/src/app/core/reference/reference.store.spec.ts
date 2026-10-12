import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH } from '../api';
import { ReferenceStore } from './reference.store';

const BASE = 'http://api.test';
const MAKES = `${BASE}/api/v1/reference/makes`;

/**
 * The reference data cache.
 *
 * <p>Two behaviours, pulling in opposite directions, and both needed: a list is fetched
 * once however many components want it — and a failure is <em>not</em> remembered, or one
 * dropped connection at startup would leave the filters empty until the page is reloaded.
 */
describe('ReferenceStore', () => {
  let store: ReferenceStore;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: BASE_PATH, useValue: BASE }],
    });
    store = TestBed.inject(ReferenceStore);
    backend = TestBed.inject(HttpTestingController);
  });

  it('asks once, however many callers there are', async () => {
    const first = firstValueFrom(store.makes());
    const second = firstValueFrom(store.makes());

    backend.expectOne(MAKES).flush([{ id: 'm-1', slug: 'peugeot', name: 'Peugeot' }]);

    expect(await first).toEqual(await second);
    // And a caller arriving after the answer gets it without another request.
    expect(await firstValueFrom(store.makes())).toHaveLength(1);
    backend.expectNone(MAKES);
  });

  it('does not ask at all until somebody subscribes', () => {
    store.makes();
    backend.expectNone(MAKES);
  });

  it('does not cache a failure', async () => {
    const failed = firstValueFrom(store.makes()).catch(() => 'failed');
    backend.expectOne(MAKES).error(new ProgressEvent('offline'));
    expect(await failed).toBe('failed');

    // The next caller tries again rather than being handed the old error for the rest
    // of the page's life.
    const retried = firstValueFrom(store.makes());
    backend.expectOne(MAKES).flush([{ id: 'm-1', slug: 'peugeot', name: 'Peugeot' }]);
    expect(await retried).toHaveLength(1);
  });

  it('caches models per make', async () => {
    const peugeot = firstValueFrom(store.models('m-peugeot'));
    const tesla = firstValueFrom(store.models('m-tesla'));

    backend.expectOne(`${MAKES}/m-peugeot/models`).flush([{ id: 'a', slug: '308', name: '308' }]);
    backend.expectOne(`${MAKES}/m-tesla/models`).flush([]);

    expect(await peugeot).toHaveLength(1);
    expect(await tesla).toEqual([]);
    await firstValueFrom(store.models('m-peugeot'));
    backend.expectNone(`${MAKES}/m-peugeot/models`);
  });

  it('unwraps the enum values', async () => {
    const enums = firstValueFrom(store.enums());
    backend.expectOne(`${BASE}/api/v1/reference/enums`).flush({ values: { Gearbox: ['MANUAL'] } });

    expect(await enums).toEqual({ Gearbox: ['MANUAL'] });
  });

  describe('minorUnits', () => {
    it('maps each currency to its number of decimals', async () => {
      const units = firstValueFrom(store.minorUnits());
      backend.expectOne(`${BASE}/api/v1/reference/countries`).flush([
        { code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 },
        { code: 'JP', currencyCode: 'JPY', currencyMinorUnit: 0 },
      ]);

      // Zero is a value: a currency with no minor unit must not be read as "unknown".
      expect(await units).toEqual({ EUR: 2, JPY: 0 });
    });

    it('answers with nothing, rather than failing, when the data cannot be read', async () => {
      // Prices then fall back to the default exponent. A reference outage should not take
      // the results list down with it.
      const units = firstValueFrom(store.minorUnits());
      backend.expectOne(`${BASE}/api/v1/reference/countries`).error(new ProgressEvent('offline'));

      expect(await units).toEqual({});
    });
  });
});
