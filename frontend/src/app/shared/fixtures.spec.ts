import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { convertToParamMap } from '@angular/router';

import {
  Feature,
  ImageResponse,
  LeasingView,
  PublicListingDetail,
  PublicListingSummary,
} from '../core/api';
import { memberSinceFr } from '../features/dealers/dealer-directory-page';
import { parseAmount, parseRate } from '../features/listing/financing-simulator';
import { registration } from '../features/listing/key-facts';
import { marketBasis } from '../features/listing/listing-summary';
import { galleryImages, groupFeatures, specificationRows } from '../features/listing/listing-view';
import { describeFilters } from '../features/search/active-filters';
import {
  EMPTY_QUERY,
  SearchQuery,
  activeFilterCount,
  parseQuery,
  toCriteria,
  toQueryParams,
} from '../features/search/search-query';
import { ErrorMessageService, waitSentenceFr } from '../core/errors/error-message.service';
import DISPLAY from '../../../fixtures/display.json';
import FORMS from '../../../fixtures/forms.json';
import LISTING from '../../../fixtures/listing.json';
import SEARCH from '../../../fixtures/search-query.json';
import {
  MAX_LENGTHS,
  emailValidators,
  firstErrorFr,
  matchesPassword,
  passwordValidators,
  phoneValidators,
  requiredText,
} from './forms/validation';
import { formatDateFr } from './format/date';
import { formatIntegerFr } from './format/number';
import { countFr } from './format/plural';
import { simulateCredit } from './listing/financing';
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

describe('shared fixtures: listing', () => {
  for (const c of LISTING.specificationRows) {
    it(`specificationRows — ${c.name}`, () => {
      expect(specificationRows(c.listing as unknown as PublicListingDetail)).toEqual(c.rows);
    });
  }

  for (const c of LISTING.groupFeatures) {
    it(`groupFeatures — ${c.name}`, () => {
      expect(groupFeatures(c.codes, c.reference as unknown as Feature[])).toEqual(c.groups);
    });
  }

  it('registration', () => {
    for (const c of LISTING.registration) {
      expect(registration(c.year, c.month)).toBe(c.text);
    }
  });

  for (const c of LISTING.marketBasis) {
    it(`marketBasis — ${c.name}`, () => {
      expect(marketBasis(c.listing as unknown as PublicListingDetail, 2)).toBe(c.text);
    });
  }

  for (const c of LISTING.simulateCredit) {
    it(`simulateCredit — ${c.name}`, () => {
      expect(simulateCredit(c)).toEqual(c.result);
    });
  }

  it('parseAmount and parseRate', () => {
    for (const c of LISTING.parseAmount) {
      expect(parseAmount(c.text)).toBe(c.value);
    }
    for (const c of LISTING.parseRate) {
      expect(parseRate(c.text)).toBe(c.value);
    }
  });

  for (const c of LISTING.galleryImages) {
    it(`galleryImages — ${c.name}`, () => {
      expect(
        galleryImages({ images: c.images as unknown as ImageResponse[] } as PublicListingDetail),
      ).toEqual(c.gallery);
    });
  }

  it('memberSinceFr, formatDateFr, countFr', () => {
    for (const c of LISTING.memberSince) {
      expect(memberSinceFr(c.iso)).toBe(c.text);
    }
    for (const c of LISTING.formatDate) {
      expect(formatDateFr(c.iso)).toBe(c.text);
    }
    for (const c of LISTING.countFr) {
      expect(countFr(c.count, c.singular, c.plural)).toBe(c.text);
    }
  });
});

describe('shared fixtures: forms', () => {
  const fields: Record<string, ValidatorFn[]> = {
    email: emailValidators,
    password: passwordValidators,
    displayName: [requiredText(), Validators.maxLength(MAX_LENGTHS.displayName)],
    firstName: [Validators.maxLength(MAX_LENGTHS.firstName)],
    lastName: [Validators.maxLength(MAX_LENGTHS.lastName)],
    phone: phoneValidators,
  };

  it('validates each field as the forms do', () => {
    for (const c of FORMS.validate) {
      const control = new FormControl(c.value, { validators: fields[c.field] });
      expect({ field: c.field, value: c.value, message: firstErrorFr(control.errors) }).toEqual(c);
    }
  });

  it('checks the password confirmation', () => {
    for (const c of FORMS.confirmation) {
      const group = new FormGroup({
        password: new FormControl(c.password),
        passwordConfirmation: new FormControl(c.confirmation, {
          validators: [requiredText(), matchesPassword('password')],
        }),
      });
      group.controls.passwordConfirmation.updateValueAndValidity();
      expect(firstErrorFr(group.controls.passwordConfirmation.errors)).toBe(c.message);
    }
  });

  it('words every validator key', () => {
    for (const c of FORMS.errors) {
      expect(firstErrorFr(c.errors as ValidationErrors)).toBe(c.message);
    }
  });

  it('words the wait after a throttle', () => {
    for (const c of FORMS.wait) {
      expect(waitSentenceFr(c.seconds)).toBe(c.text);
    }
  });

  it('words the API’s errors', () => {
    const service = TestBed.inject(ErrorMessageService);
    for (const c of FORMS.api) {
      expect(service.messageFor(new HttpErrorResponse({ status: c.status, error: c.body }))).toBe(
        c.message,
      );
    }
  });
});
