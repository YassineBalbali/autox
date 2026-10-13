import { describe, expect, it } from 'vitest';

import { FuelType, Gearbox, ListingResponse, Make, Model, VehicleCondition } from '../../core/api';
import {
  LIMITS,
  Market,
  OTHER,
  OfferValue,
  VehicleValue,
  makeIsTyped,
  modelIsTyped,
  offerForm,
  offerValueOf,
  suggestedTitle,
  toCreateRequest,
  toOfferUpdate,
  toVehicleUpdate,
  vehicleForm,
  vehicleIdentity,
  vehicleValueOf,
} from './listing-form';

/**
 * The listing form's rules and its translation to the API.
 *
 * <p>The cases that matter are the ones where a wrong mapping is silently accepted: a
 * price a hundred times too small, a catalogue make sent with a typed model (which the
 * API refuses), a cleared description that survives because it was omitted.
 */
describe('listing form', () => {
  const MAKES: Make[] = [
    { id: 'make-peugeot', name: 'Peugeot' },
    { id: 'make-renault', name: 'Renault' },
  ];
  const MODELS: Model[] = [
    { id: 'model-308', name: '308' },
    { id: 'model-3008', name: '3008' },
  ];
  const FRANCE: Market = { countryCode: 'FR', currencyCode: 'EUR', minorUnit: 2 };

  const vehicle = (over: Partial<VehicleValue> = {}): VehicleValue => ({
    make: 'make-peugeot',
    makeText: '',
    model: 'model-308',
    modelText: '',
    version: 'BlueHDi 130',
    year: '2021',
    mileage: '42 500',
    fuel: FuelType.DIESEL,
    gearbox: Gearbox.MANUAL,
    body: '',
    condition: VehicleCondition.USED,
    ...over,
  });

  const offer = (over: Partial<OfferValue> = {}): OfferValue => ({
    title: 'Peugeot 308 BlueHDi 130',
    price: '15 990',
    negotiable: false,
    description: 'Première main.',
    postalCode: '69003',
    city: 'Lyon',
    ...over,
  });

  describe('the vehicle step', () => {
    it('requires what the API requires, and not the two optional fields', () => {
      const form = vehicleForm();
      const required = Object.entries(form.controls)
        .filter(([, control]) => control.errors?.['required'])
        .map(([name]) => name);
      // `model` and the typed fields are made required by the page, depending on which of
      // them is on screen.
      expect(required).toEqual(['make', 'year', 'mileage', 'fuel', 'gearbox', 'condition']);
    });

    it('accepts next year and refuses the one after', () => {
      const form = vehicleForm(new Date('2026-10-12T00:00:00Z'));
      form.controls.year.setValue('2027');
      expect(form.controls.year.errors).toBeNull();
      form.controls.year.setValue('2028');
      expect(form.controls.year.errors).toEqual({ range: { min: LIMITS.minYear, max: 2027 } });
    });

    it('accepts zero kilometres, which is a new car', () => {
      const form = vehicleForm();
      form.controls.mileage.setValue('0');
      expect(form.controls.mileage.errors).toBeNull();
    });
  });

  describe('typed or chosen', () => {
    it('types the make when "other" is chosen, or when there is no catalogue at all', () => {
      expect(makeIsTyped({ make: 'make-peugeot' }, MAKES)).toBe(false);
      expect(makeIsTyped({ make: OTHER }, MAKES)).toBe(true);
      expect(makeIsTyped({ make: '' }, [])).toBe(true);
    });

    it('types the model whenever the make is typed', () => {
      expect(modelIsTyped({ make: OTHER, model: '' }, MAKES)).toBe(true);
      expect(modelIsTyped({ make: 'make-peugeot', model: OTHER }, MAKES)).toBe(true);
      expect(modelIsTyped({ make: 'make-peugeot', model: 'model-308' }, MAKES)).toBe(false);
    });
  });

  describe('vehicleIdentity', () => {
    it('sends two ids when both come from the catalogue', () => {
      expect(vehicleIdentity(vehicle(), MAKES, MODELS)).toEqual({
        makeId: 'make-peugeot',
        modelId: 'model-308',
      });
    });

    it('sends two texts when both are typed', () => {
      const value = vehicle({ make: OTHER, makeText: ' Aixam ', model: '', modelText: ' City ' });
      expect(vehicleIdentity(value, MAKES, MODELS)).toEqual({ makeText: 'Aixam', modelText: 'City' });
    });

    it('never mixes: a catalogue make with a typed model goes as two texts', () => {
      // The API answers 400 to makeId without modelId. The make's name stands in for its id.
      const value = vehicle({ model: OTHER, modelText: '308 SW' });
      expect(vehicleIdentity(value, MAKES, MODELS)).toEqual({ makeText: 'Peugeot', modelText: '308 SW' });
    });

    it('never sends an id the catalogue in hand does not contain', () => {
      const value = vehicle({ model: 'model-from-another-make' });
      const identity = vehicleIdentity(value, MAKES, MODELS);
      expect(identity).not.toHaveProperty('modelId');
      expect(identity).not.toHaveProperty('makeId');
    });
  });

  describe('suggestedTitle', () => {
    it('is make, model, version', () => {
      expect(suggestedTitle(vehicle(), MAKES, MODELS)).toBe('Peugeot 308 BlueHDi 130');
    });

    it('uses the typed words when there are no ids', () => {
      const value = vehicle({ make: OTHER, makeText: 'Aixam', modelText: 'City', version: '' });
      expect(suggestedTitle(value, MAKES, MODELS)).toBe('Aixam City');
    });

    it('never exceeds the title limit', () => {
      const value = vehicle({ version: 'x'.repeat(200) });
      expect(suggestedTitle(value, MAKES, MODELS)).toHaveLength(LIMITS.title);
    });
  });

  describe('toCreateRequest', () => {
    it('builds the whole draft from the two steps', () => {
      expect(toCreateRequest(vehicle(), offer(), FRANCE, MAKES, MODELS)).toEqual({
        makeId: 'make-peugeot',
        modelId: 'model-308',
        versionText: 'BlueHDi 130',
        firstRegistrationYear: 2021,
        mileageKm: 42500,
        fuelType: FuelType.DIESEL,
        gearbox: Gearbox.MANUAL,
        bodyType: undefined,
        condition: VehicleCondition.USED,
        title: 'Peugeot 308 BlueHDi 130',
        description: 'Première main.',
        priceAmount: 1_599_000,
        currencyCode: 'EUR',
        priceNegotiable: false,
        postalCode: '69003',
        city: 'Lyon',
        countryCode: 'FR',
      });
    });

    it('stores the price in minor units of the market’s own currency', () => {
      // 15 990 € is 1 599 000 cents; in a currency without decimals it is 15 990.
      const yen: Market = { countryCode: 'JP', currencyCode: 'JPY', minorUnit: 0 };
      expect(toCreateRequest(vehicle(), offer(), FRANCE, MAKES, MODELS).priceAmount).toBe(1_599_000);
      const japan = toCreateRequest(vehicle(), offer(), yen, MAKES, MODELS);
      expect(japan.priceAmount).toBe(15_990);
      expect(japan.currencyCode).toBe('JPY');
      expect(japan.countryCode).toBe('JP');
    });

    it('trims what the seller typed', () => {
      const request = toCreateRequest(
        vehicle({ version: '  GT  ' }),
        offer({ title: '  Titre  ', city: ' Lyon ', postalCode: ' 69003 ' }),
        FRANCE,
        MAKES,
        MODELS,
      );
      expect(request).toMatchObject({ versionText: 'GT', title: 'Titre', city: 'Lyon', postalCode: '69003' });
    });
  });

  describe('updates', () => {
    it('sends only the vehicle step’s fields from the vehicle step', () => {
      const update = toVehicleUpdate(vehicle({ mileage: '51000' }), MAKES, MODELS);
      expect(update).toMatchObject({ makeId: 'make-peugeot', modelId: 'model-308', mileageKm: 51000 });
      expect(update).not.toHaveProperty('priceAmount');
      expect(update).not.toHaveProperty('title');
    });

    it('sends only the offer step’s fields from the offer step', () => {
      const update = toOfferUpdate(offer({ price: '9500', negotiable: true }), FRANCE);
      expect(update).toMatchObject({ priceAmount: 950_000, priceNegotiable: true, currencyCode: 'EUR' });
      expect(update).not.toHaveProperty('mileageKm');
    });

    it('sends an emptied description as an empty string, so that it is cleared', () => {
      // Omitted, the API would leave the old text in place — and the seller who deleted it
      // would see it come back.
      expect(toOfferUpdate(offer({ description: '   ' }), FRANCE).description).toBe('');
    });
  });

  describe('loading an existing listing', () => {
    const listing: ListingResponse = {
      id: 'l1',
      title: 'Peugeot 308',
      description: 'Texte',
      priceAmount: 1_599_000,
      currencyCode: 'EUR',
      priceNegotiable: true,
      mileageKm: 42500,
      condition: VehicleCondition.USED,
      location: { postalCode: '69003', city: 'Lyon' },
      vehicle: {
        makeId: 'make-peugeot',
        make: 'Peugeot',
        modelId: 'model-308',
        model: '308',
        version: 'BlueHDi 130',
        firstRegistrationYear: 2021,
        fuelType: FuelType.DIESEL,
        gearbox: Gearbox.MANUAL,
      },
    };

    it('selects the catalogue entries of a catalogued listing', () => {
      expect(vehicleValueOf(listing)).toEqual({
        make: 'make-peugeot',
        makeText: '',
        model: 'model-308',
        modelText: '',
        version: 'BlueHDi 130',
        year: '2021',
        mileage: '42500',
        fuel: FuelType.DIESEL,
        gearbox: Gearbox.MANUAL,
        body: '',
        condition: VehicleCondition.USED,
      });
    });

    it('opens the typed fields of a free-text listing, with its words in them', () => {
      const typed = { ...listing, vehicle: { make: 'Aixam', model: 'City' } };
      expect(vehicleValueOf(typed)).toMatchObject({
        make: OTHER,
        makeText: 'Aixam',
        model: OTHER,
        modelText: 'City',
      });
    });

    it('keeps a mileage of zero, and leaves an unstated one empty', () => {
      expect(vehicleValueOf({ ...listing, mileageKm: 0 }).mileage).toBe('0');
      expect(vehicleValueOf({ ...listing, mileageKm: undefined }).mileage).toBe('');
    });

    it('shows the price in whole units of its currency', () => {
      expect(offerValueOf(listing, 2)).toEqual({
        title: 'Peugeot 308',
        price: '15990',
        negotiable: true,
        description: 'Texte',
        postalCode: '69003',
        city: 'Lyon',
      });
      expect(offerValueOf({ ...listing, priceAmount: 15_990 }, 0).price).toBe('15990');
    });

    it('round-trips: loading a listing and saving it unchanged changes nothing', () => {
      const request = toCreateRequest(vehicleValueOf(listing), offerValueOf(listing, 2), FRANCE, MAKES, MODELS);
      expect(request).toMatchObject({
        makeId: 'make-peugeot',
        modelId: 'model-308',
        firstRegistrationYear: 2021,
        mileageKm: 42500,
        priceAmount: 1_599_000,
        priceNegotiable: true,
        postalCode: '69003',
        city: 'Lyon',
      });
    });

    it('produces values the forms accept', () => {
      const form = vehicleForm();
      form.setValue(vehicleValueOf(listing));
      expect(form.valid).toBe(true);
      const second = offerForm();
      second.setValue(offerValueOf(listing, 2));
      expect(second.valid).toBe(true);
    });
  });
});
