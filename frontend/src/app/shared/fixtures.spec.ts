import { convertToParamMap } from '@angular/router';

import { LeasingView, PublicListingSummary } from '../core/api';
import { describeFilters } from '../features/search/active-filters';
import {
  EMPTY_QUERY,
  SearchQuery,
  activeFilterCount,
  parseQuery,
  toCriteria,
  toQueryParams,
} from '../features/search/search-query';
import DISPLAY from '../../../fixtures/display.json';
import SEARCH from '../../../fixtures/search-query.json';
import { formatIntegerFr } from './format/number';
import { leasingTerms } from './listing/leasing.fr';
import { formatPrice } from './listing/price';
import { headlineOf, placeOf } from './listing/summary';
import { vehicleName } from './listing/vehicle-name';

/**
 * The shared test vectors in `frontend/fixtures/`, against the web's own functions.
 *
 * <p>The Android app runs the same files against its Dart port of these rules
 * (`mobile/test/shared_fixtures_test.dart`). So a rule changed here and not there, or there and
 * not here, fails one of the two suites: the site and the app cannot quietly disagree about what
 * a search means or how a price is written.
 */
const query = (partial: object): SearchQuery => ({ ...EMPTY_QUERY, ...partial }) as SearchQuery;

describe('shared fixtures: search', () => {
  for (const c of SEARCH.toCriteria) {
    it(`toCriteria — ${c.name}`, () => {
      expect(toCriteria(query(c.query), c.vehicle, 'EUR', 2)).toEqual(c.criteria);
    });
  }

  for (const c of SEARCH.toQueryParams) {
    it(`toQueryParams — ${c.name}`, () => {
      expect(toQueryParams(query(c.query))).toEqual(c.params);
    });
  }

  for (const c of SEARCH.parseQuery) {
    it(`parseQuery — ${c.name}`, () => {
      expect(parseQuery(convertToParamMap(c.params))).toEqual(query(c.query));
    });
  }

  for (const c of SEARCH.describeFilters) {
    it(`describeFilters — ${c.name}`, () => {
      expect(describeFilters(query(c.query), c.names).map((filter) => filter.label)).toEqual(
        c.labels,
      );
      expect(activeFilterCount(query(c.query))).toBe(c.activeFilterCount);
    });
  }
});

describe('shared fixtures: display', () => {
  it('formatPrice', () => {
    for (const c of DISPLAY.formatPrice) {
      expect(formatPrice(c.amountMinor, c.currency, c.minorUnit)).toBe(c.text);
    }
  });

  it('formatIntegerFr', () => {
    for (const c of DISPLAY.formatInteger) {
      expect(formatIntegerFr(c.value)).toBe(c.text);
    }
  });

  it('vehicleName', () => {
    for (const c of DISPLAY.vehicleName) {
      expect(vehicleName(c.make, c.model)).toBe(c.text);
    }
  });

  it('placeOf', () => {
    for (const c of DISPLAY.placeOf) {
      expect(placeOf(c as PublicListingSummary)).toBe(c.text);
    }
  });

  it('headlineOf', () => {
    for (const c of DISPLAY.headlineOf) {
      expect(headlineOf(c as PublicListingSummary)).toEqual(c.expected);
    }
  });

  it('leasingTerms', () => {
    for (const c of DISPLAY.leasingTerms) {
      expect(leasingTerms(c as LeasingView, formatIntegerFr)).toBe(c.text);
    }
  });
});
