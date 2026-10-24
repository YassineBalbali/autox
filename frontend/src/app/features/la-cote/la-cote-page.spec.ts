import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, Valuation, ValuationScope } from '../../core/api';
import { NBSP } from '../../shared/format/number';
import { LaCotePage } from './la-cote-page';

const BASE = 'http://api.test';
const API = `${BASE}/api/v1`;
const VALUE = `${API}/valuation`;
const ENGINES = `${API}/valuation/engines`;

@Component({ template: '' })
class Stub {}

const MAKES = [
  { id: 'make-renault', slug: 'renault', name: 'Renault' },
  { id: 'make-peugeot', slug: 'peugeot', name: 'Peugeot' },
];
const MODELS = [
  { id: 'model-clio', slug: 'clio', name: 'Clio' },
  { id: 'model-captur', slug: 'captur', name: 'Captur' },
];
const CLIO_ENGINES = [
  { fuelType: 'PETROL', powerHp: 90, exampleVersion: 'TCe 90 Evolution', listingCount: 8 },
  { fuelType: 'HYBRID_PETROL', powerHp: 145, exampleVersion: 'E-Tech 145', listingCount: 2 },
];
const CLIO_URL = '/la-cote?marque=renault&modele=clio&carburant=petrol&puissance=90&annee=2022&km=45000&boite=manual';

const WINDOW = {
  yearFrom: 2020,
  yearTo: 2024,
  mileageFromKm: 30_000,
  mileageToKm: 60_000,
  fuelType: 'PETROL',
  powerFromHp: 77,
  powerToHp: 103,
};

const ESTIMATE: Valuation = {
  scope: ValuationScope.EXACT,
  comparableCount: 7,
  minimumComparables: 5,
  estimate: { lowAmount: 970_000, medianAmount: 1_060_000, highAmount: 1_130_000, currencyCode: 'EUR' },
  basis: { yearFrom: 2022, yearTo: 2024, mileageFromKm: 35_600, mileageToKm: 56_900 },
  window: WINDOW,
  widerScopes: [],
};

const TOO_FEW: Valuation = {
  scope: ValuationScope.EXACT,
  comparableCount: 2,
  minimumComparables: 5,
  basis: { yearFrom: 2021, yearTo: 2022, mileageFromKm: 41_000, mileageToKm: 52_000 },
  window: WINDOW,
  widerScopes: [
    { scope: ValuationScope.ALL_ENGINES, comparableCount: 9 },
    { scope: ValuationScope.ANY_MILEAGE, comparableCount: 6 },
  ],
};

/**
 * La Cote's page: the description in the address, the estimate and what it rests on, no
 * figure from too few, the loosenings as links, and the two ways on.
 */
