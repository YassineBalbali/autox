import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { ErrorCode } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { sendFailure, sentAtShort } from './messaging';
import { dayLabel } from './message-thread';

const problem = (code: ErrorCode, status: number, extra: object = {}) =>
  new HttpErrorResponse({ status, error: { code, ...extra } });

describe('messaging helpers', () => {
  describe('when a message was sent, as an inbox writes it', () => {
    const now = new Date(2026, 9, 21, 15, 0);

    it('gives the time for today, "hier" for yesterday, the day before that', () => {
      expect(sentAtShort(new Date(2026, 9, 21, 9, 5).toISOString(), now)).toBe('09:05');
      expect(sentAtShort(new Date(2026, 9, 20, 23, 59).toISOString(), now)).toBe('hier');
      expect(sentAtShort(new Date(2026, 9, 12, 10, 0).toISOString(), now)).toBe('12 oct.');
    });

    it('adds the year for an earlier year, and gives nothing for no date', () => {
      expect(sentAtShort(new Date(2025, 9, 12).toISOString(), now)).toBe('12 oct. 2025');
      expect(sentAtShort(undefined, now)).toBe('');
      expect(sentAtShort('pas une date', now)).toBe('');
    });

    it('names the days of a conversation', () => {
      expect(dayLabel(new Date(2026, 9, 21, 8, 0), now)).toBe('Aujourd’hui');
      expect(dayLabel(new Date(2026, 9, 20, 8, 0), now)).toBe('Hier');
      expect(dayLabel(new Date(2026, 9, 14, 8, 0), now)).toBe('mercredi 14 octobre 2026');
    });
  });

  describe('why a message could not be sent', () => {
    const errors = () => TestBed.inject(ErrorMessageService);

    it('says the limit in the writer’s terms, with how long to wait', () => {
      const sentence = sendFailure(problem(ErrorCode.RATE_LIMITED, 429, { retryAfterSeconds: 300 }), errors());

      expect(sentence).toContain('Vous avez envoyé beaucoup de messages en peu de temps.');
      expect(sentence).toContain('5 minutes');
      expect(sentence).not.toContain('Trop de requêtes');
    });

    it('explains a closed conversation, and a listing that is gone', () => {
      expect(sendFailure(problem(ErrorCode.CONFLICT, 409), errors())).toContain('Cette conversation est fermée');
      expect(sendFailure(problem(ErrorCode.NOT_FOUND, 404), errors())).toContain('n’est plus en ligne');
    });

    it('falls back to the general table for anything else', () => {
      expect(sendFailure(new HttpErrorResponse({ status: 0 }), errors())).toContain('Vérifiez votre connexion');
    });
  });
});
