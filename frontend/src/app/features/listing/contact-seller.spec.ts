import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode, PublicListingDetail, UserRole, UserSummary } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE } from '../../core/auth/token-store';
import { ContactSeller } from './contact-seller';

const BASE = 'http://api.test';
const CONTACT = `${BASE}/api/v1/listings/l1/conversation`;

const SESSION: StoredTokens = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  accessTokenExpiresAt: Date.now() + 900_000,
};

const LISTING: PublicListingDetail = {
  id: 'l1',
  title: 'Peugeot 308 PureTech 130',
  seller: { id: 'seller-1', displayName: 'Garage Bellevue', role: 'PROFESSIONNEL' },
};

@Component({
  imports: [ContactSeller],
  template: `<app-contact-seller [listing]="listing()" />`,
})
class Host {
  readonly listing = signal(LISTING);
  readonly contact = viewChild.required(ContactSeller);
}

/**
 * "Contacter le vendeur". The contract: a buyer reaches the seller only through AutoX, the
 * form says what it sends and to whom, and every way it can fail is said in French.
 */
describe('ContactSeller', () => {
  let fixture: ComponentFixture<Host>;
  let backend: HttpTestingController;
  let root: HTMLElement;

  async function render(signedInAs: string | null): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        {
          provide: TOKEN_STORE,
          useValue: { read: () => (signedInAs ? SESSION : null), write: () => undefined, clear: () => undefined },
        },
      ],
    });
    const auth = TestBed.inject(AuthService);
    auth.restore();
    if (signedInAs) {
      auth.setUser({ id: signedInAs, email: 'moi@autox.test', role: UserRole.PARTICULIER, displayName: 'Moi' } as UserSummary);
    }
    backend = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Host);
    root = fixture.nativeElement;
    await fixture.whenStable();
  }

  const settle = () => fixture.whenStable();
  const button = () => root.querySelector('button.contact__button') as HTMLButtonElement;
  const textarea = () => root.querySelector('textarea') as HTMLTextAreaElement;
  const send = () => root.querySelector('button[type=submit]') as HTMLButtonElement;
  const sent = (): TestRequest => backend.expectOne(CONTACT);
  const type = async (value: string) => {
    textarea().value = value;
    textarea().dispatchEvent(new Event('input'));
    await settle();
  };

  it('sends a visitor who is not signed in to sign in, and back to the listing', async () => {
    await render(null);
    const link = root.querySelector('a.contact__button') as HTMLAnchorElement;

    expect(link.getAttribute('href')).toBe('/connexion?suivant=%2Fannonces%2Fl1');
    expect(root.textContent).toContain('ni votre adresse e-mail ni votre numéro ne lui sont communiqués');
  });

  it('tells the seller it is their own listing, and where buyers’ messages go', async () => {
    await render('seller-1');

    expect(button()).toBeNull();
    expect(root.textContent).toContain('C’est votre annonce');
    expect(root.querySelector('a[href="/messages"]')).not.toBeNull();
  });

  it('opens a form with a first line to edit, focused, and says what it is for', async () => {
    await render('buyer-1');
    button().click();
    await settle();

    expect(root.querySelector('h2')?.textContent?.trim()).toBe('Écrire à Garage Bellevue');
    expect(textarea().value).toBe(
      'Bonjour, votre annonce « Peugeot 308 PureTech 130 » m’intéresse. Est-elle toujours disponible ?',
    );
    expect(document.activeElement).toBe(textarea());
    // The warning against paying before seeing the car is where the message is written.
    expect(root.textContent).toContain('Ne payez jamais un véhicule avant de l’avoir vu');
  });

  it('sends what was typed, trimmed, and then shows the way to the conversation', async () => {
    await render('buyer-1');
    button().click();
    await settle();
    await type('  Bonjour, visible samedi ?  \n');
    send().click();

    const request = sent();
    expect(request.request.body).toEqual({ body: 'Bonjour, visible samedi ?' });
    request.flush({ id: 'c1', messages: [] });
    await settle();

    expect(root.querySelector('.alert--success')?.textContent).toContain('Message envoyé');
    expect(root.querySelector('a[href="/messages/c1"]')?.textContent?.trim()).toBe('Voir la conversation');
  });

  it('will not send an empty message', async () => {
    await render('buyer-1');
    button().click();
    await settle();
    await type('   ');

    expect(send().disabled).toBe(true);
    send().click();
    backend.expectNone(CONTACT);
  });

  it('says how long to wait when the sender is over the limit, and keeps what they wrote', async () => {
    await render('buyer-1');
    button().click();
    await settle();
    await type('Encore une question');
    send().click();

    sent().flush({ code: ErrorCode.RATE_LIMITED, retryAfterSeconds: 600 }, { status: 429, statusText: 'Too Many' });
    await settle();

    expect(root.querySelector('.alert--error')?.textContent).toContain('beaucoup de messages en peu de temps');
    expect(root.querySelector('.alert--error')?.textContent).toContain('10 minutes');
    expect(textarea().value).toBe('Encore une question');
  });

  it('says a demo seller will not answer, before and after writing, but not to the seller', async () => {
    const demo = { ...LISTING, seller: { ...LISTING.seller, demo: true } };
    const note = () => root.querySelector('.contact__demo')?.textContent ?? '';

    await render(null);
    fixture.componentInstance.listing.set(demo);
    await settle();
    expect(note()).toContain('fait partie de la démonstration et ne répondra pas');

    await render('buyer-1');
    fixture.componentInstance.listing.set(demo);
    await settle();
    button().click();
    await settle();
    expect(note()).toContain('ne répondra pas');

    await render('seller-1');
    fixture.componentInstance.listing.set(demo);
    await settle();
    expect(root.querySelector('.contact__demo')).toBeNull();
  });

  it('says nothing of the kind about a real seller', async () => {
    await render('buyer-1');

    expect(root.querySelector('.contact__demo')).toBeNull();
  });

  it('cancelling closes the form without sending anything', async () => {
    await render('buyer-1');
    button().click();
    await settle();
    (Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Annuler') as HTMLButtonElement).click();
    await settle();

    expect(root.querySelector('form')).toBeNull();
    backend.expectNone(CONTACT);
  });

  describe('engaged from elsewhere — the phone\'s bottom bar', () => {
    it('opens the form, focused, exactly as its own button does', async () => {
      await render('buyer-1');

      fixture.componentInstance.contact().engage();
      await settle();

      expect(document.activeElement).toBe(textarea());
      expect(fixture.componentInstance.contact().open()).toBe(true);
    });

    it('goes to the conversation once a message is sent', async () => {
      await render('buyer-1');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      button().click();
      await settle();
      send().click();
      sent().flush({ id: 'c1', messages: [] });
      await settle();

      fixture.componentInstance.contact().engage();

      expect(navigate).toHaveBeenCalledWith(['/messages', 'c1']);
    });

    it('sends a visitor to sign in, and back to the listing', async () => {
      await render(null);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      fixture.componentInstance.contact().engage();

      expect(navigate).toHaveBeenCalledWith(['/connexion'], { queryParams: { suivant: '/annonces/l1' } });
    });

    it('does nothing on the seller\'s own listing', async () => {
      await render('seller-1');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

      fixture.componentInstance.contact().engage();
      await settle();

      expect(navigate).not.toHaveBeenCalled();
      expect(textarea()).toBeNull();
    });
  });
});