describe('LaCotePage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  // Matching takes requests off the queue, so only the ones asked for are matched.
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const valuations = () => open(VALUE);
  // Not \s, which would turn the no-break spaces in prices into ordinary ones.
  const text = () => (root.textContent ?? '').replace(/[ \t\r\n]+/g, ' ');
  const link = (label: string) =>
    Array.from(root.querySelectorAll('a')).find((a) => a.textContent?.trim().startsWith(label)) as HTMLAnchorElement;
  const field = (label: string) => {
    const found = Array.from(root.querySelectorAll('label')).find((l) => l.textContent?.trim().startsWith(label));
    return found ? (root.querySelector(`#${found.getAttribute('for')}`) as HTMLInputElement | HTMLSelectElement) : null;
  };
  async function choose(label: string, value: string): Promise<void> {
    const element = field(label)!;
    element.value = value;
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input'));
    await settle();
  }

  /** Answers the catalogue: the makes, then Renault's models, then the Clio's engines. */
  async function answerCatalogue(): Promise<void> {
    open(`${API}/reference/makes`).forEach((request) => request.flush(MAKES));
    open(`${API}/reference/countries`).forEach((request) =>
      request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
    await settle();
    open(`${API}/reference/makes/make-renault/models`).forEach((request) => request.flush(MODELS));
    await settle();
    open(ENGINES).forEach((request) => request.flush(CLIO_ENGINES));
    await settle();
  }

  async function visit(url: string): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'la-cote', component: LaCotePage },
          { path: 'vendre', component: Stub },
          { path: 'recherche', component: Stub },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    await answerCatalogue();
  }

  async function answer(valuation: Valuation): Promise<TestRequest> {
    const request = valuations()[0];
    request.flush(valuation);
    await settle();
    return request;
  }

  describe('an estimate', () => {
    it('asks for the car in the address, by catalogue id, without credentials', async () => {
      await visit(CLIO_URL);
      const request = await answer(ESTIMATE);

      const params = request.request.params;
      expect(params.get('modelId')).toBe('model-clio');
      expect(params.get('fuelType')).toBe('PETROL');
      expect(params.get('powerHp')).toBe('90');
      expect(params.get('year')).toBe('2022');
      expect(params.get('mileageKm')).toBe('45000');
      expect(params.get('scope')).toBe('EXACT');
      // The gearbox chooses no comparables, so it is not sent.
      expect(params.has('gearbox')).toBe(false);
      expect(request.request.headers.has('Authorization')).toBe(false);
    });

    it('shows the median and the middle half around it', async () => {
      await visit(CLIO_URL);
      await answer(ESTIMATE);

      expect(root.querySelector('app-price')?.textContent?.trim()).toBe(`10${NBSP}600${NBSP}€`);
      expect(text()).toContain(`Fourchette : 9${NBSP}700${NBSP}€ à 11${NBSP}300${NBSP}€`);
      expect(text()).toContain('La moitié des annonces comparables');
    });

    it('says what it rests on: how many, which years, which mileages, which criteria, and that these are asking prices', async () => {
      await visit(CLIO_URL);
      await answer(ESTIMATE);

      expect(text()).toContain('7 annonces comparables en ligne sur AutoX');
      expect(text()).toContain('2022 à 2024');
      expect(text()).toContain(`35${NBSP}600 à 56${NBSP}900 km`);
      expect(text()).toContain('Même motorisation (Essence, 77 à 103 ch)');
      expect(text()).toContain('prix demandés sur AutoX, pas des prix de vente');
    });

    it('fills the form from the address, the listed engine chosen', async () => {
      await visit(CLIO_URL);

      expect(field('Marque')?.value).toBe('renault');
      expect(field('Modèle')?.value).toBe('clio');
      expect(field('Motorisation')?.value).toBe('PETROL:90');
      expect(field('Kilométrage')?.value).toBe('45000');
      expect(field('Boîte')?.value).toBe('MANUAL');
      // A listed engine needs no fuel or power typed.
      expect(field('Puissance')).toBeNull();
    });

    it('leads on to selling the car and to the listings like it, both already filled in', async () => {
      await visit(CLIO_URL);
      await answer(ESTIMATE);

      const sell = new URL(link('Vendre ma voiture').href);
      expect(sell.pathname).toBe('/vendre');
      expect(Object.fromEntries(sell.searchParams)).toEqual({
        marque: 'make-renault',
        modele: 'model-clio',
        annee: '2022',
        km: '45000',
        carburant: 'PETROL',
        boite: 'MANUAL',
      });

      const search = new URL(link('Voir les annonces similaires').href);
      expect(search.pathname).toBe('/recherche');
      expect(Object.fromEntries(search.searchParams)).toEqual({
        marque: 'renault',
        modele: 'clio',
        annee_min: '2020',
        annee_max: '2024',
        km_min: '30000',
        km_max: '60000',
        carburant: 'petrol',
        boite: 'manual',
      });
    });

    it('says when an estimate was made under a loosening, and what that leaves out', async () => {
      await visit(`${CLIO_URL}&elargir=motorisations`);
      const request = await answer({ ...ESTIMATE, scope: ValuationScope.ALL_ENGINES, window: { ...WINDOW, fuelType: undefined } });

      expect(request.request.params.get('scope')).toBe('ALL_ENGINES');
      expect(text()).toContain('Estimation élargie');
      expect(text()).toContain('ne tient pas compte de la puissance ni du carburant');
    });
  });

  describe('too few comparables', () => {
    it('gives no figure, says how many there are and how many it takes', async () => {
      await visit(CLIO_URL);
      await answer(TOO_FEW);

      expect(root.querySelector('app-price')).toBeNull();
      expect(text()).toContain('Pas assez d’annonces pour estimer cette voiture');
      expect(text()).toContain('Seulement 2 annonces comparables en ligne sur AutoX');
      expect(text()).toContain('Il en faut au moins 5');
      expect(text()).toContain('Recherché : Même motorisation');
    });

    it('offers the loosenings that would give one, with their counts, and follows one when chosen', async () => {
      await visit(CLIO_URL);
      await answer(TOO_FEW);

      const widen = link('Tous les kilométrages');
      expect(widen.textContent).toContain('6 annonces');
      expect(link('Toutes les motorisations du modèle').textContent).toContain('9 annonces');

      widen.click();
      await settle();
      expect(TestBed.inject(Router).url).toContain('elargir=kilometrage');
      expect(valuations()[0].request.params.get('scope')).toBe('ANY_MILEAGE');
    });

    it('says so plainly when even a looser search would not be enough', async () => {
      await visit(CLIO_URL);
      await answer({ ...TOO_FEW, comparableCount: 0, basis: undefined, widerScopes: [] });

      expect(text()).toContain('Aucune annonce comparable en ligne sur AutoX');
      expect(text()).toContain('Même en élargissant les critères');
      expect(root.querySelector('.result__wider')).toBeNull();
    });
  });

  describe('the form', () => {
    it('explains the method before anything is asked, and asks nothing', async () => {
      await visit('/la-cote');

      expect(text()).toContain('Comment est calculée la cote');
      expect(valuations()).toHaveLength(0);
    });

    it('puts the description in the address and estimates it, by the indicator’s own rule first', async () => {
      await visit(`${CLIO_URL}&elargir=kilometrage`);
      await answer(ESTIMATE);

      await choose('Kilométrage', '52000');
      (root.querySelector('form button[type=submit]') as HTMLButtonElement).click();
      await settle();

      const url = TestBed.inject(Router).url;
      expect(url).toContain('km=52000');
      expect(url).toContain('version=TCe%2090%20Evolution');
      expect(url).not.toContain('elargir');
      expect(valuations()[0].request.params.get('mileageKm')).toBe('52000');
    });

    it('takes a fuel and a power for an engine that is not listed', async () => {
      await visit('/la-cote?marque=renault&modele=clio&annee=2022&km=45000');

      await choose('Motorisation', '__autre__');
      expect(field('Puissance')).not.toBeNull();

      (root.querySelector('form button[type=submit]') as HTMLButtonElement).click();
      await settle();
      expect(text()).toContain('Ce champ est obligatoire');

      await choose('Carburant', 'DIESEL');
      await choose('Puissance', '110');
      (root.querySelector('form button[type=submit]') as HTMLButtonElement).click();
      await settle();

      const params = valuations()[0].request.params;
      expect(params.get('fuelType')).toBe('DIESEL');
      expect(params.get('powerHp')).toBe('110');
    });

    it('says so when the address names a model the catalogue does not have', async () => {
      await visit('/la-cote?marque=renault&modele=twingo&carburant=petrol&puissance=65&annee=2015&km=90000');

      expect(valuations()).toHaveLength(0);
      expect(text()).toContain('Ce modèle n’est pas dans le catalogue AutoX');
    });
  });
});
