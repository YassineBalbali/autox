import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH, ErrorCode } from '../../core/api';
import { TOKEN_STORE } from '../../core/auth/token-store';
import { MyDataPage, exportFileName } from './my-data-page';

const BASE = 'http://api.test';

@Component({ template: '<p>supprimé</p>' })
class Deleted {}

/** Mes données: the export, and deleting the account. */
describe('MyDataPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;
  const cleared = vi.fn();
  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  beforeEach(async () => {
    cleared.mockReset();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          { path: 'mon-compte/donnees', component: MyDataPage },
          { path: 'compte-supprime', component: Deleted },
        ]),
        { provide: BASE_PATH, useValue: BASE },
        {
          provide: TOKEN_STORE,
          useValue: {
            read: () => ({ accessToken: 'a', refreshToken: 'r', accessTokenExpiresAt: Date.now() + 900_000 }),
            write: () => undefined,
            clear: cleared,
          },
        },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/mon-compte/donnees');
    root = harness.fixture.nativeElement;
    await settle();
  });

  afterEach(() => vi.restoreAllMocks());

  describe('the export', () => {
    it('downloads the account’s data as a dated JSON file', async () => {
      const created: Blob[] = [];
      const urls = URL as unknown as { createObjectURL?: unknown; revokeObjectURL?: unknown };
      urls.createObjectURL = (blob: Blob) => {
        created.push(blob);
        return 'blob:export';
      };
      urls.revokeObjectURL = () => undefined;
      const clicked: string[] = [];
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push(this.download);
      });

      (root.querySelector('[data-action="export"]') as HTMLButtonElement).click();
      await settle();
      open(`${BASE}/api/v1/me/export`)[0].flush({ account: { email: 'moi@autox.test' }, listings: [] });
      await settle();

      expect(clicked).toEqual([exportFileName()]);
      expect(clicked[0]).toMatch(/^autox-mes-donnees-\d{4}-\d{2}-\d{2}\.json$/);
      expect(JSON.parse(await created[0].text())).toEqual({ account: { email: 'moi@autox.test' }, listings: [] });
      expect(root.textContent).toContain('Fichier téléchargé.');
    });

    it('says when the limit is reached', async () => {
      (root.querySelector('[data-action="export"]') as HTMLButtonElement).click();
      await settle();
      open(`${BASE}/api/v1/me/export`)[0].flush(
        { code: ErrorCode.RATE_LIMITED, status: 429, retryAfterSeconds: 600 },
        { status: 429, statusText: 'Too Many Requests' },
      );
      await settle();

      expect(root.querySelector('app-alert')?.textContent?.trim()).toBeTruthy();
    });
  });

  describe('deleting the account', () => {
    const submit = async () => {
      (root.querySelector('[data-action="delete-account"]') as HTMLButtonElement).click();
      await settle();
    };
    const typePassword = async (value: string) => {
      const input = root.querySelector('input[type="password"]') as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      await settle();
    };

    it('says what will be erased and what will stay before anything is asked', () => {
      const summary = root.querySelector('[data-summary]')?.textContent?.replace(/\s+/g, ' ');

      expect(summary).toContain('vos annonces et leurs photos');
      expect(summary).toContain('vos recherches enregistrées');
      expect(summary).toContain('« Compte supprimé », sans leur texte');
      expect(summary).toContain('e-mail de confirmation');
    });

    it('needs the password: without it, nothing is sent', async () => {
      await submit();

      expect(open(`${BASE}/api/v1/me/deletion`)).toHaveLength(0);
    });

    it('sends the password, then ends the session here and says the account is gone', async () => {
      await typePassword('correct-horse-battery');
      await submit();

      const request = open(`${BASE}/api/v1/me/deletion`)[0];
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ password: 'correct-horse-battery' });
      request.flush(null, { status: 204, statusText: 'No Content' });
      await settle();

      expect(cleared).toHaveBeenCalled();
      expect(TestBed.inject(Router).url).toBe('/compte-supprime');
    });

    it('stays, and says why, when the server refuses: the last administrator', async () => {
      await typePassword('correct-horse-battery');
      await submit();

      open(`${BASE}/api/v1/me/deletion`)[0].flush(
        { code: ErrorCode.LAST_ADMIN, status: 409 },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();

      expect(root.textContent).toContain('dernier administrateur');
      expect(TestBed.inject(Router).url).toBe('/mon-compte/donnees');
      expect(cleared).not.toHaveBeenCalled();
    });
  });
});
