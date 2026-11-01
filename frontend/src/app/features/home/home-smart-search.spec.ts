import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { HomeSmartSearch } from './home-smart-search';

const BASE = 'http://api.test';
const CAPABILITIES = `${BASE}/api/v1/search/capabilities`;
const INTERPRET = `${BASE}/api/v1/search/interpret`;

@Component({ template: '<p>résultats</p>' })
class ResultsStub {}

/**
 * "Recherche intelligente" on the home page, through a real router and a fake backend: hidden
 * unless the server has it, and a description that becomes an ordinary search link — or, when
 * the server cannot interpret it, the ordinary text search.
 */
describe('HomeSmartSearch', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const settle = () => harness.fixture.whenStable();
  const words = (selector: string) => root.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  async function visit(interpret: boolean | 'fail'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: '', pathMatch: 'full', component: HomeSmartSearch },
          { path: 'recherche', component: ResultsStub },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/');
    root = harness.routeNativeElement as HTMLElement;
    await settle();
    for (const request of open(CAPABILITIES)) {
      if (interpret === 'fail') {
        request.flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
      } else {
        request.flush({ interpret });
      }
    }
    await settle();
  }

  async function describeCar(text: string): Promise<void> {
    const field = root.querySelector('#smart-description') as HTMLInputElement;
    field.value = text;
    (root.querySelector('.smart__submit') as HTMLButtonElement).click();
    await settle();
  }

  it('is absent when the server has no provider, or cannot say', async () => {
    await visit(false);
    expect(root.querySelector('.smart')).toBeNull();

    await visit('fail');
    expect(root.querySelector('.smart')).toBeNull();
  });

  it('offers a field and a button when the server has it', async () => {
    await visit(true);

    expect(words('.smart__title')).toBe('Recherche intelligente');
    const field = root.querySelector('#smart-description') as HTMLInputElement;
    expect(field.placeholder).toBe('Décrivez la voiture que vous cherchez');
    expect(field.maxLength).toBe(200);
  });

  it('sends the description, shows it is working, then opens the results with the filters applied', async () => {
    await visit(true);
    await describeCar('  SUV diesel   automatique moins de 15 000 € en bon état ');

    const [request] = open(INTERPRET);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ query: 'SUV diesel automatique moins de 15 000 € en bon état' });
    expect(words('.smart__submit')).toBe('Analyse…');
    expect((root.querySelector('.smart__submit') as HTMLButtonElement).disabled).toBe(true);

    request.flush({
      criteria: { bodyType: ['SUV'], fuelType: ['DIESEL'], gearbox: ['AUTOMATIC'], priceMax: 1500000, currency: 'EUR' },
      summary: 'SUV / 4x4 · Diesel · Automatique · ≤ 15 000 €',
      notUnderstood: ['en bon état'],
    });
    await settle();
    open(`${BASE}/api/v1/reference/makes`).forEach((pending) => pending.flush([]));
    open(`${BASE}/api/v1/reference/countries`).forEach((pending) =>
      pending.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
    await settle();

    expect(TestBed.inject(Router).url).toBe('/recherche?prix_max=15000&carburant=diesel&boite=automatic&carrosserie=suv');
    expect(TestBed.inject(Location).getState()).toMatchObject({
      interpretation: { text: 'SUV diesel automatique moins de 15 000 € en bon état', notUnderstood: ['en bon état'] },
    });
  });

  it('on failure says so, and offers the same words as an ordinary search', async () => {
    await visit(true);
    await describeCar('clio automatique pas chère');

    open(INTERPRET)[0].flush(
      { code: 'INTERPRETER_UNAVAILABLE', status: 503 },
      { status: 503, statusText: 'Service Unavailable' },
    );
    await settle();

    expect(words('.smart__error')).toContain('La recherche intelligente est indisponible pour le moment.');
    expect((root.querySelector('.smart__submit') as HTMLButtonElement).disabled).toBe(false);

    (root.querySelector('.smart__fallback') as HTMLButtonElement).click();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/recherche?q=clio%20automatique%20pas%20ch%C3%A8re');
  });

  it('ignores an empty description', async () => {
    await visit(true);
    await describeCar('   ');

    expect(open(INTERPRET)).toHaveLength(0);
  });
});
