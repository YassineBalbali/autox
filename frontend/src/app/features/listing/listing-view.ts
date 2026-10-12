import { Feature, FeatureCategory, PublicListingDetail } from '../../core/api';
import { formatIntegerFr, NBSP } from '../../shared/format/number';
import {
  BODY_LABELS,
  COLOR_LABELS,
  CONDITION_LABELS,
  EMISSION_LABELS,
  FEATURE_CATEGORY_LABELS,
  FUEL_LABELS,
  GEARBOX_LABELS,
  labelOf,
} from '../../shared/listing/labels.fr';
import { GalleryImage } from '../../shared/listing/photo-gallery';

/**
 * A listing, turned into what its page displays. Pure functions, no component.
 *
 * <p>Every field of the generated detail model is optional, and a seller fills in what
 * they choose to. So each of these is written around absence: a row that has no value is
 * left out, never shown as a dash, an "undefined" or a zero that means "not stated".
 */

export interface SpecRow {
  readonly label: string;
  readonly value: string;
}

/**
 * The specification table, in a fixed order.
 *
 * <p>The order is the one a buyer checks in: how old, how far, what it runs on, then the
 * body, then the paperwork. The same on every listing, so two tabs side by side can be
 * compared line for line.
 *
 * <p>There is no VIN row. The public API does not send one — it identifies the vehicle
 * and, through it, the owner, and published VINs are what plate-cloning works from — so
 * there is nothing here to show, by the backend's design rather than by omission.
 */
export function specificationRows(listing: PublicListingDetail): SpecRow[] {
  const rows: SpecRow[] = [];
  const add = (label: string, value: string | null | undefined) => {
    if (value) {
      rows.push({ label, value });
    }
  };
  const vehicle = listing.vehicle ?? {};

  add('Année', vehicle.firstRegistrationYear ? String(vehicle.firstRegistrationYear) : null);
  add('Mise en circulation', firstRegistration(vehicle.firstRegistrationMonth, vehicle.firstRegistrationYear));
  // Zero is a mileage — a new car — so the test is for a number, not for truthiness.
  add('Kilométrage', typeof listing.mileageKm === 'number' ? `${formatIntegerFr(listing.mileageKm)}${NBSP}km` : null);
  add('Carburant', labelOf(FUEL_LABELS, vehicle.fuelType));
  add('Boîte de vitesses', labelOf(GEARBOX_LABELS, vehicle.gearbox));
  add('Puissance', power(vehicle.powerHp, vehicle.powerKw));
  add('Carrosserie', labelOf(BODY_LABELS, vehicle.bodyType));
  add('Portes', vehicle.doors ? String(vehicle.doors) : null);
  add('Places', vehicle.seats ? String(vehicle.seats) : null);
  add('Couleur', colour(listing));
  add('État', labelOf(CONDITION_LABELS, listing.condition));
  // Zero grams is a real figure for an electric car, and worth stating.
  add(
    'Émissions de CO₂',
    typeof listing.co2EmissionsGKm === 'number' ? `${listing.co2EmissionsGKm}${NBSP}g/km` : null,
  );
  add('Norme antipollution', labelOf(EMISSION_LABELS, listing.emissionClass));

  return rows;
}

/** "mars 2021" — only when the month is known; the year alone is already its own row. */
function firstRegistration(month: number | undefined, year: number | undefined): string | null {
  if (!month || !year || month < 1 || month > 12) {
    return null;
  }
  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(
    new Date(Date.UTC(year, month - 1, 15)),
  );
}

/** "130 ch (96 kW)" — horsepower is what a French buyer reads; kilowatts are on the papers. */
function power(hp: number | undefined, kw: number | undefined): string | null {
  if (hp && kw) {
    return `${hp}${NBSP}ch (${kw}${NBSP}kW)`;
  }
  if (hp) {
    return `${hp}${NBSP}ch`;
  }
  return kw ? `${kw}${NBSP}kW` : null;
}

/** "Gris métallisé", "Bleu (Bleu Vertigo)" — the family, then whatever the seller added. */
function colour(listing: PublicListingDetail): string | null {
  const family = labelOf(COLOR_LABELS, listing.exteriorColor);
  const named = listing.colorText?.trim();
  if (!family && !named) {
    return null;
  }
  const base = family ? `${family}${listing.metallicPaint ? ' métallisé' : ''}` : '';
  if (!named) {
    return base;
  }
  return base ? `${base} (${named})` : named;
}

export interface FeatureGroup {
  readonly category: FeatureCategory;
  readonly title: string;
  readonly items: readonly string[];
}

/** The locales to look for a label under, most specific first. */
const LABEL_LOCALES = ['fr-FR', 'fr'];

/**
 * A listing's equipment, grouped by category and labelled in French.
 *
 * <p>The listing carries codes; the reference endpoint carries, for each code, its
 * category, its position and its label per locale. The words therefore come from the API
 * here — unlike the enums — because equipment is a catalogue that grows without a
 * release, and its translations live with it.
 *
 * <p>A code the reference data does not know, or knows without a French label, is left
 * out. There is nothing truthful to print for it: the raw code ("ADAPTIVE_CRUISE") is
 * not French, and the English label is not either.
 */
export function groupFeatures(
  codes: readonly string[] | undefined,
  reference: readonly Feature[],
): FeatureGroup[] {
  const wanted = new Set(codes ?? []);
  const byCategory = new Map<FeatureCategory, { order: number; label: string }[]>();

  for (const feature of reference) {
    if (!feature.code || !wanted.has(feature.code)) {
      continue;
    }
    const label = LABEL_LOCALES.map((locale) => feature.labels?.[locale]).find(Boolean);
    if (!label) {
      continue;
    }
    const category = feature.category ?? FeatureCategory.OTHER;
    const bucket = byCategory.get(category) ?? [];
    bucket.push({ order: feature.displayOrder ?? Number.MAX_SAFE_INTEGER, label });
    byCategory.set(category, bucket);
  }

  // The order of the label table is the order of the page.
  return (Object.keys(FEATURE_CATEGORY_LABELS) as FeatureCategory[])
    .filter((category) => byCategory.has(category))
    .map((category) => ({
      category,
      title: FEATURE_CATEGORY_LABELS[category],
      items: byCategory
        .get(category)!
        .sort((a, b) => a.order - b.order || a.label.localeCompare(b.label, 'fr'))
        .map((entry) => entry.label),
    }));
}

/**
 * The listing's photographs, in the seller's order, as the gallery takes them.
 *
 * <p>Only images that are ready and have something to show. Each size falls back to the
 * next one: a gallery that showed nothing because one variant was missing would be worse
 * than one that showed a slightly soft picture.
 */
export function galleryImages(listing: PublicListingDetail): GalleryImage[] {
  return [...(listing.images ?? [])]
    .sort((a, b) => (a.position ?? 0) - (b.position ?? 0))
    .flatMap((image, index) => {
      const variants = image.variants ?? {};
      const large = variants['FULL']?.url ?? variants['CARD']?.url ?? variants['THUMBNAIL']?.url;
      if (!large) {
        return [];
      }
      const medium = variants['CARD']?.url ?? large;
      const sized = variants['FULL'] ?? variants['CARD'] ?? variants['THUMBNAIL'];
      return [
        {
          id: image.id ?? `image-${index}`,
          thumbnail: variants['THUMBNAIL']?.url ?? medium,
          medium,
          large,
          width: sized?.width ?? null,
          height: sized?.height ?? null,
        },
      ];
    });
}
