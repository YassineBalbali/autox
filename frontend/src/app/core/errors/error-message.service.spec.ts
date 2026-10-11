import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';

import { ErrorCode } from '../api';
import { ErrorMessageService, waitSentenceFr } from './error-message.service';
import { ERROR_MESSAGES_FR } from './error-messages.fr';

describe('ErrorMessageService', () => {
  let service: ErrorMessageService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [ErrorMessageService] });
    service = TestBed.inject(ErrorMessageService);
  });

  /** An error response shaped like the API's, for the given code. */
  function problem(code: ErrorCode, extra: Record<string, unknown> = {}, status = 400) {
    return new HttpErrorResponse({
      status,
      statusText: 'Error',
      url: 'http://api.test/api/v1/me',
      error: {
        type: 'about:blank',
        title: 'Error',
        status,
        detail: 'English developer text that must never reach a user.',
        instance: '/api/v1/me',
        code,
        timestamp: '2026-10-11T12:00:00Z',
        ...extra,
      },
    });
  }

  it('every error code has French text', () => {
    // The real guard is the compiler: ERROR_MESSAGES_FR is Record<ErrorCode, string>, so a
    // code added to the API breaks the build until someone writes the French. This asserts
    // the other half — that none of the entries is an empty string or left in English.
    const codes = Object.values(ErrorCode);
    expect(codes.length).toBeGreaterThan(20);

    for (const code of codes) {
      const message = ERROR_MESSAGES_FR[code];
      expect(message, `${code} has no message`).toBeTruthy();
      expect(message.length, `${code} message is too short to be a sentence`).toBeGreaterThan(10);
      // A crude but effective French check: every message ends in a full stop and none of
      // them contains the English words that would betray a copied `detail`.
      expect(message, `${code} should read as a sentence`).toMatch(/[.!?]$/);
      expect(message.toLowerCase()).not.toMatch(/\b(must|invalid|failed|request|token)\b/);
    }
  });

  it('never shows the API detail text', () => {
    const translated = service.translate(problem(ErrorCode.INVALID_CREDENTIALS));

    expect(translated.message).toBe(ERROR_MESSAGES_FR[ErrorCode.INVALID_CREDENTIALS]);
    expect(translated.message).not.toContain('English developer text');
    expect(translated.code).toBe(ErrorCode.INVALID_CREDENTIALS);
  });

  it('maps validation failures to French field labels', () => {
    const translated = service.translate(
      problem(ErrorCode.VALIDATION_FAILED, {
        errors: [
          { field: 'email', message: 'must be a well-formed email address' },
          { field: 'professional.vatNumber', message: 'must not be blank' },
        ],
      }),
    );

    expect(translated.message).toBe(ERROR_MESSAGES_FR[ErrorCode.VALIDATION_FAILED]);
    expect([...translated.fieldErrors.entries()]).toEqual([
      ['email', 'Adresse e-mail'],
      ['professional.vatNumber', 'Numéro de TVA'],
    ]);
    // The English constraint text is dropped entirely, not translated.
    expect(JSON.stringify([...translated.fieldErrors])).not.toContain('well-formed');
  });

  it('falls back to the raw field name for a field it does not know', () => {
    // Visible and ugly on purpose: a missing label should be noticed, not silently hidden.
    const translated = service.translate(
      problem(ErrorCode.VALIDATION_FAILED, { errors: [{ field: 'somethingNew' }] }),
    );
    expect(translated.fieldErrors.get('somethingNew')).toBe('somethingNew');
  });

  it('appends the wait to a throttled error', () => {
    const translated = service.translate(
      problem(ErrorCode.RATE_LIMITED, { retryAfterSeconds: 61 }, 429),
    );

    expect(translated.retryAfterSeconds).toBe(61);
    expect(translated.message).toBe(
      `${ERROR_MESSAGES_FR[ErrorCode.RATE_LIMITED]} Réessayez dans 2 minutes.`,
    );
  });

  it('surfaces the incident id from a server fault', () => {
    const translated = service.translate(
      problem(ErrorCode.INTERNAL_ERROR, { incidentId: 'abc-123' }, 500),
    );
    expect(translated.incidentId).toBe('abc-123');
  });

  it('tells the user to check their connection when the request never arrived', () => {
    // Status 0 is offline, DNS failure, or a CORS refusal. "Try again" is the wrong advice.
    const translated = service.translate(
      new HttpErrorResponse({ status: 0, statusText: 'Unknown Error', error: null }),
    );

    expect(translated.message).toContain('connexion internet');
    expect(translated.code).toBeNull();
  });

  it('falls back to a generic message for anything that is not the API contract', () => {
    const cases: unknown[] = [
      new HttpErrorResponse({ status: 502, statusText: 'Bad Gateway', error: '<html>502</html>' }),
      new HttpErrorResponse({ status: 500, statusText: 'Error', error: { message: 'nope' } }),
      new Error('a bug in the client'),
      undefined,
    ];

    for (const error of cases) {
      const translated = service.translate(error);
      expect(translated.message).toBe(ERROR_MESSAGES_FR[ErrorCode.INTERNAL_ERROR]);
      expect(translated.code).toBeNull();
    }
  });

  it('ignores a code from a newer backend than this bundle knows', () => {
    // Deployment skew: the API ships ahead of the web app and sends a code this build has
    // never heard of. Looking it up would render `undefined`.
    const unknown = new HttpErrorResponse({
      status: 409,
      statusText: 'Conflict',
      error: { code: 'SOME_FUTURE_CODE', detail: 'English text' },
    });

    const translated = service.translate(unknown);
    expect(translated.message).toBe(ERROR_MESSAGES_FR[ErrorCode.INTERNAL_ERROR]);
    expect(translated.message).not.toContain('undefined');
  });
});

describe('waitSentenceFr', () => {
  it('reads as French rather than as arithmetic', () => {
    expect(waitSentenceFr(0)).toBe('Réessayez dans un instant.');
    expect(waitSentenceFr(1)).toBe('Réessayez dans un instant.');
    expect(waitSentenceFr(30)).toBe('Réessayez dans 30 secondes.');
    expect(waitSentenceFr(60)).toBe('Réessayez dans une minute.');
    // 91 seconds rounds up to 2 minutes: nobody says "wait 91 seconds".
    expect(waitSentenceFr(91)).toBe('Réessayez dans 2 minutes.');
    expect(waitSentenceFr(900)).toBe('Réessayez dans 15 minutes.');
  });
});
