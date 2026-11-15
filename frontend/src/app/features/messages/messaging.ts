import { ErrorCode, LastMessage, MessageReportReason } from '../../core/api';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService, waitSentenceFr } from '../../core/errors/error-message.service';
import { fill } from '../../shared/forms/validation';
import LABELS from '../../../../labels.fr.json';

// The words below are in frontend/labels.fr.json (`messaging`, and the report reasons under
// `enums`), shared with the Android app; the rules are held to frontend/fixtures/messaging.json.
const WORDS = LABELS.messaging;

/** The longest message the API accepts. */
export const MESSAGE_MAX_LENGTH = 2000;

/** The longest note the API accepts with a report, on a message and on a listing. */
export const MESSAGE_REPORT_NOTE_MAX_LENGTH = 500;
export const LISTING_REPORT_NOTE_MAX_LENGTH = 1000;

/** The reasons a recipient can give, in the order offered, with the words a user reads. */
export const REPORT_REASONS: readonly { reason: MessageReportReason; label: string }[] = Object.entries(
  LABELS.enums.MessageReportReason,
).map(([reason, label]) => ({ reason: reason as MessageReportReason, label }));

/** Whether a message can be sent: something besides spaces, and no longer than the API takes. */
export function canSendMessage(body: string): boolean {
  return body.trim().length > 0 && body.length <= MESSAGE_MAX_LENGTH;
}

/** Whether a report can be sent: a reason, and a note when the reason is "other". */
export function canSubmitReport(reason: string | null, note: string): boolean {
  return !!reason && (reason !== 'OTHER' || note.trim().length > 0);
}

/** The note field's placeholder, which says when the note is required. */
export function reportNotePlaceholder(reason: string | null): string {
  return reason === 'OTHER' ? WORDS.reportNoteRequired : WORDS.reportNoteOptional;
}

/** The first line of a message to a listing's seller, or to a dealer from their page. */
export function contactGreeting(listingTitle: string | undefined | null): string {
  return listingTitle === null ? WORDS.greetingDealer : fill(WORDS.greetingListing, { title: `${listingTitle}` });
}

/** The inbox's last line: "Vous : …", "Message masqué par la modération", or the other side's words. */
export function inboxPreview(last: LastMessage | undefined | null): string {
  if (!last) {
    return '';
  }
  const text = last.hidden ? WORDS.previewHidden : last.erased ? WORDS.previewErased : (last.preview ?? '');
  return last.sentByMe ? fill(WORDS.previewMine, { text }) : text;
}

/** "1 message non lu", "3 messages non lus", for a screen reader beside the badge. */
export function unreadLabel(count: number): string {
  return count === 1 ? WORDS.unreadOne : fill(WORDS.unreadMany, { count: String(count) });
}

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
    return WORDS.yesterdayShort;
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
      return `${WORDS.sendRateLimited}${wait}`;
    }
    case ErrorCode.CONFLICT:
      return WORDS.sendClosed;
    case ErrorCode.NOT_FOUND:
      return WORDS.sendListingGone;
    default:
      return errors.translate(error).message;
  }
}
