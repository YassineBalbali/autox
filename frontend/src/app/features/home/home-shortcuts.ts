import { BodyType, FuelType, Gearbox } from '../../core/api';
import { IconName } from '../../shared/ui/icons.data';
import { EMPTY_QUERY, SearchQuery } from '../search/search-query';

/** A tile on the home page: a label, and the search it opens. */
export interface Shortcut {
  readonly id: string;
  readonly label: string;
  readonly query: SearchQuery;
}

export interface CategoryShortcut extends Shortcut {
  readonly icon: IconName;
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
 * Kinds of car, each an ordinary search on the filters the results page already has —
 * so a tile and the sidebar can never disagree about what "hybride" means.
 *
 * <p>"Citadine" is the hatchback body type, which the sidebar already labels "Citadine /
 * compacte". "Familiale" is a break or a monospace: the two bodies bought for space.
 */
export const CATEGORIES: readonly CategoryShortcut[] = [
  { id: 'electric', label: 'Électrique', icon: 'zap', query: search({ fuel: [FuelType.ELECTRIC] }) },
  {
    id: 'hybrid',
    label: 'Hybride',
    icon: 'leaf',
    query: search({ fuel: [FuelType.HYBRID_PETROL, FuelType.HYBRID_DIESEL, FuelType.PLUGIN_HYBRID] }),
  },
  { id: 'automatic', label: 'Automatique', icon: 'cog', query: search({ gearbox: [Gearbox.AUTOMATIC] }) },
  { id: 'city', label: 'Citadine', icon: 'building-2', query: search({ body: [BodyType.HATCHBACK] }) },
  { id: 'suv', label: 'SUV', icon: 'mountain', query: search({ body: [BodyType.SUV] }) },
  { id: 'family', label: 'Familiale', icon: 'users', query: search({ body: [BodyType.ESTATE, BodyType.MPV] }) },
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
