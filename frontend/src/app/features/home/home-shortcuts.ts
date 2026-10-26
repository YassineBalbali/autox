import { BodyType } from '../../core/api';
import { SilhouetteName } from '../../shared/ui/body-silhouettes.data';
import { IconName } from '../../shared/ui/icons.data';
import { EMPTY_QUERY, SearchQuery } from '../search/search-query';

/** A tile on the home page: a label, and the search it opens. */
export interface Shortcut {
  readonly id: string;
  readonly label: string;
  readonly query: SearchQuery;
}

export interface CategoryShortcut extends Shortcut {
  /** The car drawn on the tile. */
  readonly silhouette: SilhouetteName;
}

const search = (patch: Partial<SearchQuery>): SearchQuery => ({ ...EMPTY_QUERY, ...patch });

/**
 * Price bands, in whole euros.
 *
 * <p>Adjacent bands share their boundary — a car at exactly 10 000 € is in both "5 000 –
 * 10 000" and "10 000 – 15 000" — because the search's price bounds are inclusive at both
 * ends, and the alternative, a band ending at 9 999 €, would put an odd number in every
 * link. Asking prices are rarely that round; the overlap costs nothing a visitor notices.
 */
export const BUDGETS: readonly Shortcut[] = [
  { id: 'under-5k', label: 'Moins de 5 000 €', query: search({ priceMax: 5_000 }) },
  { id: '5k-10k', label: '5 000 – 10 000 €', query: search({ priceMin: 5_000, priceMax: 10_000 }) },
  { id: '10k-15k', label: '10 000 – 15 000 €', query: search({ priceMin: 10_000, priceMax: 15_000 }) },
  { id: '15k-20k', label: '15 000 – 20 000 €', query: search({ priceMin: 15_000, priceMax: 20_000 }) },
  { id: 'over-20k', label: 'Plus de 20 000 €', query: search({ priceMin: 20_000 }) },
];

/**
 * Kinds of car, by shape: each tile is an ordinary search on the sidebar's body-type
 * filter, so a tile and the sidebar can never disagree about what "break" means, and each
 * shows the car's silhouette.
 *
 * <p>The six bodies most cars on sale are. "Citadine" is the hatchback, which the sidebar
 * labels "Citadine / compacte"; "Utilitaire" is the van. Coupés, cabriolets and pick-ups
 * are in the sidebar. Energy and gearbox are too: they are not shapes, and a row of car
 * drawings with a lightning bolt in it reads as one tile out of place.
 */
export const CATEGORIES: readonly CategoryShortcut[] = [
  { id: 'citadine', label: 'Citadine', silhouette: 'citadine', query: search({ body: [BodyType.HATCHBACK] }) },
  { id: 'berline', label: 'Berline', silhouette: 'berline', query: search({ body: [BodyType.SEDAN] }) },
  { id: 'break', label: 'Break', silhouette: 'break', query: search({ body: [BodyType.ESTATE] }) },
  { id: 'suv', label: 'SUV', silhouette: 'suv', query: search({ body: [BodyType.SUV] }) },
  { id: 'monospace', label: 'Monospace', silhouette: 'monospace', query: search({ body: [BodyType.MPV] }) },
  { id: 'utilitaire', label: 'Utilitaire', silhouette: 'utilitaire', query: search({ body: [BodyType.VAN] }) },
];

/** The budgets offered by the hero's "Prix max" field, in whole euros. */
export const PRICE_MAX_OPTIONS: readonly number[] = [
  5_000, 7_500, 10_000, 12_500, 15_000, 20_000, 25_000, 30_000, 40_000, 50_000,
];

export interface TrustPoint {
  readonly icon: IconName;
  readonly title: string;
  readonly body: string;
}

/**
 * Why a visitor might trust what they see. Each is something the site actually does —
 * nothing here may promise more than the code delivers.
 */
export const TRUST_POINTS: readonly TrustPoint[] = [
  {
    icon: 'shield-check',
    title: 'Annonces modérées',
    body: 'Toute annonce publiée entre dans une file de modération : notre équipe la relit et retire celles qui ne respectent pas les règles.',
  },
  {
    icon: 'image',
    title: 'Photos contrôlées',
    body: 'Format et taille vérifiés à l’envoi, puis chaque photo est revue avec l’annonce qu’elle illustre.',
  },
  {
    icon: 'users',
    title: 'Particuliers et professionnels',
    body: 'Les annonces des professionnels sont signalées comme telles, pour savoir d’emblée à qui vous achetez.',
  },
];
