import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { ADMIN_NAV, AdminLayout, adminNavItemFor } from './admin-layout';

@Component({ template: 'page' })
class Page {}

/** The frame, through a real router. */
describe('AdminLayout', () => {
  let harness: RouterTestingHarness;
  let root: HTMLElement;

  async function at(url: string): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
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
      ],
    });
    harness = await RouterTestingHarness.create(url);
    root = harness.routeNativeElement as HTMLElement;
    await harness.fixture.whenStable();
  }

  const active = () =>
    Array.from(root.querySelectorAll('.admin__link--active')).map((link) => link.getAttribute('href'));
  const toggle = () => root.querySelector('.admin__toggle') as HTMLButtonElement;
  const menu = () => root.querySelector('.admin__menu') as HTMLElement;

  it('lists the four pages, with the page itself beside them', async () => {
    await at('/admin');

    expect(Array.from(root.querySelectorAll('.admin__link')).map((link) => link.getAttribute('href'))).toEqual([
      '/admin',
      '/admin/moderation',
      '/admin/moderation/messages',
      '/admin/utilisateurs',
    ]);
    expect(root.querySelector('.admin__content')?.textContent).toContain('page');
  });

  it.each([
    ['/admin', '/admin'],
    ['/admin/moderation', '/admin/moderation'],
    ['/admin/moderation?vue=en-ligne&page=2', '/admin/moderation'],
    ['/admin/moderation/messages', '/admin/moderation/messages'],
    ['/admin/utilisateurs?role=ADMIN', '/admin/utilisateurs'],
    ['/admin/utilisateurs/42', '/admin/utilisateurs'],
  ])('highlights one entry at %s', async (url, entry) => {
    await at(url);

    expect(active()).toEqual([entry]);
    expect(root.querySelector('[aria-current="page"]')?.getAttribute('href')).toBe(entry);
  });

  it('folds on a phone behind a button naming the page, and folds again after a choice', async () => {
    await at('/admin/utilisateurs/42');
    expect(toggle().textContent).toContain('Utilisateurs');
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(menu().classList).not.toContain('admin__menu--open');

    toggle().click();
    await harness.fixture.whenStable();
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(menu().classList).toContain('admin__menu--open');

    (root.querySelector('a[href="/admin/moderation"]') as HTMLElement).click();
    await harness.fixture.whenStable();
    expect(menu().classList).not.toContain('admin__menu--open');
    expect(TestBed.inject(Router).url).toBe('/admin/moderation');
    expect(toggle().textContent).toContain('Modération');
  });
});

describe('adminNavItemFor', () => {
  it('finds the entry of a URL, whatever its query', () => {
    expect(adminNavItemFor('/admin').label).toBe('Tableau de bord');
    expect(adminNavItemFor('/admin/moderation?vue=retirees').label).toBe('Modération');
    expect(adminNavItemFor('/admin/moderation/messages').label).toBe('Signalements');
    expect(adminNavItemFor('/admin/utilisateurs/42').label).toBe('Utilisateurs');
    expect(ADMIN_NAV).toHaveLength(4);
  });
});
