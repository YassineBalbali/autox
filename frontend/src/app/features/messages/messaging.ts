import { ErrorCode, MessageReportReason } from '../../core/api';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService, waitSentenceFr } from '../../core/errors/error-message.service';

/** The longest message the API accepts. */
export const MESSAGE_MAX_LENGTH = 2000;

/** The reasons a recipient can give, in the order offered, with the words a user reads. */
export const REPORT_REASONS: readonly { reason: MessageReportReason; label: string }[] = [
  { reason: MessageReportReason.SCAM, label: 'Arnaque ou demande de paiement suspecte' },
  { reason: MessageReportReason.ABUSIVE, label: 'Propos injurieux ou menaçants' },
  { reason: MessageReportReason.SPAM, label: 'Publicité ou message envoyé en masse' },
  { reason: MessageReportReason.OTHER, label: 'Autre raison' },
];

const TIME = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' });
const DAY_MONTH = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short' });
const DAY_MONTH_YEAR = new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
const FULL = new Intl.DateTimeFormat('fr-FR', { dateStyle: 'full', timeStyle: 'short' });

/**
 * When a message was sent, as an inbox writes it: "14:32" today, "hier", "12 oct." this
 * year, "12 oct. 2025" before. Empty for a missing or unparseable value.
 */
export function sentAtShort(iso: string | undefined, now = new Date()): string {
  const date = iso ? new Date(iso) : null;
  if (!date || Number.isNaN(date.getTime())) {
    return '';
  }
  const days = dayNumber(now) - dayNumber(date);
  if (days === 0) {
    return TIME.format(date);
  }
  if (days === 1) {
    return 'hier';
  }
  return date.getFullYear() === now.getFullYear() ? DAY_MONTH.format(date) : DAY_MONTH_YEAR.format(date);
}

/** "mardi 21 octobre 2026 à 14:32", for a tooltip and a screen reader. */
export function sentAtFull(iso: string | undefined): string {
  const date = iso ? new Date(iso) : null;
  return date && !Number.isNaN(date.getTime()) ? FULL.format(date) : '';
}

/** Days since the epoch in the viewer's own zone, so "today" ends at their midnight. */
function dayNumber(date: Date): number {
  return Math.floor((date.getTime() - date.getTimezoneOffset() * 60_000) / 86_400_000);
}

/**
 * Why a message could not be sent, in words that fit sending a message. The general table
 * says "Trop de requêtes" for a rate limit, which is the server's view of it; here it is the
 * writer's.
 */
export function sendFailure(error: unknown, errors: ErrorMessageService): string {
  const problem = asApiError(error);
  switch (problem?.code) {
    case ErrorCode.RATE_LIMITED: {
      const wait = typeof problem.retryAfterSeconds === 'number' ? ` ${waitSentenceFr(problem.retryAfterSeconds)}` : '';
      return `Vous avez envoyé beaucoup de messages en peu de temps.${wait}`;
    }
    case ErrorCode.CONFLICT:
      return 'Cette conversation est fermée : l’annonce a été retirée, ou l’un des comptes n’est plus actif.';
    case ErrorCode.NOT_FOUND:
      return 'Cette annonce n’est plus en ligne : il n’est plus possible d’écrire au vendeur.';
    default:
      return errors.translate(error).message;
  }
}
