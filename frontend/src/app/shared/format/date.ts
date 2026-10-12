/**
 * Dates, in French, from the ISO instants the API returns.
 *
 * <p>`Intl.DateTimeFormat` rather than Angular's `DatePipe`. The pipe needs locale data
 * registered for whatever `LOCALE_ID` resolves to, and its formats are Angular's own
 * patterns; `Intl` is in the platform, needs nothing registered, and renders the formats a
 * French reader expects — "3 mars 2026 à 14:05" — without a pattern string to maintain.
 *
 * <p>Every timestamp in the API is UTC. These render in the viewer's own zone, which is the
 * only answer that makes sense on a sessions list: "created at 02:14" has to mean 02:14
 * where the person was, or they cannot recognise their own sign-in.
 */

const DATE_ONLY = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
});

const DATE_AND_TIME = new Intl.DateTimeFormat('fr-FR', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** "3 mars 2026", or `null` for a missing or unparseable value. */
export function formatDateFr(iso: string | null | undefined): string | null {
  const date = parse(iso);
  return date ? DATE_ONLY.format(date) : null;
}

/** "3 mars 2026, 14:05". */
export function formatDateTimeFr(iso: string | null | undefined): string | null {
  const date = parse(iso);
  return date ? DATE_AND_TIME.format(date) : null;
}

/**
 * Returns `null` rather than rendering "Invalid Date".
 *
 * <p>Every date field on a generated response model is an optional string, because the
 * backend's output schemas carry no `required` markers. So the absent case is not
 * hypothetical, and a screen showing "Invalid Date" where a timestamp should be is a bug
 * the user reports instead of a field the screen simply omits.
 */
function parse(iso: string | null | undefined): Date | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}
