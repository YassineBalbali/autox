import { describe, expect, it } from 'vitest';

import { BodyType, Feature, FeatureCategory, FuelType, Gearbox, PublicListingDetail } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { galleryImages, groupFeatures, specificationRows } from './listing-view';

const FULL: PublicListingDetail = {
  id: 'l-1',
  title: 'Peugeot 308',
  mileageKm: 62400,
  condition: 'USED',
  exteriorColor: 'GREY',
  metallicPaint: true,
  co2EmissionsGKm: 118,
  emissionClass: 'EURO_6D',
  vehicle: {
    firstRegistrationYear: 2021,
    firstRegistrationMonth: 3,
    fuelType: FuelType.DIESEL,
    gearbox: Gearbox.MANUAL,
    bodyType: BodyType.HATCHBACK,
    powerHp: 130,
    powerKw: 96,
    doors: 5,
    seats: 5,
  },
};

const rows = (listing: PublicListingDetail) =>
  Object.fromEntries(specificationRows(listing).map((row) => [row.label, row.value]));

/**
 * What a listing page displays, derived from the API's detail model.
 *
 * <p>Every field on that model is optional and a seller fills in what they choose to, so
 * most of what is asserted here is behaviour around absence: a missing value is a missing
 * row, never a dash, an "undefined", or a zero that really means "not stated" — while a
 * zero that <em>is</em> the value has to survive.
 */
describe('specificationRows', () => {
  it('lists every fact, in French, in a fixed order', () => {
    expect(specificationRows(FULL)).toEqual([
      { label: 'Année', value: '2021' },
      { label: 'Mise en circulation', value: 'mars 2021' },
      { label: 'Kilométrage', value: `62${NBSP}400${NBSP}km` },
      { label: 'Carburant', value: 'Diesel' },
      { label: 'Boîte de vitesses', value: 'Manuelle' },
      { label: 'Puissance', value: `130${NBSP}ch (96${NBSP}kW)` },
      { label: 'Carrosserie', value: 'Citadine / compacte' },
      { label: 'Portes', value: '5' },
      { label: 'Places', value: '5' },
      { label: 'Couleur', value: 'Gris métallisé' },
      { label: 'État', value: 'Occasion' },
      { label: 'Émissions de CO₂', value: `118${NBSP}g/km` },
      { label: 'Norme antipollution', value: 'Euro 6d' },
    ]);
  });

  it('never shows an enum code', () => {
    const text = JSON.stringify(specificationRows(FULL));
    for (const code of ['DIESEL', 'MANUAL', 'HATCHBACK', 'USED', 'GREY', 'EURO_6D']) {
      expect(text).not.toContain(code);
    }
  });

  it('has no VIN row, because the public API does not send one', () => {
    // Deliberate on the backend: a VIN identifies the vehicle and, through it, the owner,
    // and published VINs are what plate-cloning works from. A field smuggled into the
    // response would have to be asked for here on purpose.
    const labels = specificationRows({ ...FULL, ...({ vin: 'VF3LCYHZPMS000000' } as object) }).map(
      (row) => row.label,
    );
    expect(labels.join(' ')).not.toMatch(/VIN|série/i);
  });

  describe('leaves out what the seller did not state', () => {
    it('shows only the rows it has values for', () => {
      expect(specificationRows({ id: 'bare', title: 'Sans détail' })).toEqual([]);
    });

    it('prints no placeholder for a missing value', () => {
      const text = JSON.stringify(specificationRows({ vehicle: { firstRegistrationYear: 2019 }, mileageKm: 1000 }));

      expect(text).not.toMatch(/undefined|null|NaN|—/);
    });

    it('omits the registration month row when only the year is known', () => {
      const listing = { ...FULL, vehicle: { ...FULL.vehicle, firstRegistrationMonth: undefined } };

      expect(rows(listing)['Année']).toBe('2021');
      expect(rows(listing)['Mise en circulation']).toBeUndefined();
    });

    it('ignores a month that is not a month', () => {
      const listing = { ...FULL, vehicle: { ...FULL.vehicle, firstRegistrationMonth: 13 } };
      expect(rows(listing)['Mise en circulation']).toBeUndefined();
    });
  });

  describe('keeps a zero that is the value', () => {
    it('shows 0 km for a new car', () => {
      // `if (mileage)` would drop it, and a new car would have no mileage row at all.
      expect(rows({ ...FULL, mileageKm: 0 })['Kilométrage']).toBe(`0${NBSP}km`);
    });

    it('shows 0 g/km for an electric car', () => {
      // Zero emissions is the single most relevant figure on an electric listing.
      expect(rows({ ...FULL, co2EmissionsGKm: 0 })['Émissions de CO₂']).toBe(`0${NBSP}g/km`);
    });
  });

  describe('power', () => {
    const withPower = (powerHp?: number, powerKw?: number) =>
      rows({ ...FULL, vehicle: { ...FULL.vehicle, powerHp, powerKw } })['Puissance'];

    it('gives horsepower first, kilowatts beside it', () => {
      expect(withPower(130, 96)).toBe(`130${NBSP}ch (96${NBSP}kW)`);
    });

    it('gives whichever one it has', () => {
      expect(withPower(130)).toBe(`130${NBSP}ch`);
      expect(withPower(undefined, 96)).toBe(`96${NBSP}kW`);
      expect(withPower()).toBeUndefined();
    });
  });

  describe('colour', () => {
    const colour = (patch: Partial<PublicListingDetail>) =>
      rows({ ...FULL, exteriorColor: undefined, metallicPaint: undefined, ...patch })['Couleur'];

    it.each([
      [{ exteriorColor: 'BLUE' }, 'Bleu'],
      [{ exteriorColor: 'BLUE', metallicPaint: true }, 'Bleu métallisé'],
      [{ exteriorColor: 'BLUE', colorText: 'Bleu Vertigo' }, 'Bleu (Bleu Vertigo)'],
      [{ colorText: 'Bleu Vertigo' }, 'Bleu Vertigo'],
      [{ colorText: '   ' }, undefined],
    ])('describes %j as %s', (patch, expected) => {
      expect(colour(patch)).toBe(expected);
    });
  });
});

