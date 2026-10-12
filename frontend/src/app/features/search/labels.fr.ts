import { BodyType, FuelType, Gearbox, SearchSort, VehicleCondition } from '../../core/api';

/**
 * French words for the vehicle enums the search screen shows.
 *
 * <p>The <em>values</em> come from the API — the filter lists are built from
 * `GET /reference/enums`, so a fuel type added on the backend appears in the sidebar
 * without a frontend change. The <em>words</em> are here, which is the agreed split:
 * values server-side, labels in Angular.
 *
 * <p>Each map is a `Record` over the generated enum, so the day the API gains a value the
 * client is regenerated and this file stops compiling until someone writes the French.
 * Between a backend release and that regeneration the value is still shown —
 * {@link labelOf} falls back to the raw name, which is ugly and therefore noticed —
 * rather than silently missing from the filters.
 */
export const FUEL_LABELS: Record<FuelType, string> = {
  [FuelType.PETROL]: 'Essence',
  [FuelType.DIESEL]: 'Diesel',
  [FuelType.HYBRID_PETROL]: 'Hybride essence',
  [FuelType.HYBRID_DIESEL]: 'Hybride diesel',
  [FuelType.PLUGIN_HYBRID]: 'Hybride rechargeable',
  [FuelType.ELECTRIC]: 'Électrique',
  [FuelType.LPG]: 'GPL',
  [FuelType.CNG]: 'GNV',
  [FuelType.HYDROGEN]: 'Hydrogène',
  [FuelType.ETHANOL]: 'Éthanol (E85)',
  [FuelType.OTHER]: 'Autre',
};

export const GEARBOX_LABELS: Record<Gearbox, string> = {
  [Gearbox.MANUAL]: 'Manuelle',
  [Gearbox.AUTOMATIC]: 'Automatique',
  [Gearbox.SEMI_AUTOMATIC]: 'Semi-automatique',
};

export const BODY_LABELS: Record<BodyType, string> = {
  [BodyType.HATCHBACK]: 'Citadine / compacte',
  [BodyType.SEDAN]: 'Berline',
  [BodyType.ESTATE]: 'Break',
  [BodyType.SUV]: 'SUV / 4x4',
  [BodyType.COUPE]: 'Coupé',
  [BodyType.CONVERTIBLE]: 'Cabriolet',
  [BodyType.MPV]: 'Monospace',
  [BodyType.PICKUP]: 'Pick-up',
  [BodyType.VAN]: 'Utilitaire',
  [BodyType.OTHER]: 'Autre',
};

export const CONDITION_LABELS: Record<VehicleCondition, string> = {
  [VehicleCondition.NEW]: 'Neuf',
  [VehicleCondition.USED]: 'Occasion',
  [VehicleCondition.DAMAGED]: 'Accidenté',
};

/** In the order the sort menu lists them. */
export const SORT_OPTIONS: readonly { value: SearchSort; label: string }[] = [
  { value: SearchSort.NEWEST, label: 'Plus récentes' },
  { value: SearchSort.PRICE_ASC, label: 'Prix croissant' },
  { value: SearchSort.PRICE_DESC, label: 'Prix décroissant' },
  { value: SearchSort.MILEAGE_ASC, label: 'Kilométrage croissant' },
];

/**
 * The label for a value, or the value itself when there is none.
 *
 * <p>Takes a plain string because that is what the API's summaries carry: `fuelType` on a
 * search result is typed `string`, not as the enum.
 */
export function labelOf(labels: Record<string, string>, value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  return labels[value] ?? value;
}
