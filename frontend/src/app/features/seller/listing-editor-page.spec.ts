import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import {
  BASE_PATH,
  ErrorCode,
  FuelType,
  Gearbox,
  ImageStatus,
  ListingQuota,
  ListingResponse,
  ListingStatus,
  VehicleCondition,
} from '../../core/api';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { FlashService } from '../../core/ui/flash.service';
import { NBSP, formatIntegerFr } from '../../shared/format/number';
import { ListingEditorPage } from './listing-editor-page';
import { OTHER } from './listing-form';

const BASE = 'http://api.test';
const API = `${BASE}/api/v1`;
const ID = 'l1';

@Component({ template: 'mes annonces' })
class Stub {}

const MAKES = [
  { id: 'make-peugeot', name: 'Peugeot' },
  { id: 'make-renault', name: 'Renault' },
];
const MODELS = [
  { id: 'model-308', name: '308' },
  { id: 'model-3008', name: '3008' },
];
const ENUMS = {
  values: {
    FuelType: ['PETROL', 'DIESEL', 'SOMETHING_NEWER_THAN_THIS_BUILD'],
    Gearbox: ['MANUAL', 'AUTOMATIC'],
    BodyType: ['HATCHBACK', 'SUV'],
    VehicleCondition: ['NEW', 'USED'],
  },
};
const COUNTRIES = [{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }];

const { DRAFT, ACTIVE, PAUSED, SOLD, REMOVED } = ListingStatus;

const listing = (over: Partial<ListingResponse> = {}): ListingResponse => ({
  id: ID,
  status: DRAFT,
  allowedTransitions: [ACTIVE, REMOVED] as unknown as Set<ListingStatus>,
  title: 'Peugeot 308 BlueHDi 130',
  description: 'Première main.',
  priceAmount: 1_599_000,
  currencyCode: 'EUR',
  priceNegotiable: true,
  mileageKm: 42_500,
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
  images: [],
  ...over,
});

const image = (id: string, position: number) => ({
  id,
  position,
  status: ImageStatus.READY,
  variants: {
    THUMBNAIL: { url: `http://img.test/${id}/thumbnail.jpg` },
    CARD: { url: `http://img.test/${id}/card.jpg` },
    FULL: { url: `http://img.test/${id}/full.jpg`, width: 1600, height: 1200 },
  },
});

/**
 * The listing editor: create and edit, four steps.
 *
 * <p>The properties worth a test are the ones a seller would be hurt by. Nothing is sent
 * until the draft can exist, and then exactly one POST. Hidden fields never block the
 * form. The rules for going online — a photo, the cap — are stated before the button is
 * pressed, and the button does nothing while they are unmet. And whatever the API refuses
 * is said in French.
 */
