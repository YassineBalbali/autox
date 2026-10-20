import { describe, expect, it } from 'vitest';

import { PublicListingSummary } from '../../core/api';
import { coverOf, departmentOf, headlineOf, listingLink, placeOf } from './summary';

const CAPTUR: PublicListingSummary = {
  id: 'l-1',
  title: 'Renault Captur E-Tech Plug-in 160 Intens, carnet complet',
  make: 'Renault',
  model: 'Captur',
  version: 'E-Tech Plug-in 160 Intens',
};

/**
 * What the results read off a listing summary. Every field on a generated model is
 * optional, so each is tried with things missing as much as with things present.
 */
describe('listing summary', () => {
  describe('headlineOf', () => {
    it('heads a result with the make and model, the version under it', () => {
      // The seller's own title ("…, carnet complet") is left to the listing's page: a
      // column of results is scanned for "Renault Captur", in those words, every time.
      expect(headlineOf(CAPTUR)).toEqual({ title: 'Renault Captur', subtitle: 'E-Tech Plug-in 160 Intens' });
    });

    it('has no subtitle when there is no version', () => {
      expect(headlineOf({ ...CAPTUR, version: undefined })).toEqual({ title: 'Renault Captur', subtitle: null });
      expect(headlineOf({ ...CAPTUR, version: '   ' }).subtitle).toBeNull();
    });

    it('uses the make alone when there is no model', () => {
      expect(headlineOf({ ...CAPTUR, model: undefined }).title).toBe('Renault');
    });

    it('falls back to the seller’s title when there is no make', () => {
      // An older listing, or one typed by hand: never a result headed by nothing.
      expect(headlineOf({ ...CAPTUR, make: undefined })).toEqual({ title: CAPTUR.title, subtitle: null });
      expect(headlineOf({ ...CAPTUR, make: ' ' }).title).toBe(CAPTUR.title);
    });

    it('is an empty title, not "undefined", for a listing with nothing', () => {
      expect(headlineOf({})).toEqual({ title: '', subtitle: null });
    });
  });

  describe('coverOf', () => {
    const card = { url: 'http://img.test/card.jpg', width: 800, height: 600 };
    const thumb = { url: 'http://img.test/thumb.jpg', width: 320, height: 240 };

    it('prefers the card variant, then the thumbnail', () => {
      expect(coverOf({ coverImage: { CARD: card, THUMBNAIL: thumb } })).toEqual(card);
      expect(coverOf({ coverImage: { THUMBNAIL: thumb } })).toEqual(thumb);
    });

    it('is null without a usable image', () => {
      expect(coverOf({})).toBeNull();
      expect(coverOf({ coverImage: {} })).toBeNull();
      expect(coverOf({ coverImage: { CARD: { width: 800, height: 600 } } })).toBeNull();
    });
  });

  describe('placeOf', () => {
    it('gives the city with its département', () => {
      expect(placeOf({ city: 'Lyon', postalCode: '69003', countryCode: 'FR' })).toBe('Lyon (69)');
    });

    it('gives the city alone when there is no département to give', () => {
      expect(placeOf({ city: 'Berlin', postalCode: '10115', countryCode: 'DE' })).toBe('Berlin');
    });

    it('is null without a city', () => {
      expect(placeOf({ postalCode: '69003', countryCode: 'FR' })).toBeNull();
    });
  });

  it('links to the listing page, or nowhere without an id', () => {
    expect(listingLink('l-1')).toEqual(['/annonces', 'l-1']);
    expect(listingLink(undefined)).toBeNull();
  });

  describe('departmentOf', () => {
    it.each([
      ['69003', 'FR', '69'],
      ['75011', 'FR', '75'],
      ['01000', 'FR', '01'],
      // Overseas: three digits.
      ['97400', 'FR', '974'],
      ['98800', 'FR', '988'],
    ])('reads %s (%s) as %s', (postalCode, country, expected) => {
      expect(departmentOf(postalCode, country)).toBe(expected);
    });

    it.each([
      // Another country's postal code is not a département.
      ['10115', 'DE'],
      ['6900', 'FR'],
      ['SW1A 1AA', 'GB'],
      [undefined, 'FR'],
      ['69003', undefined],
    ])('gives nothing for %s (%s)', (postalCode, country) => {
      expect(departmentOf(postalCode, country)).toBeNull();
    });
  });
});
