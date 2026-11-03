import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { AdminUserRow, BASE_PATH, UserRole, UserStatus } from '../../core/api';
import { AdminUsersPage, USERS_PAGE_SIZE } from './admin-users-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/admin/users`;

const row = (over: Partial<AdminUserRow> = {}): AdminUserRow => ({
  id: 'u1',
  email: 'camille@exemple.fr',
  displayName: 'Camille R.',
  role: UserRole.PARTICULIER,
  status: UserStatus.PENDING_VERIFICATION,
  createdAt: '2026-10-03T10:00:00Z',
  listingCount: 3,
  demo: false,
  ...over,
});

const answer = (items: AdminUserRow[], over: object = {}) => ({
  items,
  page: 0,
  size: USERS_PAGE_SIZE,
  totalElements: items.length,
  totalPages: 1,
  hasNext: false,
  ...over,
});

describe('AdminUsersPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const words = (element: Element | null | undefined) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const select = (id: string) => root.querySelector(`#${id}`) as HTMLSelectElement;

  async function open(url = '/admin/utilisateurs'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'admin/utilisateurs', component: AdminUsersPage },
            { path: 'admin/utilisateurs/:id', component: AdminUsersPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
  }

  const request = (): TestRequest => backend.expectOne((candidate) => candidate.url === LIST);

  async function flush(items: AdminUserRow[], over: object = {}): Promise<void> {
    request().flush(answer(items, over));
    await harness.fixture.whenStable();
  }

  it('shows each account: name, e-mail, role, status, date, listings', async () => {
    await open();
    await flush([row()]);

    const cells = Array.from(root.querySelectorAll('[data-user] td')).map((cell) => words(cell));
    expect(cells).toEqual(['Camille R.', 'camille@exemple.fr', 'Particulier', 'E-mail non vérifié', '3 octobre 2026', '3']);
    expect(root.querySelector('[data-user] a')?.getAttribute('href')).toBe('/admin/utilisateurs/u1');
    expect(words(root.querySelector('[data-count]'))).toBe('1 compte');
  });

  it('marks a demo account and a suspended one', async () => {
    await open();
    await flush([row({ id: 'a', demo: true }), row({ id: 'b', status: UserStatus.SUSPENDED })]);

    const [demo, suspended] = Array.from(root.querySelectorAll('[data-user]'));
    expect(demo.querySelector('[data-demo]')).not.toBeNull();
    expect(suspended.querySelector('[data-demo]')).toBeNull();
    expect(words(suspended.querySelector('.tag--danger'))).toBe('Suspendu');
  });

  it('reads the filters and the page from the URL, in the API’s terms', async () => {
    await open('/admin/utilisateurs?q=%20Lac%20&role=PROFESSIONNEL&statut=SUSPENDED&comptes=demo&page=3');

    const { params } = request().request;
    expect(params.get('q')).toBe('Lac');
    expect(params.get('role')).toBe('PROFESSIONNEL');
    expect(params.get('status')).toBe('SUSPENDED');
    expect(params.get('demo')).toBe('true');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe(String(USERS_PAGE_SIZE));
  });

  it('can ask for real accounts only', async () => {
    await open('/admin/utilisateurs?comptes=reels');
    expect(request().request.params.get('demo')).toBe('false');
  });

  it('ignores filter values it does not know, rather than sending them', async () => {
    await open('/admin/utilisateurs?role=NOPE&statut=DELETED&comptes=autre&page=abc');

    const { params } = request().request;
    expect(params.has('role')).toBe(false);
    expect(params.has('status')).toBe(false);
    expect(params.has('demo')).toBe(false);
    expect(params.get('page')).toBe('0');
  });

  it('changes a filter in the URL and goes back to the first page', async () => {
    await open('/admin/utilisateurs?page=3');
    await flush([row()], { totalPages: 5, page: 2 });

    select('users-role').value = UserRole.ADMIN;
    select('users-role').dispatchEvent(new Event('change'));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/admin/utilisateurs?role=ADMIN');
    await flush([row({ role: UserRole.ADMIN })]);
    expect(select('users-role').value).toBe(UserRole.ADMIN);
  });

  it('searches on submit', async () => {
    await open();
    await flush([row()]);

    (root.querySelector('#users-q') as HTMLInputElement).value = '  camille ';
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await harness.fixture.whenStable();

    expect(TestBed.inject(Router).url).toBe('/admin/utilisateurs?q=camille');
    expect(request().request.params.get('q')).toBe('camille');
  });

  it('pages through the list, keeping the filters', async () => {
    await open('/admin/utilisateurs?role=ADMIN&page=2');
    await flush([row()], { totalPages: 3, page: 1 });

    const previous = root.querySelector('a[rel="prev"]')?.getAttribute('href');
    const next = root.querySelector('a[rel="next"]')?.getAttribute('href');
    expect(previous).toBe('/admin/utilisateurs?role=ADMIN');
    expect(next).toBe('/admin/utilisateurs?role=ADMIN&page=3');
    expect(words(root.querySelector('.users__pages'))).toContain('Page 2 sur 3');
  });

  it('tells an empty list from an empty search', async () => {
    await open();
    await flush([]);
    expect(words(root.querySelector('[data-empty]'))).toBe('Aucun compte.');

    await open('/admin/utilisateurs?q=personne');
    await flush([]);
    expect(words(root.querySelector('[data-empty]'))).toBe('Aucun compte ne correspond à cette recherche.');
  });

  it('says when it failed, and can try again', async () => {
    await open();
    request().flush({ code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'Server Error' });
    await harness.fixture.whenStable();
    expect(root.querySelector('app-alert')).not.toBeNull();

    (root.querySelector('.btn--quiet') as HTMLButtonElement).click();
    await flush([row()]);
    expect(root.querySelector('[data-user]')).not.toBeNull();
  });
});
