import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { AuthService } from '../../core/auth/auth.service';
import { EmailVerificationNotice } from './email-verification-notice';

const BASE = 'http://api.test';

@Component({
  imports: [EmailVerificationNotice],
  template: `<app-email-verification-notice>Vérifiez votre adresse.</app-email-verification-notice>`,
})
class Host {}

describe('EmailVerificationNotice', () => {
  let backend: HttpTestingController;
  let element: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    TestBed.inject(AuthService).setUser({ email: 'jean@exemple.fr', emailVerified: false });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    element = fixture.nativeElement;
  });

  const press = () => element.querySelector<HTMLButtonElement>('[data-action="resend"]')!.click();

  const settle = async () => {
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.tick();
  };

  it('says what the page gave it, with the way to get a new link', () => {
    expect(element.textContent).toContain('Vérifiez votre adresse.');
    expect(element.querySelector('[data-action="resend"]')?.textContent).toContain('Renvoyer le lien');
  });

  it('asks for a new link and says where it went', async () => {
    press();
    backend.expectOne(`${BASE}/api/v1/me/email/verification`).flush(null, { status: 202, statusText: 'Accepted' });
    await settle();

    expect(element.querySelector('[data-resend-sent]')?.textContent).toContain('jean@exemple.fr');
  });

  it('says so when it has been asked too often', async () => {
    press();
    backend.expectOne(`${BASE}/api/v1/me/email/verification`).flush(
      { code: 'RATE_LIMITED', retryAfterSeconds: 600 },
      { status: 429, statusText: 'Too Many Requests' },
    );
    await settle();

    expect(element.querySelector('[data-resend-failure]')?.textContent).toContain('Trop de requêtes');
    expect(element.querySelector('[data-resend-sent]')).toBeNull();
  });
});
