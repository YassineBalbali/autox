import {
  BodyType,
  DamageFilter,
  DamageType,
  EmissionClass,
  ExteriorColor,
  FeatureCategory,
  FuelType,
  Gearbox,
  PriceLevel,
  SearchSort,
  UserRole,
  VehicleCondition,
} from '../../core/api';
import LABELS from '../../../../labels.fr.json';

/**
 * French words for the vehicle enums the search and listing screens show.
 *
 * <p>The <em>values</em> come from the API — the filter lists are built from
 * `GET /reference/enums`, so a fuel type added on the backend appears in the sidebar
 * without a frontend change. The <em>words</em> are in `frontend/labels.fr.json`, which is the
 * agreed split: values server-side, labels in the clients. That file is shared with the Android
 * app, so the site and the app cannot word the same value differently.
 *
 * <p>Each map is still typed as a `Record` over the generated enum, and the JSON's own type is
 * checked against it: the day the API gains a value the client is regenerated and this file
 * stops compiling until someone writes the French in the JSON. Between a backend release and
 * that regeneration the value is still shown — {@link labelOf} falls back to the raw name, which
 * is ugly and therefore noticed — rather than silently missing from the filters. A value the API
 * no longer has is caught by `labels.fr.json.spec.ts`.
 */
export const FUEL_LABELS: Record<FuelType, string> = LABELS.enums.FuelType;

export const GEARBOX_LABELS: Record<Gearbox, string> = LABELS.enums.Gearbox;

export const BODY_LABELS: Record<BodyType, string> = LABELS.enums.BodyType;

export const CONDITION_LABELS: Record<VehicleCondition, string> = LABELS.enums.VehicleCondition;

export const DAMAGE_TYPE_LABELS: Record<DamageType, string> = LABELS.enums.DamageType;

/** "État du véhicule", in the order the filter offers them: the default first. */
export const DAMAGE_FILTER_LABELS: Record<DamageFilter, string> = LABELS.enums.DamageFilter;

export const COLOR_LABELS: Record<ExteriorColor, string> = LABELS.enums.ExteriorColor;

export const EMISSION_LABELS: Record<EmissionClass, string> = LABELS.enums.EmissionClass;

/**
 * Equipment categories, and — by the order of the keys — the order they are listed in.
 *
 * <p>Safety first: on a used car it is what a buyer checks before anything else, and it
 * is the category where a missing item matters.
 */
export const FEATURE_CATEGORY_LABELS: Record<FeatureCategory, string> =
  LABELS.enums.FeatureCategory;

export const SELLER_ROLE_LABELS: Record<UserRole, string> = LABELS.enums.UserRole;

/** The price indicator's verdicts. The rule is `MarketPrice`, on the backend. */
export const PRICE_LEVEL_LABELS: Record<PriceLevel, string> = LABELS.enums.PriceLevel;

/**
 * A sort menu from the JSON. Its values are plain strings there; `labels.fr.json.spec.ts`
 * checks that each is a `SearchSort`, which is what makes the cast below honest.
 */
function sortOptions(
  options: readonly { value: string; label: string }[],
): readonly { value: SearchSort; label: string }[] {
  return options.map(({ value, label }) => ({ value: value as SearchSort, label }));
}

/** In the order the sort menu lists them. */
export const SORT_OPTIONS: readonly { value: SearchSort; label: string }[] = sortOptions(
  LABELS.sortOptions.sale,
);

/** The same for leasing offers, which are sorted by their terms. */
export const LEASING_SORT_OPTIONS: readonly { value: SearchSort; label: string }[] = sortOptions(
  LABELS.sortOptions.leasing,
);

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
