import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH, UserProfileResponse, UserRole } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { ProfilePage } from './profile-page';

const BASE = 'http://api.test';

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    accessTokenExpiresAt: Date.now() + 900_000,
  };
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

const PROFILE: UserProfileResponse = {
  id: 'u-1',
  email: 'jean@exemple.fr',
  role: UserRole.PARTICULIER,
  displayName: 'Jean D.',
  firstName: 'Jean',
  lastName: 'Dupont',
  phone: '+33612345678',
  countryCode: 'FR',
  locale: 'fr-FR',
  emailVerified: true,
  createdAt: '2026-03-01T09:15:00Z',
};

/**
 * The account screen.
 *
 * <p>The behaviour worth pinning down is how a blank optional field is submitted. The API
 * distinguishes an absent member ("leave unchanged") from a blank string ("clear it"), and
 * a form that sent `undefined` for an emptied field would silently refuse to let a user
 * delete their own phone number — a bug with no error message anywhere.
 */
describe('ProfilePage', () => {
  let fixture: ComponentFixture<ProfilePage>;
  let backend: HttpTestingController;
  let auth: AuthService;

  async function render(profile: UserProfileResponse = PROFILE): Promise<void> {
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

    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    auth.restore();

    fixture = TestBed.createComponent(ProfilePage);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/me`).flush(profile);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await render();
  });

  function byLabel(text: string): HTMLInputElement {
    const label = (
      Array.from(fixture.nativeElement.querySelectorAll('label')) as HTMLLabelElement[]
    ).find((candidate) => candidate.textContent?.includes(text));
    if (!label) {
      throw new Error(`No field labelled "${text}".`);
    }
    return fixture.nativeElement.querySelector(`#${label.getAttribute('for')}`);
  }

  async function type(label: string, value: string): Promise<void> {
    const element = byLabel(label);
    element.value = value;
    element.dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  const save = async () => {
    (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
  };

  it('fills the form from the profile', () => {
    expect(byLabel('Nom affiché').value).toBe('Jean D.');
    expect(byLabel('Prénom').value).toBe('Jean');
    expect(byLabel('Téléphone').value).toBe('+33612345678');
  });

  it('shows what cannot be changed here, and says why', () => {
    expect(fixture.nativeElement.textContent).toContain('jean@exemple.fr');
    expect(fixture.nativeElement.textContent).toContain('Particulier');
    // Changing an email has to re-trigger verification, which is why the API keeps it off a
    // profile PATCH. Showing the field as uneditable with no reason invites a support
    // ticket per user.
    expect(fixture.nativeElement.textContent).toContain('nouvelle vérification');
  });

  it('renders the creation date in French', () => {
    expect(fixture.nativeElement.textContent).toContain('mars 2026');
  });

  it('sends a blank string for a field the user emptied', async () => {
    await type('Téléphone', '');
    await save();

    // "" means clear; omitting the member means leave unchanged. Sent as undefined, the
    // user's phone number would still be there after they deleted it and saved.
    expect(backend.expectOne(`${BASE}/api/v1/me`).request.body).toEqual({
      displayName: 'Jean D.',
      firstName: 'Jean',
      lastName: 'Dupont',
      phone: '',
    });
  });

  it('trims what it sends', async () => {
    await type('Nom affiché', '  Jean Dupont  ');
    await save();

    expect(backend.expectOne(`${BASE}/api/v1/me`).request.body.displayName).toBe('Jean Dupont');
  });

  it('refuses a blank display name without calling the API', async () => {
    // The API rejects it too, with `@Size(min = 1)` — but its message is untranslated
    // developer text, so the only place the user can be told what is wrong is here.
    await type('Nom affiché', '   ');
    await save();

    backend.expectNone(`${BASE}/api/v1/me`);
    expect(fixture.nativeElement.textContent).toContain('Ce champ est obligatoire.');
  });

  it('refuses a phone number in the national format, and says what is wanted', async () => {
    // The mistake a French user makes first. The API's rule is international format, which
    // is not a French-market preference: 0612345678 is ambiguous once a second country
    // opens.
    await type('Téléphone', '0612345678');
    await save();

    backend.expectNone(`${BASE}/api/v1/me`);
    expect(fixture.nativeElement.textContent).toContain('+33612345678');
  });

  it('confirms a save, and updates the session the header reads', async () => {
    await type('Nom affiché', 'Jean Dupont');
    await save();

    backend
      .expectOne(`${BASE}/api/v1/me`)
      .flush({ ...PROFILE, displayName: 'Jean Dupont' });
    await fixture.whenStable();

    expect(
      (fixture.nativeElement.querySelector('.alert--success') as HTMLElement).textContent,
    ).toContain('enregistrées');
    expect(auth.user()?.displayName).toBe('Jean Dupont');
  });

  it('drops the confirmation as soon as the user edits again', async () => {
    await type('Nom affiché', 'Jean Dupont');
    await save();
    backend.expectOne(`${BASE}/api/v1/me`).flush({ ...PROFILE, displayName: 'Jean Dupont' });
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.alert--success')).not.toBeNull();

    await type('Prénom', 'Jeanne');

    // Otherwise "enregistré" sits above a form holding unsaved changes.
    expect(fixture.nativeElement.querySelector('.alert--success')).toBeNull();
  });

  it('offers the company screen to a dealer only', async () => {
    expect(fixture.nativeElement.textContent).not.toContain('Informations de l’entreprise');

    await render({ ...PROFILE, role: UserRole.PROFESSIONNEL });
    expect(fixture.nativeElement.textContent).toContain('Informations de l’entreprise');
  });

  it('offers a retry when the profile cannot be loaded', async () => {
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
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProfilePage);
    await fixture.whenStable();

    backend.expectOne(`${BASE}/api/v1/me`).error(new ProgressEvent('offline'));
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('Vérifiez votre connexion');

    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    backend.expectOne(`${BASE}/api/v1/me`).flush(PROFILE);
    await fixture.whenStable();

    expect(byLabel('Nom affiché').value).toBe('Jean D.');
  });
});
