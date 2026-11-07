import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH } from '../../core/api';
import { ADMIN_NAV, AdminLayout } from './admin-layout';

@Component({ template: 'page' })
class Page {}

const BASE = 'http://api.test';
const QUEUE = `${BASE}/api/v1/admin/listings`;
const REPORTS = `${BASE}/api/v1/admin/message-reports`;

const total = (totalElements: number) => ({ items: [], page: 0, size: 1, totalElements, totalPages: 1, hasNext: false });

/** The frame, through a real router: the sidebar, the tab bar and its badges. */
describe('AdminLayout', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  /** Opens a URL and answers the two counts, unless told not to. */
  async function at(url: string, counts: { listings: number; reports: number } | null = { listings: 0, reports: 0 }) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([
          {
            path: 'admin',
            component: AdminLayout,
            children: [
              { path: '', pathMatch: 'full', component: Page },
              { path: 'moderation', component: Page },
              { path: 'moderation/messages', component: Page },
              { path: 'utilisateurs', component: Page },
              { path: 'utilisateurs/:id', component: Page },
            ],
          },
        ]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    if (counts) {
      await answer(counts.listings, counts.reports);
    }
  }

  async function answer(listings: number, reports: number) {
    open(QUEUE).forEach((request) => request.flush(total(listings)));
    open(REPORTS).forEach((request) => request.flush(total(reports)));
    await settle();
  }

  const hrefs = (selector: string) => Array.from(root.querySelectorAll(selector)).map((link) => link.getAttribute('href'));
  const tab = (href: string) => root.querySelector(`.admin__tab[href="${href}"]`) as HTMLElement;
  const badge = (href: string) => tab(href).querySelector('.admin__badge')?.textContent?.trim() ?? null;

  it('lists the four pages in the sidebar and in the tab bar, with the page beside them', async () => {
    await at('/admin');

    const pages = ['/admin', '/admin/moderation', '/admin/moderation/messages', '/admin/utilisateurs'];
    expect(hrefs('.admin__sidebar .admin__link')).toEqual(pages);
    expect(hrefs('.admin__tabs .admin__tab')).toEqual(pages);
    expect(root.querySelector('.admin__content')?.textContent).toContain('page');
  });

  it('gives each tab an icon above a short label', async () => {
    await at('/admin');

    const labels = Array.from(root.querySelectorAll('.admin__tab-label')).map((label) => label.textContent?.trim());
    expect(labels).toEqual(['Tableau', 'Modération', 'Signalements', 'Utilisateurs']);
    for (const link of Array.from(root.querySelectorAll('.admin__tab'))) {
      expect(link.firstElementChild?.classList).toContain('admin__tab-icon');
      expect(link.querySelector('.admin__tab-icon app-icon')).not.toBeNull();
    }
    expect(root.querySelectorAll('nav[aria-label="Administration"]')).toHaveLength(2);
  });

  it.each([
    ['/admin', '/admin'],
    ['/admin/moderation', '/admin/moderation'],
    ['/admin/moderation?vue=en-ligne&page=2', '/admin/moderation'],
    ['/admin/moderation/messages', '/admin/moderation/messages'],
    ['/admin/utilisateurs?role=ADMIN', '/admin/utilisateurs'],
    ['/admin/utilisateurs/42', '/admin/utilisateurs'],
  ])('marks one entry as the current page at %s, in both', async (url, entry) => {
    await at(url);

    expect(hrefs('.admin__link--active')).toEqual([entry]);
    expect(hrefs('.admin__tab--active')).toEqual([entry]);
    expect(hrefs('.admin__sidebar [aria-current="page"]')).toEqual([entry]);
    expect(hrefs('.admin__tabs [aria-current="page"]')).toEqual([entry]);
  });

  it('counts the queue and the open reports with the lists’ own endpoints', async () => {
    await at('/admin', null);

    const queue = open(QUEUE);
    expect(queue).toHaveLength(1);
    expect(queue[0].request.params.get('view')).toBe('PENDING');
    expect(queue[0].request.params.get('size')).toBe('1');
    expect(open(REPORTS)[0].request.params.get('size')).toBe('1');
  });

  it('shows what waits as a badge on Modération and Signalements, and says it to a screen reader', async () => {
    await at('/admin', { listings: 3, reports: 1 });

    expect(badge('/admin/moderation')).toBe('3');
    expect(badge('/admin/moderation/messages')).toBe('1');
    expect(badge('/admin')).toBeNull();
    expect(badge('/admin/utilisateurs')).toBeNull();

    expect(tab('/admin/moderation').querySelector('.admin__badge')?.getAttribute('aria-hidden')).toBe('true');
    expect(tab('/admin/moderation').querySelector('.visually-hidden')?.textContent).toContain('3 annonces à vérifier');
    expect(tab('/admin/moderation/messages').querySelector('.visually-hidden')?.textContent).toContain('1 signalement ouvert');
  });

  it('shows no badge when nothing waits, and caps a long queue at 99+', async () => {
    await at('/admin', { listings: 0, reports: 140 });

    expect(badge('/admin/moderation')).toBeNull();
    expect(tab('/admin/moderation').querySelector('.visually-hidden')).toBeNull();
    expect(badge('/admin/moderation/messages')).toBe('99+');
    expect(tab('/admin/moderation/messages').querySelector('.visually-hidden')?.textContent).toContain('140 signalements ouverts');
  });

  it('shows no badge rather than a wrong one when a count fails', async () => {
    await at('/admin', null);
    open(QUEUE)[0].flush(total(4));
    open(REPORTS)[0].flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
    await settle();

    expect(root.querySelectorAll('.admin__badge')).toHaveLength(0);
  });

  it('counts again on moving to another page, not on a new filter within one', async () => {
    await at('/admin/moderation', { listings: 2, reports: 0 });
    const router = TestBed.inject(Router);

    await router.navigateByUrl('/admin/moderation?vue=en-ligne');
    await settle();
    expect(open(QUEUE)).toHaveLength(0);

    await router.navigateByUrl('/admin/moderation/messages');
    await settle();
    const again = open(QUEUE);
    expect(again).toHaveLength(1);
    again[0].flush(total(1));
    open(REPORTS)[0].flush(total(0));
    await settle();
    expect(badge('/admin/moderation')).toBe('1');
  });

  it('has a short label for every entry that fits under an icon', () => {
    expect(ADMIN_NAV.map((item) => item.short ?? item.label)).toEqual(['Tableau', 'Modération', 'Signalements', 'Utilisateurs']);
  });
});
