/**
 * French number formatting, with a separator that is actually visible.
 *
 * <p>`Intl.NumberFormat('fr-FR')` groups thousands with U+202F, the narrow no-break space,
 * which is typographically correct and which many fonts — the one this application uses
 * among them — draw at close to zero width. "18 990 €" then renders as "18990 €", and a
 * price that has lost its grouping is a price people misread by a factor of ten.
 *
 * <p>So the narrow space is replaced with U+00A0, the ordinary no-break space: still
 * unbreakable, and every font gives it a real width. Every grouped number in the
 * application comes through here for that reason.
 */
const NARROW_NBSP = /\u202f/g;
/** U+00A0. Named, because an invisible character in source is a trap. */
export const NBSP = '\u00a0';

export function visibleSpaces(formatted: string): string {
  return formatted.replace(NARROW_NBSP, NBSP);
}

const GROUPED = new Intl.NumberFormat('fr-FR');

/** "62 400" — an integer with its thousands grouped. */
export function formatIntegerFr(value: number): string {
  return visibleSpaces(GROUPED.format(value));
}
