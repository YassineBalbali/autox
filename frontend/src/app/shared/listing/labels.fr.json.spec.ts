import {
  BodyType,
  CritAir,
  DamageFilter,
  DamageType,
  EmissionClass,
  ErrorCode,
  ExteriorColor,
  FeatureCategory,
  FuelType,
  Gearbox,
  LeasingType,
  ListingReportReason,
  MessageReportReason,
  PriceLevel,
  SearchSort,
  UserRole,
  VehicleCondition,
} from '../../core/api';
import LABELS from '../../../../labels.fr.json';

/**
 * `frontend/labels.fr.json` against the generated enums.
 *
 * <p>The compiler already refuses a JSON that lacks a value (each map is typed as a `Record`
 * over its enum). What it cannot refuse is a value the API no longer has, or a section added to
 * the JSON for an enum nobody checks — both of which the Android app, generating from the same
 * file, would turn into a compile error. Hence: exactly the enum's values, in each section.
 */
const ENUMS: Record<keyof typeof LABELS.enums, Record<string, string>> = {
  FuelType,
  Gearbox,
  BodyType,
  VehicleCondition,
  DamageType,
  DamageFilter,
  ExteriorColor,
  EmissionClass,
  FeatureCategory,
  UserRole,
  PriceLevel,
  LeasingType,
  CritAir,
  ErrorCode,
  ListingReportReason,
  MessageReportReason,
};

describe('labels.fr.json', () => {
  for (const [name, values] of Object.entries(ENUMS)) {
    it(`words every value of ${name}, and nothing else`, () => {
      const labels: Record<string, string> = LABELS.enums[name as keyof typeof LABELS.enums];

      expect(Object.keys(labels).sort()).toEqual(Object.values(values).sort());
      for (const word of Object.values(labels)) {
        expect(word.trim()).not.toBe('');
      }
    });
  }

  it('has a section for every enum it is checked against, and no other', () => {
    expect(Object.keys(LABELS.enums).sort()).toEqual(Object.keys(ENUMS).sort());
  });

  it('offers real sort orders, each once', () => {
    const sorts = new Set<string>(Object.values(SearchSort));
    for (const menu of [LABELS.sortOptions.sale, LABELS.sortOptions.leasing]) {
      const values = menu.map((option) => option.value);
      expect(values.every((value) => sorts.has(value))).toBe(true);
      expect(new Set(values).size).toBe(values.length);
    }
  });

  it('offers Crit’Air bounds 0 to 5', () => {
    expect(LABELS.critAirMaxOptions.map((option) => option.value)).toEqual([0, 1, 2, 3, 4, 5]);
  });
});
