import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import {
  AdminUserActionType,
  AdminUserDetail,
  BASE_PATH,
  ListingStatus,
  ModerationActionType,
  ModerationStatus,
  UserRole,
  UserStatus,
} from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { AdminUserDetailPage, minutesFr } from './admin-user-detail-page';

const BASE = 'http://api.test';
const USER = `${BASE}/api/v1/admin/users/u1`;

const account = (over: Partial<AdminUserDetail> = {}): AdminUserDetail => ({
  id: 'u1',
  email: 'camille@exemple.fr',
  displayName: 'Camille R.',
  phone: '+33612345678',
  role: UserRole.PARTICULIER,
  status: UserStatus.PENDING_VERIFICATION,
  countryCode: 'FR',
  createdAt: '2026-10-03T10:00:00Z',
  demo: false,
  activity: {
    listings: 2,
    listingsOnline: 1,
    conversations: 4,
    messagesSent: 5,
    reportsFiled: 0,
    reportsReceived: 1,
    activeSessions: 2,
  },
  listings: [
    {
      id: 'l1',
      title: 'Audi A3',
      status: ListingStatus.ACTIVE,
      moderationStatus: ModerationStatus.APPROVED,
      priceAmount: 1_500_000,
      currencyCode: 'EUR',
      createdAt: '2026-10-04T10:00:00Z',
      online: true,
    },
    {
      id: 'l2',
      title: 'Peugeot 308',
      status: ListingStatus.DRAFT,
      moderationStatus: ModerationStatus.NOT_REVIEWED,
      priceAmount: 900_000,
      currencyCode: 'EUR',
      createdAt: '2026-10-05T10:00:00Z',
      online: false,
    },
  ],
  moderationHistory: [
    {
      id: 'm1',
      listingId: 'l1',
      listingTitle: 'Audi A3',
      action: ModerationActionType.APPROVE,
      adminName: 'Modérateur',
      createdAt: '2026-10-06T10:00:00Z',
    },
  ],
  actions: [],
  accessTokenTtlSeconds: 900,
  ...over,
});

const suspended = () =>
  account({
    status: UserStatus.SUSPENDED,
    actions: [
      {
        id: 'a1',
        action: AdminUserActionType.SUSPEND,
        adminEmail: 'admin@autox.test',
        reason: 'Annonces frauduleuses',
        previousValue: UserStatus.PENDING_VERIFICATION,
        newValue: UserStatus.SUSPENDED,
        createdAt: '2026-10-07T10:00:00Z',
      },
    ],
  });