describe('ListingEditorPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let router: Router;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  async function answerReference(makes: unknown = MAKES): Promise<void> {
    open(`${API}/reference/makes`).forEach((request) =>
      makes === null ? request.flush(null, { status: 503, statusText: 'Down' }) : request.flush(makes as object[]),
    );
    open(`${API}/reference/enums`).forEach((request) => request.flush(ENUMS));
    open(`${API}/reference/countries`).forEach((request) => request.flush(COUNTRIES));
    await settle();
  }

  async function answerModels(): Promise<void> {
    open(`${API}/reference/makes/make-peugeot/models`).forEach((request) => request.flush(MODELS));
    await settle();
  }

  async function visit(url: string, makes: unknown = MAKES): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'vendre', component: ListingEditorPage },
            { path: 'mes-annonces/:id/modifier', component: ListingEditorPage },
            { path: 'mes-annonces', component: Stub },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
        // The editor reads the account's country for its market; signed out, it falls back to France.
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    await answerReference(makes);
  }

  /** Opens an existing listing at a step. */
  async function edit(step: string, value: ListingResponse = listing(), quota?: ListingQuota | null): Promise<void> {
    await visit(`/mes-annonces/${ID}/modifier?etape=${step}`);
    open(`${API}/listings/mine/${ID}`)[0].flush(value);
    await settle();
    await answerModels();
    if (quota !== undefined) {
      open(`${API}/listings/mine/quota`).forEach((request) =>
        quota === null ? request.flush(null, { status: 500, statusText: 'Error' }) : request.flush(quota),
      );
      await settle();
    }
  }

  const control = (label: string) => {
    const found = Array.from(root.querySelectorAll('label')).find((candidate) =>
      candidate.textContent?.trim().startsWith(label),
    );
    return found ? (root.querySelector(`#${found.getAttribute('for')}`) as HTMLInputElement | HTMLSelectElement) : null;
  };

  async function fill(label: string, value: string): Promise<void> {
    const element = control(label);
    if (!element) {
      throw new Error(`No field labelled "${label}"`);
    }
    element.value = value;
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input'));
    await settle();
  }

  const submit = async () => {
    (root.querySelector('form button[type=submit]') as HTMLButtonElement).click();
    await settle();
  };
  const press = async (label: string) => {
    const found = Array.from(root.querySelectorAll('button, a')).find((candidate) =>
      candidate.textContent?.replace(/\s+/g, ' ').trim().includes(label),
    );
    if (!found) {
      throw new Error(`Nothing to press labelled "${label}"`);
    }
    (found as HTMLElement).click();
    await settle();
  };
  const errors = () => Array.from(root.querySelectorAll('.field__error')).map((error) => error.textContent?.trim());
  const text = () => (root.textContent ?? '').replace(/\s+/g, ' ');
  const publishButton = () => root.querySelector('[data-action=publish]') as HTMLButtonElement | null;
  const currentStep = () => root.querySelector('[aria-current=step]')?.textContent?.replace(/\s+/g, ' ').trim();

  async function fillVehicle(): Promise<void> {
    await fill('Marque', 'make-peugeot');
    await answerModels();
    await fill('Modèle', 'model-308');
    await fill('Version', 'BlueHDi 130');
    await fill('Année', '2021');
    await fill('Kilométrage', '42 500');
    await fill('Carburant', 'DIESEL');
    await fill('Boîte', 'MANUAL');
    await fill('État', 'USED');
  }

  async function fillOffer(): Promise<void> {
    await fill('Prix', '15 990');
    await fill('Code postal', '69003');
    await fill('Ville', 'Lyon');
  }

  describe('creating: the vehicle', () => {
    it('opens on the first of four steps, having sent nothing but reference lookups', async () => {
      await visit('/vendre');

      expect(root.querySelector('h1')?.textContent).toBe('Déposer une annonce');
      expect(Array.from(root.querySelectorAll('.steps__item')).map((item) => item.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
        '1 Véhicule',
        '2 Prix et description',
        '3 Photos',
        '4 Aperçu et publication',
      ]);
      expect(currentStep()).toBe('1 Véhicule');
      backend.expectNone(`${API}/listings`);
    });

    it('offers the catalogue’s makes, then a way out of the catalogue', async () => {
      await visit('/vendre');

      expect(Array.from((control('Marque') as HTMLSelectElement).options).map((option) => option.textContent?.trim())).toEqual([
        'Choisir…',
        'Peugeot',
        'Renault',
        'Autre marque (à saisir)',
      ]);
    });

    it('asks for the model only once a make is chosen, from that make’s models', async () => {
      await visit('/vendre');
      expect(control('Modèle')).toBeNull();

      await fill('Marque', 'make-peugeot');
      await answerModels();

      expect(Array.from((control('Modèle') as HTMLSelectElement).options).map((option) => option.textContent?.trim())).toEqual([
        'Choisir…',
        '308',
        '3008',
        'Autre modèle (à saisir)',
      ]);
    });

    it('forgets the model when the make changes', async () => {
      await visit('/vendre');
      await fill('Marque', 'make-peugeot');
      await answerModels();
      await fill('Modèle', 'model-308');

      await fill('Marque', 'make-renault');
      open(`${API}/reference/makes/make-renault/models`).forEach((request) => request.flush([{ id: 'model-clio', name: 'Clio' }]));
      await settle();

      expect((control('Modèle') as HTMLSelectElement).value).toBe('');
    });

    it('offers the enum values in French, and only those this build can name', async () => {
      await visit('/vendre');

      expect(Array.from((control('Carburant') as HTMLSelectElement).options).map((option) => [option.value, option.textContent?.trim()])).toEqual([
        ['', 'Choisir…'],
        ['PETROL', 'Essence'],
        ['DIESEL', 'Diesel'],
      ]);
    });

    it('names every missing field in French and goes nowhere', async () => {
      await visit('/vendre');
      await submit();

      expect(errors()).toEqual(Array.from({ length: 6 }, () => 'Ce champ est obligatoire.'));
      expect(router.url).toBe('/vendre');
    });

    it('requires a model once a make is chosen', async () => {
      await visit('/vendre');
      await fillVehicle();
      await fill('Modèle', '');
      await submit();

      expect(errors()).toEqual(['Ce champ est obligatoire.']);
      expect(router.url).toBe('/vendre');
    });

    it('explains a year and a mileage that cannot be right', async () => {
      await visit('/vendre');
      await fill('Année', '1850');
      await fill('Kilométrage', '12,5');
      await submit();

      expect(errors()).toContain(`Saisissez une valeur entre 1${NBSP}900 et ${formatIntegerFr(new Date().getFullYear() + 1)}.`);
      expect(errors()).toContain('Saisissez un nombre entier, sans décimale.');
    });

    it('moves to the second step without saving: there is nothing the API would accept yet', async () => {
      await visit('/vendre');
      await fillVehicle();
      await submit();

      expect(router.url).toBe('/vendre?etape=offre');
      expect(currentStep()).toBe('2 Prix et description');
      backend.expectNone(`${API}/listings`);
    });
  });

  describe('creating: outside the catalogue', () => {
    it('opens two text fields for "another make", and stops asking for a model from a list', async () => {
      await visit('/vendre');
      await fill('Marque', OTHER);

      expect(control('Marque (saisie libre)')).not.toBeNull();
      expect(control('Modèle (saisie libre)')).not.toBeNull();
      expect(root.querySelectorAll('select')).toHaveLength(5);
    });

    it('opens one text field for "another model" of a catalogue make', async () => {
      await visit('/vendre');
      await fill('Marque', 'make-peugeot');
      await answerModels();
      await fill('Modèle', OTHER);

      expect(control('Modèle (saisie libre)')).not.toBeNull();
      expect(control('Marque (saisie libre)')).toBeNull();
    });

    it('requires the typed fields that are on screen, and only those', async () => {
      await visit('/vendre');
      await fillVehicle();
      await fill('Marque', OTHER);
      await submit();
      expect(errors()).toEqual(['Ce champ est obligatoire.', 'Ce champ est obligatoire.']);

      await fill('Marque (saisie libre)', 'Aixam');
      await fill('Modèle (saisie libre)', 'City');
      await submit();
      expect(router.url).toBe('/vendre?etape=offre');
    });

    it('sends a typed vehicle as text, never as half an id', async () => {
      await visit('/vendre');
      await fillVehicle();
      await fill('Modèle', OTHER);
      await fill('Modèle (saisie libre)', '308 SW');
      await submit();
      await fillOffer();
      await submit();

      const body = open(`${API}/listings`)[0].request.body;
      expect(body).toMatchObject({ makeText: 'Peugeot', modelText: '308 SW' });
      expect(body).not.toHaveProperty('makeId');
      expect(body).not.toHaveProperty('modelId');
    });

    it('still works, typed, when the catalogue cannot be loaded', async () => {
      await visit('/vendre', null);

      expect(text()).toContain('La liste des marques est indisponible');
      expect(control('Marque (saisie libre)')).not.toBeNull();
      expect(control('Modèle (saisie libre)')).not.toBeNull();

      await fill('Marque (saisie libre)', 'Peugeot');
      await fill('Modèle (saisie libre)', '308');
      await fill('Année', '2021');
      await fill('Kilométrage', '42500');
      await fill('Carburant', 'DIESEL');
      await fill('Boîte', 'MANUAL');
      await fill('État', 'USED');
      await submit();

      expect(router.url).toBe('/vendre?etape=offre');
    });
  });

  describe('creating: the offer, and the draft', () => {
    async function toOffer(): Promise<void> {
      await visit('/vendre');
      await fillVehicle();
      await submit();
    }

    it('suggests a title from the vehicle, which the seller may change', async () => {
      await toOffer();

      expect((control('Titre') as HTMLInputElement).value).toBe('Peugeot 308 BlueHDi 130');
    });

    it('does not overwrite a title the seller has typed', async () => {
      await toOffer();
      await fill('Titre', 'Ma 308, très propre');
      await press('Précédent');
      await submit();

      expect((control('Titre') as HTMLInputElement).value).toBe('Ma 308, très propre');
    });

    it('requires a price and a place', async () => {
      await toOffer();
      await submit();

      expect(errors()).toEqual(Array.from({ length: 3 }, () => 'Ce champ est obligatoire.'));
      backend.expectNone(`${API}/listings`);
    });

    it('creates the draft with one POST carrying both steps', async () => {
      await toOffer();
      await fillOffer();
      await fill('Description', 'Première main.');
      (root.querySelector('input[type=checkbox]') as HTMLInputElement).click();
      await settle();
      await submit();

      const requests = open(`${API}/listings`);
      expect(requests).toHaveLength(1);
      expect(requests[0].request.method).toBe('POST');
      expect(requests[0].request.body).toEqual({
        makeId: 'make-peugeot',
        modelId: 'model-308',
        versionText: 'BlueHDi 130',
        firstRegistrationYear: 2021,
        mileageKm: 42500,
        fuelType: 'DIESEL',
        gearbox: 'MANUAL',
        condition: 'USED',
        title: 'Peugeot 308 BlueHDi 130',
        description: 'Première main.',
        // 15 990 €, in cents.
        priceAmount: 1_599_000,
        currencyCode: 'EUR',
        priceNegotiable: true,
        postalCode: '69003',
        city: 'Lyon',
        countryCode: 'FR',
      });
    });

    it('then continues at the photos step, on the listing’s own address', async () => {
      // So that a reload — or a closed tab — carries on with the draft.
      await toOffer();
      await fillOffer();
      await submit();
      open(`${API}/listings`)[0].flush(listing());
      await settle();

      expect(router.url).toBe(`/mes-annonces/${ID}/modifier?etape=photos`);
      // The edit route loads the draft it was just given the address of — exactly what a
      // reload would do, which is the point of changing the address.
      open(`${API}/listings/mine/${ID}`)[0].flush(listing());
      await settle();
      await answerReference();

      expect(currentStep()).toBe('3 Photos');
      expect(root.querySelector('app-photo-uploader')).not.toBeNull();
      // Still exactly one POST: the draft is not created again on arrival.
      backend.expectNone(`${API}/listings`);
    });

    it('does not create a second draft when the button is pressed twice', async () => {
      await toOffer();
      await fillOffer();
      await submit();

      expect((root.querySelector('form button[type=submit]') as HTMLButtonElement).disabled).toBe(true);
    });

    it('says in French why the draft could not be saved, and keeps what was typed', async () => {
      await toOffer();
      await fillOffer();
      await submit();
      open(`${API}/listings`)[0].flush(
        { code: ErrorCode.VALIDATION_FAILED, message: 'postalCode: must match', detail: 'raw' },
        { status: 400, statusText: 'Bad Request' },
      );
      await settle();

      expect(root.querySelector('.alert--error')).not.toBeNull();
      expect(text()).not.toContain('VALIDATION_FAILED');
      expect(text()).not.toContain('must match');
      expect((control('Prix') as HTMLInputElement).value).toBe('15 990');
      expect(router.url).toBe('/vendre?etape=offre');
    });

    it('sends the seller back to the vehicle when the offer is opened on its own', async () => {
      // A reload at step two of a new listing: the first step's answers are gone.
      await visit('/vendre?etape=offre');
      await fillOffer();
      await fill('Titre', 'Titre');
      await submit();

      expect(router.url).toBe('/vendre?etape=vehicule');
      backend.expectNone(`${API}/listings`);
    });

    it.each(['photos', 'apercu'])('has no %s step before there is a listing to attach it to', async (step) => {
      await visit(`/vendre?etape=${step}`);

      expect(currentStep()).toBe('1 Véhicule');
      // And the stepper does not offer them.
      expect(Array.from(root.querySelectorAll('button.steps__link'))).toHaveLength(0);
    });
  });

  describe('editing', () => {
    it('loads the listing into the same form', async () => {
      await edit('vehicule');

      expect(root.querySelector('h1')?.textContent).toBe('Modifier l’annonce');
      expect(TestBed.inject(Router).url).toContain(ID);
      expect((control('Marque') as HTMLSelectElement).value).toBe('make-peugeot');
      expect((control('Modèle') as HTMLSelectElement).value).toBe('model-308');
      expect((control('Version') as HTMLInputElement).value).toBe('BlueHDi 130');
      expect((control('Année') as HTMLInputElement).value).toBe('2021');
      expect((control('Kilométrage') as HTMLInputElement).value).toBe('42500');
      expect((control('Carburant') as HTMLSelectElement).value).toBe('DIESEL');
      expect((control('État') as HTMLSelectElement).value).toBe('USED');
    });

    it('loads the offer too, the price back in euros', async () => {
      await edit('offre');

      expect((control('Titre') as HTMLInputElement).value).toBe('Peugeot 308 BlueHDi 130');
      expect((control('Prix') as HTMLInputElement).value).toBe('15990');
      expect((root.querySelector('input[type=checkbox]') as HTMLInputElement).checked).toBe(true);
      expect((control('Description') as HTMLInputElement).value).toBe('Première main.');
      expect((control('Code postal') as HTMLInputElement).value).toBe('69003');
      expect((control('Ville') as HTMLInputElement).value).toBe('Lyon');
    });

    it('opens the typed fields of a listing recorded outside the catalogue', async () => {
      await edit('vehicule', listing({ vehicle: { make: 'Aixam', model: 'City', firstRegistrationYear: 2021 } }));

      expect((control('Marque') as HTMLSelectElement).value).toBe(OTHER);
      expect((control('Marque (saisie libre)') as HTMLInputElement).value).toBe('Aixam');
      expect((control('Modèle (saisie libre)') as HTMLInputElement).value).toBe('City');
    });

    it('names the listing in the tab', async () => {
      await edit('vehicule');

      expect(document.title).toBe('Modifier « Peugeot 308 BlueHDi 130 » · AutoX');
    });

    it('saves the vehicle step with a PATCH of that step’s fields', async () => {
      await edit('vehicule');
      await fill('Kilométrage', '51 000');
      await submit();

      const request = open(`${API}/listings/${ID}`)[0];
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toMatchObject({ makeId: 'make-peugeot', modelId: 'model-308', mileageKm: 51000 });
      expect(request.request.body).not.toHaveProperty('priceAmount');
      request.flush(listing({ mileageKm: 51000 }));
      await settle();

      expect(router.url).toBe(`/mes-annonces/${ID}/modifier?etape=offre`);
    });

    it('saves the offer step with a PATCH, never a second POST', async () => {
      await edit('offre');
      await fill('Prix', '14 500');
      await submit();

      const request = open(`${API}/listings/${ID}`)[0];
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toMatchObject({ priceAmount: 1_450_000, currencyCode: 'EUR' });
      backend.expectNone(`${API}/listings`);
      request.flush(listing({ priceAmount: 1_450_000 }));
      await settle();

      expect(router.url).toBe(`/mes-annonces/${ID}/modifier?etape=photos`);
    });

    it('lets every step be opened directly from the stepper', async () => {
      await edit('vehicule');

      expect(Array.from(root.querySelectorAll('button.steps__link')).map((step) => step.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
        '2 Prix et description',
        '3 Photos',
        '4 Aperçu et publication',
      ]);

      await press('3 Photos');
      expect(router.url).toBe(`/mes-annonces/${ID}/modifier?etape=photos`);
    });

    it('says in French that a save was refused', async () => {
      await edit('vehicule', listing({ status: SOLD, allowedTransitions: [REMOVED] as unknown as Set<ListingStatus> }));
      await submit();
      open(`${API}/listings/${ID}`)[0].flush({ code: ErrorCode.CONFLICT }, { status: 409, statusText: 'Conflict' });
      await settle();

      expect(root.querySelector('.alert--error')?.textContent).toContain('Cette action n’est pas possible dans l’état actuel.');
      expect(router.url).toBe(`/mes-annonces/${ID}/modifier?etape=vehicule`);
    });

    it('shows a not-found page for a listing that is not the caller’s, or is gone', async () => {
      await visit(`/mes-annonces/${ID}/modifier`);
      open(`${API}/listings/mine/${ID}`)[0].flush({ code: ErrorCode.NOT_FOUND }, { status: 404, statusText: 'Not Found' });
      await settle();

      expect(root.querySelector('app-not-found')?.textContent).toContain('Cette annonce est introuvable');
      expect(root.querySelector('form')).toBeNull();
    });

    it('offers a retry when the listing could not be loaded', async () => {
      await visit(`/mes-annonces/${ID}/modifier`);
      open(`${API}/listings/mine/${ID}`)[0].flush(null, { status: 500, statusText: 'Error' });
      await settle();
      expect(root.querySelector('.alert--error')).not.toBeNull();

      await press('Réessayer');
      open(`${API}/listings/mine/${ID}`)[0].flush(listing());
      await settle();

      expect(root.querySelector('form')).not.toBeNull();
    });
  });

  describe('photos', () => {
    it('hands the listing and its photos to the uploader, ready ones only, in order', async () => {
      await edit(
        'photos',
        listing({ images: [image('b', 1), image('a', 0), { ...image('c', 2), status: ImageStatus.PENDING }] }),
      );

      expect(Array.from(root.querySelectorAll('.photo img')).map((img) => (img as HTMLImageElement).src)).toEqual([
        'http://img.test/a/thumbnail.jpg',
        'http://img.test/b/thumbnail.jpg',
      ]);
    });

    it('carries a deletion made here through to the preview', async () => {
      await edit('photos', listing({ images: [image('a', 0)] }));
      await press('Supprimer');
      open(`${API}/listings/${ID}/images/a`)[0].flush(null);
      await settle();
      await press('Voir l’aperçu');
      open(`${API}/listings/mine/quota`).forEach((request) => request.flush({ active: 0, limit: 5, canPublish: true }));
      await settle();

      expect(publishButton()?.disabled).toBe(true);
      expect(root.querySelector('[data-rule=photo]')).not.toBeNull();
    });
  });

  describe('preview and going online', () => {
    const ok: ListingQuota = { active: 1, limit: 5, canPublish: true };
    const withPhoto = () => listing({ images: [image('a', 0), image('b', 1)] });

    it('shows the listing as a buyer will see it', async () => {
      await edit('apercu', withPhoto(), ok);

      expect(root.querySelector('.preview__title')?.textContent).toBe('Peugeot 308 BlueHDi 130');
      expect(root.querySelector('.preview app-price')?.textContent).toContain(`15${NBSP}990${NBSP}€`);
      expect(root.querySelector('.preview')?.textContent).toContain('Prix négociable');
      expect(root.querySelector('.preview__description')?.textContent).toBe('Première main.');
      expect(root.querySelector('app-photo-gallery')).not.toBeNull();
      const specs = Array.from(root.querySelectorAll('.preview .readout > div')).map(
        (spec) => `${spec.querySelector('dt')?.textContent?.trim()} ${spec.querySelector('dd')?.textContent?.trim()}`,
      );
      expect(specs).toEqual(
        expect.arrayContaining([
          'Année 2021',
          `Kilométrage 42${NBSP}500${NBSP}km`,
          'Carburant Diesel',
          'Boîte de vitesses Manuelle',
          'État Occasion',
          'Localisation 69003 Lyon',
        ]),
      );
    });

    it('offers to go online when there is a photo and room under the cap', async () => {
      await edit('apercu', withPhoto(), ok);

      expect(publishButton()?.disabled).toBe(false);
      expect(root.querySelector('[data-rule]')).toBeNull();
    });

    it('explains, before the button is pressed, that a photo is needed', async () => {
      await edit('apercu', listing(), ok);

      expect(root.querySelector('[data-rule=photo]')?.textContent).toContain(
        'Ajoutez au moins une photo pour mettre l’annonce en ligne.',
      );
      expect(publishButton()?.disabled).toBe(true);
      expect(root.querySelector('.preview__nophoto')).not.toBeNull();
    });

    it('explains the cap for a private seller who has reached it', async () => {
      await edit('apercu', withPhoto(), { active: 5, limit: 5, canPublish: false });

      const rule = root.querySelector('[data-rule=cap]')?.textContent?.replace(/\s+/g, ' ');
      expect(rule).toContain('Vous avez déjà 5 annonces en ligne, le maximum pour un compte particulier (5).');
      expect(rule).toContain('Votre brouillon est conservé.');
      expect(publishButton()?.disabled).toBe(true);
    });

    it('sends nothing when the disabled button is pressed anyway', async () => {
      await edit('apercu', listing(), ok);
      publishButton()!.disabled = false;
      publishButton()!.click();
      await settle();

      backend.expectNone(`${API}/listings/${ID}/publish`);
    });

    it('leaves the decision to the API when the quota cannot be read', async () => {
      // Unknown is not "no".
      await edit('apercu', withPhoto(), null);

      expect(publishButton()?.disabled).toBe(false);
      expect(root.querySelector('[data-rule=cap]')).toBeNull();
    });

    it('publishes, then returns to the seller’s listings with a confirmation', async () => {
      await edit('apercu', withPhoto(), ok);
      await press('Mettre en ligne');

      const request = open(`${API}/listings/${ID}/publish`)[0];
      expect(request.request.method).toBe('POST');
      expect(publishButton()?.disabled).toBe(true);
      request.flush({ ...withPhoto(), status: ACTIVE });
      await settle();

      expect(router.url).toBe('/mes-annonces');
      expect(TestBed.inject(FlashService).take()).toBe('Votre annonce est en ligne.');
    });

    it('explains the cap when it was reached between the check and the click', async () => {
      await edit('apercu', withPhoto(), ok);
      await press('Mettre en ligne');
      open(`${API}/listings/${ID}/publish`)[0].flush(
        { code: ErrorCode.ACTIVE_LISTING_LIMIT_REACHED, message: 'limit' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(root.querySelector('.alert--error')?.textContent).toContain(
        'Vous avez atteint le nombre maximum d’annonces en ligne.',
      );
      expect(text()).not.toContain('ACTIVE_LISTING_LIMIT_REACHED');
      // And the button now reflects it.
      expect(publishButton()?.disabled).toBe(true);
      expect(router.url).toContain('etape=apercu');
    });

    it('explains a missing photo when the API is the one to notice', async () => {
      await edit('apercu', withPhoto(), ok);
      await press('Mettre en ligne');
      open(`${API}/listings/${ID}/publish`)[0].flush(
        { code: ErrorCode.PHOTO_REQUIRED },
        { status: 422, statusText: 'Unprocessable' },
      );
      await settle();

      expect(root.querySelector('.alert--error')?.textContent).toContain('Ajoutez au moins une photo avant de publier.');
    });

    it('has no publish button for a listing that is already online, and says its edits are live', async () => {
      await edit(
        'apercu',
        listing({ status: ACTIVE, allowedTransitions: [PAUSED, SOLD, REMOVED] as unknown as Set<ListingStatus>, images: [image('a', 0)] }),
      );

      expect(publishButton()).toBeNull();
      expect(text()).toContain('Cette annonce est en ligne. Vos modifications sont déjà visibles des acheteurs.');
      // No cap to explain, so none is asked about.
      backend.expectNone(`${API}/listings/mine/quota`);
    });

    it('has no publish button for a listing whose state does not allow going online', async () => {
      await edit('apercu', listing({ status: SOLD, allowedTransitions: [REMOVED] as unknown as Set<ListingStatus> }));

      expect(publishButton()).toBeNull();
      expect(text()).toContain('ne peut pas être mise en ligne depuis cette page');
    });

    it('can be left for later, the draft kept', async () => {
      await edit('apercu', withPhoto(), ok);
      await press('Plus tard');

      expect(router.url).toBe('/mes-annonces');
      backend.expectNone(`${API}/listings/${ID}/publish`);
    });
  });
});