describe('groupFeatures', () => {
  const feature = (code: string, category: FeatureCategory, order: number, fr: string | null, en = code): Feature => ({
    code,
    category,
    displayOrder: order,
    labels: fr ? { 'fr-FR': fr, en } : { en },
  });

  const REFERENCE: Feature[] = [
    feature('HEATED_SEATS', FeatureCategory.COMFORT, 30, 'Sièges chauffants'),
    feature('AIR_CONDITIONING', FeatureCategory.COMFORT, 10, 'Climatisation'),
    feature('ABS', FeatureCategory.SAFETY, 10, 'ABS'),
    feature('BLUETOOTH', FeatureCategory.MULTIMEDIA, 10, 'Bluetooth'),
    feature('TOW_BAR', FeatureCategory.EXTERIOR, 60, 'Attelage'),
    feature('ENGLISH_ONLY', FeatureCategory.OTHER, 10, null),
  ];

  it('groups a listing’s equipment by category, with French labels', () => {
    expect(groupFeatures(['ABS', 'AIR_CONDITIONING', 'BLUETOOTH'], REFERENCE)).toEqual([
      { category: FeatureCategory.SAFETY, title: 'Sécurité', items: ['ABS'] },
      { category: FeatureCategory.COMFORT, title: 'Confort', items: ['Climatisation'] },
      { category: FeatureCategory.MULTIMEDIA, title: 'Multimédia', items: ['Bluetooth'] },
    ]);
  });

  it('puts safety first, whatever order the codes arrive in', () => {
    // On a used car it is what a buyer checks before anything else.
    const groups = groupFeatures(['BLUETOOTH', 'TOW_BAR', 'ABS'], REFERENCE);
    expect(groups.map((group) => group.title)).toEqual(['Sécurité', 'Multimédia', 'Extérieur']);
  });

  it('orders items within a category as the catalogue does', () => {
    const [comfort] = groupFeatures(['HEATED_SEATS', 'AIR_CONDITIONING'], REFERENCE);
    expect(comfort.items).toEqual(['Climatisation', 'Sièges chauffants']);
  });

  it('shows only what this listing has, not the whole catalogue', () => {
    expect(groupFeatures(['ABS'], REFERENCE).flatMap((group) => group.items)).toEqual(['ABS']);
  });

  it('leaves out a code the catalogue does not know', () => {
    // The raw code is not French and there is nothing truthful to print in its place.
    const items = groupFeatures(['ABS', 'FLUX_CAPACITOR'], REFERENCE).flatMap((group) => group.items);
    expect(items).toEqual(['ABS']);
  });

  it('leaves out an item that has no French label, rather than showing the English', () => {
    expect(groupFeatures(['ENGLISH_ONLY'], REFERENCE)).toEqual([]);
  });

  it('accepts a plain "fr" label when there is no "fr-FR"', () => {
    const reference: Feature[] = [{ code: 'ESP', category: FeatureCategory.SAFETY, labels: { fr: 'ESP' } }];
    expect(groupFeatures(['ESP'], reference)[0].items).toEqual(['ESP']);
  });

  it('gives nothing for a listing with no equipment, or before the catalogue has loaded', () => {
    expect(groupFeatures([], REFERENCE)).toEqual([]);
    expect(groupFeatures(undefined, REFERENCE)).toEqual([]);
    expect(groupFeatures(['ABS'], [])).toEqual([]);
  });
});

describe('galleryImages', () => {
  const variants = (name: string) => ({
    THUMBNAIL: { url: `${name}-thumb.jpg`, width: 320, height: 240 },
    CARD: { url: `${name}-card.jpg`, width: 800, height: 600 },
    FULL: { url: `${name}-full.jpg`, width: 1600, height: 1200 },
  });

  it('gives each photo its three sizes', () => {
    expect(galleryImages({ images: [{ id: 'a', position: 0, variants: variants('a') }] })).toEqual([
      { id: 'a', thumbnail: 'a-thumb.jpg', medium: 'a-card.jpg', large: 'a-full.jpg', width: 1600, height: 1200 },
    ]);
  });

  it('keeps the seller’s order, whatever order the API lists them in', () => {
    // The first photo is the one the seller chose to lead with.
    const images = galleryImages({
      images: [
        { id: 'c', position: 2, variants: variants('c') },
        { id: 'a', position: 0, variants: variants('a') },
        { id: 'b', position: 1, variants: variants('b') },
      ],
    });
    expect(images.map((image) => image.id)).toEqual(['a', 'b', 'c']);
  });

  it('falls back to the sizes it has when a variant is missing', () => {
    // A slightly soft picture beats an empty gallery.
    const [image] = galleryImages({ images: [{ id: 'a', variants: { CARD: { url: 'card.jpg' } } }] });
    expect(image).toMatchObject({ thumbnail: 'card.jpg', medium: 'card.jpg', large: 'card.jpg' });
  });

  it('skips an image with nothing to show', () => {
    expect(galleryImages({ images: [{ id: 'pending', variants: {} }, { id: 'bare' }] })).toEqual([]);
  });

  it('gives an empty gallery for a listing without photos', () => {
    expect(galleryImages({})).toEqual([]);
  });
});
