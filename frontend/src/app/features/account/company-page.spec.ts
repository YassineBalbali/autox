import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, UserProfileResponse, UserRole } from '../../core/api';
import { TOKEN_STORE, StoredTokens, TokenStore } from '../../core/auth/token-store';
import { normaliseWebsite, websiteValidator } from '../../shared/forms/validation';
import { AccountProfileStore } from './account-profile.store';
import { logoRejection } from './company-logo';
import { CompanyPage } from './company-page';

const BASE = 'http://api.test';
const ME = `${BASE}/api/v1/me`;

class InMemoryTokenStore implements TokenStore {
  tokens: StoredTokens | null = { accessToken: 'access-1', refreshToken: 'refresh-1', accessTokenExpiresAt: Date.now() + 900_000 };
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

const DEALER: UserProfileResponse = {
  id: 'u-1',
  email: 'contact@garage-bellevue.example',
  role: UserRole.PROFESSIONNEL,
  displayName: 'Garage Bellevue',
  countryCode: 'FR',
  locale: 'fr-FR',
  emailVerified: true,
  emailVerificationPending: false,
  createdAt: '2026-03-01T09:15:00Z',
  professional: {
    companyName: 'Garage Bellevue SARL',
    registrationNumber: '81234567800012',
    slug: 'garage-bellevue',
    contactPhone: '+33472000000',
    showPhone: false,
    openingHours: 'Lundi au samedi : 9 h – 19 h',
  },
};

/** Mon compte → Entreprise: what the dealer's public page says, and the way to see it. */
describe('CompanyPage', () => {
  let fixture: ComponentFixture<CompanyPage>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  async function show(profile: UserProfileResponse = DEALER): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [CompanyPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        AccountProfileStore,
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useClass: InMemoryTokenStore },
      ],
    });
    fixture = TestBed.createComponent(CompanyPage);
    backend = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    backend.expectOne(ME).flush(profile);
    await fixture.whenStable();
    root = fixture.nativeElement;
  }

  const input = (name: string) =>
    root.querySelector(`[data-field="${name}"], app-text-field input, app-text-field textarea`) as HTMLInputElement;

  function type(label: string, value: string): void {
    const field = Array.from(root.querySelectorAll('app-text-field')).find((element) =>
      element.textContent?.includes(label),
    );
    const control = field?.querySelector('input, textarea') as HTMLInputElement;
    control.value = value;
    control.dispatchEvent(new Event('input'));
  }

  it('links to the public page, as visitors see it', async () => {
    await show();

    const preview = root.querySelector('[data-preview] a') as HTMLAnchorElement;
    expect(preview.getAttribute('href')).toBe('/professionnels/garage-bellevue');
    expect(preview.textContent?.trim()).toBe('Voir ma page publique');
    expect(root.querySelector('[data-hidden]')).toBeNull();
  });

  it('says when the page is hidden, and why', async () => {
    await show({ ...DEALER, professional: { ...DEALER.professional, hiddenAt: '2026-11-08T10:00:00Z', hiddenReason: 'Vérification' } });

    expect(root.querySelector('[data-hidden]')?.textContent).toContain('Vérification');
  });

  it('saves the opening hours, the choice to show the phone, and a website given without its scheme', async () => {
    await show();
    expect(input('showPhone').checked).toBe(false);

    (root.querySelector('[data-field="showPhone"]') as HTMLInputElement).click();
    type('Horaires', 'Mardi au samedi : 9 h – 18 h');
    type('Site web', 'www.garage-bellevue.example');
    fixture.detectChanges();
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));

    const request = backend.expectOne((r) => r.url === ME && r.method === 'PATCH');
    expect(request.request.body.professional).toMatchObject({
      showPhone: true,
      openingHours: 'Mardi au samedi : 9 h – 18 h',
      websiteUrl: 'https://www.garage-bellevue.example',
    });
  });

  it('refuses a website that is not a web address, before sending anything', async () => {
    await show();

    type('Site web', 'javascript:alert(1)');
    fixture.detectChanges();
    (root.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();

    backend.expectNone((r) => r.method === 'PATCH');
    expect(root.textContent).toContain('Saisissez une adresse web');
  });

  it('offers a logo, initials until there is one', async () => {
    await show();

    expect(root.querySelector('app-company-logo [data-initials]')?.textContent?.trim()).toBe('GB');
    expect(root.querySelector('[data-action="choose-logo"]')?.textContent?.trim()).toBe('Ajouter un logo');
    expect(root.querySelector('[data-action="remove-logo"]')).toBeNull();
  });

  it('removes the logo through the API and shows the initials again', async () => {
    await show({ ...DEALER, professional: { ...DEALER.professional, logoUrl: 'https://cdn.test/logo.jpg' } });
    expect(root.querySelector('app-company-logo img')?.getAttribute('src')).toBe('https://cdn.test/logo.jpg');

    (root.querySelector('[data-action="remove-logo"]') as HTMLButtonElement).click();
    const request = backend.expectOne(`${ME}/company/logo`);
    expect(request.request.method).toBe('DELETE');
    request.flush(DEALER);
    await fixture.whenStable();

    expect(root.querySelector('app-company-logo [data-initials]')).not.toBeNull();
  });
});

describe('company page rules', () => {
  it('takes a web address with or without its scheme, and nothing else', () => {
    const valid = (value: string) => websiteValidator()({ value } as never) === null;
    expect(valid('https://www.garage.fr/occasions')).toBe(true);
    expect(valid('www.garage.fr')).toBe(true);
    expect(valid('')).toBe(true);
    expect(valid('javascript:alert(1)')).toBe(false);
    expect(valid('ftp://garage.fr')).toBe(false);
    expect(valid('garage')).toBe(false);
    expect(normaliseWebsite('www.garage.fr')).toBe('https://www.garage.fr');
    expect(normaliseWebsite('HTTP://garage.fr')).toBe('HTTP://garage.fr');
  });

  it('refuses a logo that is not a JPEG or PNG, or too large', () => {
    expect(logoRejection(new File(['x'], 'logo.gif', { type: 'image/gif' }))).toContain('n’est pas une image JPEG ou PNG');
    expect(logoRejection(new File([new Uint8Array(11 * 1024 * 1024)], 'logo.png', { type: 'image/png' }))).toContain('10 Mo');
    expect(logoRejection(new File(['x'], 'logo.png', { type: 'image/png' }))).toBeNull();
  });
});