describe('AdminUserDetailPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const words = (element: Element | null | undefined) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const button = (action: string) => root.querySelector(`[data-action="${action}"]`) as HTMLButtonElement;
  const stable = () => harness.fixture.whenStable();

  async function open(user: AdminUserDetail = account(), viewer = 'admin-1'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'admin/utilisateurs', children: [] },
            { path: 'annonces/:id', children: [] },
            { path: 'admin/utilisateurs/:id', component: AdminUserDetailPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    TestBed.inject(AuthService).setUser({ id: viewer, email: 'admin@autox.test', role: UserRole.ADMIN });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/admin/utilisateurs/u1');
    root = harness.routeNativeElement as HTMLElement;
    backend.expectOne(USER).flush(user);
    backend.match((request) => request.url.includes('/reference/')).forEach((request) => request.flush([]));
    await stable();
  }

  async function type(text: string): Promise<void> {
    const area = root.querySelector('textarea') as HTMLTextAreaElement;
    area.value = text;
    area.dispatchEvent(new Event('input'));
    await stable();
  }

  async function submit(action: string): Promise<void> {
    button(action).click();
    await stable();
  }

  it('shows the account, its contact details and its activity', async () => {
    await open();

    expect(words(root.querySelector('h1'))).toBe('Camille R.');
    expect(words(root.querySelector('[data-tag="role"]'))).toBe('Particulier');
    expect(words(root.querySelector('[data-tag="status"]'))).toBe('E-mail non vérifié');
    expect(words(root.querySelector('[data-field="email"]'))).toContain('camille@exemple.fr');
    expect(words(root.querySelector('[data-field="phone"]'))).toBe('+33612345678');
    const activity = Array.from(root.querySelectorAll('[data-table="activity"] tr')).map((row) =>
      Array.from(row.children).map((cell) => words(cell)),
    );
    expect(activity).toContainEqual(['Messages envoyés', '5']);
    expect(activity).toContainEqual(['Sessions ouvertes', '2']);
  });

  it('links only the listings a visitor can open', async () => {
    await open();

    const [online, draft] = Array.from(root.querySelectorAll('[data-listing]'));
    expect(online.querySelector('a')?.getAttribute('href')).toBe('/annonces/l1');
    expect(draft.querySelector('a')).toBeNull();
    expect(words(draft)).toContain('Brouillon');
  });

  it('shows the moderation history of its listings', async () => {
    await open();
    const line = words(root.querySelector('[data-history]'));
    expect(line).toContain('Audi A3');
    expect(line).toContain('Modérateur');
  });

  it('has an empty state for each list', async () => {
    await open(account({ listings: [], moderationHistory: [], actions: [] }));

    expect(words(root.querySelector('[data-empty="listings"]'))).toBe('Aucune annonce.');
    expect(words(root.querySelector('[data-empty="moderation"]'))).toBe('Aucune décision de modération.');
    expect(words(root.querySelector('[data-empty="actions"]'))).toBe('Aucune action.');
  });

  it('says a closed or unknown account is not there', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'admin/utilisateurs/:id', component: AdminUserDetailPage }], withComponentInputBinding()),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => null, write: () => undefined, clear: () => undefined } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/admin/utilisateurs/u1');
    root = harness.routeNativeElement as HTMLElement;
    backend.expectOne(USER).flush({ code: 'NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await stable();

    expect(root.querySelector('[data-state="missing"]')).not.toBeNull();
  });

  describe('suspending', () => {
    it('explains what it does and how long a token lasts, and needs a reason', async () => {
      await open();
      await submit('suspend');

      expect(words(root.querySelector('[data-section="actions"]'))).toContain('rien n’est supprimé');
      expect(words(root.querySelector('[data-token-lifetime]'))).toContain('au plus 15 minutes');

      await submit('confirm-suspend');
      backend.expectNone(`${USER}/suspend`);
      expect(root.querySelector('.field__error')).not.toBeNull();

      await type('   ');
      await submit('confirm-suspend');
      backend.expectNone(`${USER}/suspend`);
    });

    it('sends the reason, and shows what happened', async () => {
      await open();
      await submit('suspend');
      await type('  Annonces frauduleuses ');
      await submit('confirm-suspend');

      const request = backend.expectOne(`${USER}/suspend`);
      expect(request.request.body).toEqual({ reason: 'Annonces frauduleuses' });
      request.flush({ user: suspended(), revokedSessions: 2, accessTokenTtlSeconds: 900 });
      await stable();

      const notice = words(root.querySelector('app-alert[kind="success"]') ?? root.querySelector('app-alert'));
      expect(notice).toContain('2 sessions fermées');
      expect(notice).toContain('au plus 15 minutes');
      expect(words(root.querySelector('[data-tag="status"]'))).toBe('Suspendu');
      expect(button('reactivate')).not.toBeNull();
      expect(button('suspend')).toBeNull();
    });

    it('can be cancelled, and sends nothing', async () => {
      await open();
      await submit('suspend');
      (root.querySelector('[data-section="actions"] .btn--quiet') as HTMLButtonElement).click();
      await stable();

      expect(button('suspend')).not.toBeNull();
      backend.expectNone(`${USER}/suspend`);
    });

    it('shows the API’s refusal in French and stays on the form', async () => {
      await open();
      await submit('suspend');
      await type('Motif');
      await submit('confirm-suspend');

      backend
        .expectOne(`${USER}/suspend`)
        .flush({ code: 'LAST_ADMIN', detail: 'x' }, { status: 409, statusText: 'Conflict' });
      await stable();

      expect(words(root.querySelector('[data-failure]'))).toContain('dernier administrateur');
      expect(button('confirm-suspend')).not.toBeNull();
    });
  });

  describe('when suspended', () => {
    it('says why, by whom, and that its online listings are hidden', async () => {
      await open(suspended());

      const notice = words(root.querySelector('[data-suspension]'));
      expect(notice).toContain('Annonces frauduleuses');
      expect(notice).toContain('admin@autox.test');
      expect(words(root.querySelector('[data-listing]'))).toContain('Masquée (compte suspendu)');
      expect(words(root.querySelector('[data-action-row]'))).toContain('E-mail non vérifié → Suspendu');
    });

    it('reactivates after a confirmation', async () => {
      await open(suspended());
      await submit('reactivate');
      backend.expectNone(`${USER}/reactivate`);

      await submit('confirm-reactivate');
      const request = backend.expectOne(`${USER}/reactivate`);
      expect(request.request.body).toEqual({});
      request.flush({ user: account(), revokedSessions: 0, accessTokenTtlSeconds: 900 });
      await stable();

      expect(words(root.querySelector('[data-tag="status"]'))).toBe('E-mail non vérifié');
      expect(button('suspend')).not.toBeNull();
    });
  });

  describe('changing a role', () => {
    const options = () => Array.from(root.querySelectorAll('select option')).map((option) => words(option));

    async function choose(role: UserRole): Promise<void> {
      await submit('change-role');
      const field = root.querySelector('select') as HTMLSelectElement;
      field.value = role;
      field.dispatchEvent(new Event('change'));
      await stable();
      await submit('choose-role');
    }

    it('offers the other roles, and asks before doing anything', async () => {
      await open();
      await submit('change-role');
      expect(options()).toContain('Administrateur');
      expect(options()).toContain('Professionnel');
      expect(options()).not.toContain('Particulier');
      (root.querySelector('[data-section="actions"] .btn--quiet') as HTMLButtonElement).click();
      await stable();

      await choose(UserRole.ADMIN);

      backend.expectNone(`${USER}/role`);
      expect(words(root.querySelector('[data-role-question]'))).toBe('Passer ce compte de Particulier à Administrateur ?');
    });

    it('needs a role to be chosen', async () => {
      await open();
      await submit('change-role');
      await submit('choose-role');

      expect(button('confirm-role')).toBeNull();
      expect(root.querySelector('.field__error')).not.toBeNull();
    });

    it('sends the role once confirmed, and shows the new one', async () => {
      await open();
      await choose(UserRole.PROFESSIONNEL);
      await submit('confirm-role');

      const request = backend.expectOne(`${USER}/role`);
      expect(request.request.body).toEqual({ role: UserRole.PROFESSIONNEL });
      request.flush({ user: account({ role: UserRole.PROFESSIONNEL }), revokedSessions: 0, accessTokenTtlSeconds: 900 });
      await stable();

      expect(words(root.querySelector('[data-tag="role"]'))).toBe('Professionnel');
      expect(words(root.querySelector('app-alert'))).toContain('Rôle modifié : Professionnel');
    });

    it('does not offer ADMIN for a demo account, and marks it', async () => {
      await open(account({ demo: true }));
      await submit('change-role');

      expect(options()).not.toContain('Administrateur');
      expect(words(root.querySelector('[data-tag="demo"]'))).toBe('Compte de démonstration');
    });
  });

  it('spares an administrator the buttons on their own account', async () => {
    await open(account({ role: UserRole.ADMIN }), 'u1');

    expect(root.querySelector('[data-self]')).not.toBeNull();
    expect(button('suspend')).toBeNull();
    expect(button('change-role')).toBeNull();
  });
});

describe('minutesFr', () => {
  it('says minutes, rounding up rather than promising less than the truth', () => {
    expect(minutesFr(900)).toBe('15 minutes');
    expect(minutesFr(60)).toBe('1 minute');
    expect(minutesFr(61)).toBe('2 minutes');
  });
});
