import { Injectable } from '@angular/core';

import { ErrorCode } from '../api';
import { asApiError, isTransportFailure } from './api-error';
import { ERROR_MESSAGES_FR, fieldLabelFr } from './error-messages.fr';

/** A failure, in words a user can act on. */
export interface UserFacingError {
  /** The sentence to show. Always French, always from the table — never the API's text. */
  readonly message: string;
  /** Present when the API named one, for code that wants to branch. */
  readonly code: ErrorCode | null;
  /** Field path to French label, for marking up a form. Empty unless validation failed. */
  readonly fieldErrors: ReadonlyMap<string, string>;
  /** Seconds to wait, when the API said so. Drives a countdown or a disabled button. */
  readonly retryAfterSeconds: number | null;
  /** Correlation id for a 500. Worth showing: it is what support can search for. */
  readonly incidentId: string | null;
}

/**
 * Turns anything thrown by an HTTP call into something a user can read.
 *
 * <p>One rule, and it is the reason this class exists: <b>the API's `detail` and `message`
 * members are never shown.</b> They are English developer text, they are not translated,
 * and the API documentation says their wording is not a contract. Everything a user sees
 * comes from {@link ERROR_MESSAGES_FR}, keyed on the stable `code`.
 */
@Injectable({ providedIn: 'root' })
export class ErrorMessageService {
  /** Kept as the single fallback, so there is one place to change the wording. */
  private static readonly UNKNOWN =
    'Une erreur inattendue s’est produite. Réessayez dans un instant.';

  private static readonly OFFLINE =
    'Impossible de joindre AutoX. Vérifiez votre connexion internet.';

  translate(error: unknown): UserFacingError {
    if (isTransportFailure(error)) {
      // Status 0: the request never arrived. Telling the user to retry a server that was
      // never reached is worse than useless — their connection is the thing to check.
      return {
        message: ErrorMessageService.OFFLINE,
        code: null,
        fieldErrors: new Map(),
        retryAfterSeconds: null,
        incidentId: null,
      };
    }

    const problem = asApiError(error);
    if (!problem?.code) {
      // Not our contract: an HTML error page from a proxy, or a bug in the client. The
      // generic sentence is the honest answer — we do not know what happened.
      return {
        message: ErrorMessageService.UNKNOWN,
        code: null,
        fieldErrors: new Map(),
        retryAfterSeconds: null,
        incidentId: null,
      };
    }

    const retryAfterSeconds =
      typeof problem.retryAfterSeconds === 'number' ? problem.retryAfterSeconds : null;

    return {
      message: this.sentenceFor(problem.code, retryAfterSeconds),
      code: problem.code,
      fieldErrors: fieldErrorsOf(problem),
      retryAfterSeconds,
      incidentId: typeof problem.incidentId === 'string' ? problem.incidentId : null,
    };
  }

  /** Just the sentence, for a caller that only wants to show a toast. */
  messageFor(error: unknown): string {
    return this.translate(error).message;
  }

  private sentenceFor(code: ErrorCode, retryAfterSeconds: number | null): string {
    const base = ERROR_MESSAGES_FR[code] ?? ErrorMessageService.UNKNOWN;
    if (retryAfterSeconds === null) {
      return base;
    }
    // The throttling messages are written without a duration so the wait can be added
    // here, where the actual number is known.
    return `${base} ${waitSentenceFr(retryAfterSeconds)}`;
  }
}

/**
 * Field path to French label for each rejected field.
 *
 * <p>The label only — see {@link fieldLabelFr} for why there is no per-constraint French
 * text to give. A form shows these as "this field is invalid" markers and relies on its own
 * client-side rules to say what a valid value looks like.
 */
function fieldErrorsOf(problem: { errors?: { field?: string }[] }): ReadonlyMap<string, string> {
  const result = new Map<string, string>();
  for (const violation of problem.errors ?? []) {
    if (violation.field) {
      result.set(violation.field, fieldLabelFr(violation.field));
    }
  }
  return result;
}

/**
 * "Réessayez dans 2 minutes." — rounded up to whole units, because telling somebody to
 * wait 91 seconds is not how anyone speaks.
 */
export function waitSentenceFr(seconds: number): string {
  if (seconds <= 1) {
    return 'Réessayez dans un instant.';
  }
  if (seconds < 60) {
    return `Réessayez dans ${Math.ceil(seconds)} secondes.`;
  }
  const minutes = Math.ceil(seconds / 60);
  return minutes === 1 ? 'Réessayez dans une minute.' : `Réessayez dans ${minutes} minutes.`;
}
