import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode, UserRole } from '../../core/api';
import { ACCOUNT_ROUTE } from '../../core/auth/auth.routes';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { RegisterPage } from './register-page';

const BASE = 'http://api.test';

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = null;
  read() {
    return this.tokens;
  }
  write(tokens: StoredTokens) {
    this.tokens = tokens;
  }
  clear() {
    this.tokens = null;
  }
}

/**
 * Registration, and the dealer branch in particular.
 *
 * <p>The branch is the part with teeth. Two fields appear, two sets of validators are added
 * and removed, and the payload has to carry both for a dealer and neither for a private
 * seller. Getting the removal wrong is the subtle failure: a user who looks at
 * "professionnel", changes their mind, and then cannot submit a form whose invalid fields
 * are no longer on screen.
 */
describe('RegisterPage', () => {
  let fixture: ComponentFixture<RegisterPage>;
  let backend: HttpTestingController;
  let store: InMemoryTokenStore;
  let router: Router;

  beforeEach(async () => {
    store = new InMemoryTokenStore();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: store },
      ],
    });

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RegisterPage);
    await fixture.whenStable();

    // The country select is populated from the reference endpoint. Answered here so the
    // form is in the state a user would actually see.
    backend
      .expectOne(`${BASE}/api/v1/reference/countries`)
      .flush([{ code: 'FR', name: 'France' }, { code: 'DE', name: 'Deutschland' }]);
    await fixture.whenStable();
  });

  // --- helpers ------------------------------------------------------------

  /** Fields are addressed by their label, which is the only stable handle a user has. */
  function byLabel(text: string): HTMLInputElement | HTMLSelectElement {
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('label'),
    ) as HTMLLabelElement[];
    const label = labels.find((candidate) => candidate.textContent?.includes(text));
    if (!label) {
      throw new Error(`No field labelled "${text}". Present: ${labels.map((l) => l.textContent?.trim()).join(' | ')}`);
    }
    return fixture.nativeElement.querySelector(`#${label.getAttribute('for')}`);
  }

  async function type(label: string, value: string): Promise<void> {
    const element = byLabel(label);
    element.value = value;
    element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input'));
    await fixture.whenStable();
  }

  /**
   * Picks an account type by the words next to it, the way a user does.
   *
   * <p>Not by the input's `value`: Angular's radio value accessor keeps the bound value in
   * the directive and never writes it to the DOM, so every radio on the page reports
   * `value === "on"`. Addressing them by label is both the only thing that works and the
   * thing that would still work if the enum values changed.
   */
  async function chooseRole(role: UserRole): Promise<void> {
    const text = role === UserRole.PROFESSIONNEL ? 'Professionnel' : 'Particulier';
    const option = (
      Array.from(fixture.nativeElement.querySelectorAll('.choice__option')) as HTMLElement[]
    ).find((candidate) => candidate.textContent?.includes(text));
    if (!option) {
      throw new Error(`No account type option labelled "${text}".`);
    }
    (option.querySelector('input[type="radio"]') as HTMLInputElement).click();
    await fixture.whenStable();
  }

  async function fillCommonFields(): Promise<void> {
    await type('Nom affiché', 'Jean Dupont');
    await type('Adresse e-mail', 'jean@exemple.fr');
    await type('Mot de passe', 'correct-horse-battery');
    await type('Confirmer le mot de passe', 'correct-horse-battery');
  }

  const submit = async () => {
    (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
  };

  const errorCount = () => fixture.nativeElement.querySelectorAll('.field__error').length;

  // --- the private seller path -------------------------------------------

  it('registers a private seller without asking for company details', async () => {
    await fillCommonFields();
    await submit();

    const request = backend.expectOne(`${BASE}/api/v1/auth/register`);
    expect(request.request.body).toMatchObject({
      email: 'jean@exemple.fr',
      role: UserRole.PARTICULIER,
      displayName: 'Jean Dupont',
      countryCode: 'FR',
    });
    // Not sent as empty strings: the API reads a blank optional field as "clear it", and a
    // blank here would be a cleared field on an account that never had a value.
    expect(request.request.body.companyName).toBeUndefined();
    expect(request.request.body.registrationNumber).toBeUndefined();
    expect(request.request.body.firstName).toBeUndefined();
  });

  it('does not show the company fields to a private seller', () => {
    expect(() => byLabel('Raison sociale')).toThrow();
    expect(() => byLabel('Numéro SIRET')).toThrow();
  });

  // --- the dealer path ----------------------------------------------------

  describe('as a dealer', () => {
    beforeEach(async () => {
      await chooseRole(UserRole.PROFESSIONNEL);
    });

    it('asks for the company name and the registry number', () => {
      expect(byLabel('Raison sociale')).toBeTruthy();
      expect(byLabel('Numéro SIRET')).toBeTruthy();
    });

    it('refuses to submit without them', async () => {
      await fillCommonFields();
      await submit();

      backend.expectNone(`${BASE}/api/v1/auth/register`);
      // Both marked, so the user is not left hunting for which one is missing.
      expect(errorCount()).toBe(2);
    });

    it('sends both, so the account is verifiable from the moment it exists', async () => {
      await fillCommonFields();
      await type('Raison sociale', 'Garage Bellevue');
      await type('Numéro SIRET', '81234567800012');
      await submit();

      expect(backend.expectOne(`${BASE}/api/v1/auth/register`).request.body).toMatchObject({
        role: UserRole.PROFESSIONNEL,
        companyName: 'Garage Bellevue',
        registrationNumber: '81234567800012',
      });
    });

    it('stops requiring them when the user changes back to a private account', async () => {
      // The subtle one. If the validators are not cleared, the form stays invalid because
      // of two fields that are no longer rendered — a submit button that does nothing, for
      // a reason the user cannot see.
      await fillCommonFields();
      await chooseRole(UserRole.PARTICULIER);
      await submit();

      expect(backend.expectOne(`${BASE}/api/v1/auth/register`).request.body.role).toBe(
        UserRole.PARTICULIER,
      );
    });

    it('leaves no stale error behind after switching back and forth', async () => {
      await fillCommonFields();
      await submit();
      expect(errorCount()).toBe(2);

      await chooseRole(UserRole.PARTICULIER);
      expect(errorCount()).toBe(0);

      await chooseRole(UserRole.PROFESSIONNEL);
      // Untouched again, so the fields are blank and quiet rather than pre-marked in red
      // before the user has had a chance to fill them in.
      expect(errorCount()).toBe(0);
    });
  });

  // --- outcomes -----------------------------------------------------------

  it('goes to the account, since registration already signed the user in', async () => {
    await fillCommonFields();
    await submit();

    backend.expectOne(`${BASE}/api/v1/auth/register`).flush({
      accessToken: 'access-1',
      refreshToken: 'refresh-1',
      expiresInSeconds: 900,
      user: { id: 'u-1', email: 'jean@exemple.fr', role: UserRole.PARTICULIER },
    });
    await fixture.whenStable();

    expect(store.tokens?.accessToken).toBe('access-1');
    expect(router.navigateByUrl).toHaveBeenCalledWith(ACCOUNT_ROUTE);
  });

  it('marks the field the API rejected', async () => {
    await fillCommonFields();
    await submit();

    backend.expectOne(`${BASE}/api/v1/auth/register`).flush(
      {
        code: ErrorCode.VALIDATION_FAILED,
        errors: [{ field: 'email', message: 'must be a well-formed email address' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await fixture.whenStable();

    const message = fixture.nativeElement.querySelector('.field__error') as HTMLElement;
    // "Refused" and no more: the API names the field but not the constraint, and its text is
    // untranslated developer wording that cannot be shown.
    expect(message.textContent).toContain('refusée');
  });

  it('offers the markets the API reports, not a hard-coded France', async () => {
    // The country decides the currency and the distance unit of every listing the account
    // will ever create, and it is written once, here.
    const options = Array.from(
      fixture.nativeElement.querySelectorAll('select option'),
    ) as HTMLOptionElement[];

    expect(options.map((option) => option.value)).toEqual(['FR', 'DE']);
  });

  it('still registers when the reference endpoint is unreachable', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: new InMemoryTokenStore() },
      ],
    });
    const freshBackend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RegisterPage);
    await fixture.whenStable();

    freshBackend
      .expectOne(`${BASE}/api/v1/reference/countries`)
      .error(new ProgressEvent('offline'));
    await fixture.whenStable();

    // Falls back to France alone rather than blocking registration behind a convenience
    // lookup. The API validates the code regardless.
    const options = Array.from(
      fixture.nativeElement.querySelectorAll('select option'),
    ) as HTMLOptionElement[];
    expect(options.map((option) => option.value)).toEqual(['FR']);
  });
});
