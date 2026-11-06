import { formatIntegerFr } from './number';

/**
 * The French rule: one and zero take the singular, two and more the plural. "0 annonce",
 * "1 annonce", "2 annonces".
 */
export function pluralFr(count: number, singular: string, plural: string): string {
  return Math.abs(count) >= 2 ? plural : singular;
}

/** "1 recherche enregistrée active", "3 recherches enregistrées actives", the figure grouped. */
export function countFr(count: number, singular: string, plural: string): string {
  return `${formatIntegerFr(count)} ${pluralFr(count, singular, plural)}`;
}
