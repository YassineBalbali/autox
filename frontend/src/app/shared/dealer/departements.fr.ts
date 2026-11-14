import LABELS from '../../../../labels.fr.json';

/**
 * The French départements by code, for the directory's filter: the API gives the codes it has
 * dealers in, this gives their names. Codes as the postal code reads them (DealerRepository):
 * two digits, three overseas, 2A and 2B for Corsica.
 *
 * <p>The names are in frontend/labels.fr.json (`departements`), shared with the Android app.
 */
export const DEPARTEMENTS: Readonly<Record<string, string>> = LABELS.departements;

/** "33 · Gironde", or the code alone for one this list does not know. */
export function departementLabel(code: string | null | undefined): string {
  if (!code) {
    return '';
  }
  const name = DEPARTEMENTS[code];
  return name ? `${code} · ${name}` : code;
}
