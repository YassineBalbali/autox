import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH, DamageFilter, FuelType, OfferType } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { CreateAlert, defaultName } from './create-alert';
import { EMPTY_QUERY, SearchQuery } from './search-query';

const BASE = 'http://api.test';
const SAVE = `${BASE}/api/v1/saved-searches`;
const SESSION = { accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 };

/** "Créer une alerte": who can, what is saved, and what everyone else is told. */
describe('CreateAlert', () => {
  let fixture: ComponentFixture<CreateAlert>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  async function render(query: SearchQuery, emailVerified: boolean | null): Promise<void> {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        {
          provide: TOKEN_STORE,
          useValue: { read: () => (emailVerified === null ? null : SESSION), write: () => undefined, clear: () => undefined },
        },
      ],
    });
    const auth = TestBed.inject(AuthService);
    auth.restore();
    if (emailVerified !== null) {
      auth.setUser({ id: 'u', email: 'moi@autox.test', emailVerified });
    }
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CreateAlert);
    fixture.componentRef.setInput('query', query);
    root = fixture.nativeElement;
    await fixture.whenStable();
  }

  const press = async () => {
    (root.querySelector('[data-action="create-alert"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    flushReference();
    await fixture.whenStable();
  };

  /** The reference data the resolver asks for, whenever it asks. */
  const flushReference = () => {
    backend
      .match((request) => request.url === `${BASE}/api/v1/reference/countries`)
      .forEach((request) => request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]));
    backend.match((request) => request.url === `${BASE}/api/v1/reference/makes`).forEach((request) => request.flush([]));
  };

  const submit = async () => {
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    flushReference();
    await fixture.whenStable();
  };

  const nameInput = () => root.querySelector('#alert-name') as HTMLInputElement;

  it('tells a visitor why they have to sign in, and comes back here', async () => {
    await render(EMPTY_QUERY, null);
    await press();

    expect(root.textContent).toContain('Une alerte vous est envoyée par e-mail : connectez-vous');
    expect(root.querySelector('[data-action="sign-in"]')?.getAttribute('href')).toContain('/connexion?suivant=');
    expect(nameInput()).toBeNull();
  });

  it('tells an account that is not verified why not, with the way to get a link, and asks nothing', async () => {
    await render(EMPTY_QUERY, false);
    await press();

    expect(root.textContent).toContain('vérifiez d’abord votre adresse');
    expect(root.querySelector('[data-action="resend"]')).not.toBeNull();
    expect(nameInput()).toBeNull();
    backend.expectNone(SAVE);
  });

  it('saves every filter with an editable name: the API’s criteria and the query string', async () => {
    const query: SearchQuery = { ...EMPTY_QUERY, fuel: [FuelType.DIESEL], priceMax: 15000, damage: DamageFilter.ALL };
    await render(query, true);
    await press();

    expect(nameInput().value).toContain('Diesel');
    nameInput().value = 'Mon alerte';
    nameInput().dispatchEvent(new Event('input'));
    await fixture.whenStable();
    await submit();

    const request = backend.expectOne(SAVE);
    expect(request.request.body.name).toBe('Mon alerte');
    expect(request.request.body.criteria).toEqual({
      fuelType: ['DIESEL'],
      priceMax: 1500000,
      currency: 'EUR',
      damage: 'ALL',
    });
    expect(request.request.body.params).toEqual({ carburant: 'diesel', prix_max: '15000', etat_vehicule: 'tous' });

    request.flush({ id: 's1' });
    await fixture.whenStable();
    expect(root.querySelector('[data-alert-saved]')?.textContent).toContain('chaque matin');
    expect(root.querySelector('[data-alert-saved] a')?.getAttribute('href')).toBe('/mes-recherches');
  });

  it('saves a leasing search as one', async () => {
    await render({ ...EMPTY_QUERY, offer: OfferType.LEASING, monthlyMax: 300 }, true);
    await press();
    await submit();

    const request = backend.expectOne(SAVE);
    expect(request.request.body.criteria).toEqual({ offer: 'LEASING', monthlyMax: 30000, currency: 'EUR' });
    expect(request.request.body.params).toEqual({ offre: 'location', mensualite_max: '300' });
  });

  it('says so at the limit, and keeps the form', async () => {
    await render(EMPTY_QUERY, true);
    await press();
    await submit();

    backend.expectOne(SAVE).flush({ code: 'SAVED_SEARCH_LIMIT_REACHED' }, { status: 409, statusText: 'Conflict' });
    await fixture.whenStable();

    expect(root.querySelector('[data-alert-failure]')?.textContent).toContain('le maximum');
    expect(nameInput()).not.toBeNull();
  });

  it('needs a name', async () => {
    await render(EMPTY_QUERY, true);
    await press();
    nameInput().value = '   ';
    nameInput().dispatchEvent(new Event('input'));
    await fixture.whenStable();

    expect((root.querySelector('[data-action="save-alert"]') as HTMLButtonElement).disabled).toBe(true);
  });

  describe('its default name', () => {
    beforeEach(() => TestBed.resetTestingModule());

    it('says what the search is for when it has no filters', () => {
      expect(defaultName(EMPTY_QUERY)).toBe('Toutes les annonces');
      expect(defaultName({ ...EMPTY_QUERY, offer: OfferType.LEASING })).toBe('Toutes les locations');
    });

    it('fits the limit', () => {
      expect(defaultName({ ...EMPTY_QUERY, text: 'x'.repeat(120) }).length).toBeLessThanOrEqual(80);
    });
  });
});
