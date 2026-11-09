import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from '../../app.routes';
import { SESSION_STORAGE_KEY } from '../../core/auth/token-store';
import { FOOTER_COLUMNS } from '../../layout/site-footer';
import { FAVOURITES_STORAGE_KEY } from '../../shared/listing/favourites';
import { MODERATION_LAYOUT_STORAGE_KEY } from '../admin/moderation-layout';
import { RECENT_SEARCHES_STORAGE_KEY } from '../search/recent-searches';
import { SEARCH_VIEW_STORAGE_KEY } from '../search/view-preference';
import { AboutPage } from './about-page';
import { BuyingTipsPage } from './buying-tips-page';
import { CookiesPage } from './cookies-page';
import { HelpPage } from './help-page';
import { HowItWorksPage } from './how-it-works-page';
import { CONTACT_EMAIL } from './info-page';
import { LegalNoticePage } from './legal-notice-page';
import { PrivacyPage } from './privacy-page';
import { ProPage } from './pro-page';
import { ReportPage } from './report-page';
import { AccountDeletedPage } from './account-deleted-page';
import { SellingTipsPage } from './selling-tips-page';
import { TermsPage } from './terms-page';

/** The footer's pages: present, public, and saying what the code does. */

const LEGAL: [string, Type<unknown>][] = [
  ['mentions-legales', LegalNoticePage],
  ['conditions-generales', TermsPage],
  ['confidentialite', PrivacyPage],
  ['cookies', CookiesPage],
];
const GUIDES: [string, Type<unknown>][] = [
  ['a-propos', AboutPage],
  ['comment-ca-marche', HowItWorksPage],
  ['pro', ProPage],
  ['aide', HelpPage],
  ['conseils-achat', BuyingTipsPage],
  ['conseils-vente', SellingTipsPage],
  ['signalement', ReportPage],
  ['compte-supprime', AccountDeletedPage],
];

async function render(page: Type<unknown>): Promise<HTMLElement> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(page);
  await fixture.whenStable();
  return fixture.nativeElement;
}

const text = (root: HTMLElement) => (root.textContent ?? '').replace(/\s+/g, ' ');

describe('the footer’s pages', () => {
  it('each has a public, lazy route, and every footer entry is one of them or a working page', () => {
    for (const [path] of [...LEGAL, ...GUIDES]) {
      const route = routes.find((candidate) => candidate.path === path);
      expect(route?.loadComponent, path).toBeTypeOf('function');
      expect(route?.canActivate, path).toBeUndefined();
      expect(route?.title, path).toBeTruthy();
    }
    const pages = new Set(routes.map((route) => route.path));
    for (const item of FOOTER_COLUMNS.flatMap((column) => column.items)) {
      expect(pages.has(item.path), item.path).toBe(true);
    }
  });

  it.each([...LEGAL, ...GUIDES])('%s has one title', async (path, page) => {
    const root = await render(page);

    expect(root.querySelectorAll('h1')).toHaveLength(1);
    expect(root.querySelector('h1')?.textContent?.trim()).toBeTruthy();
  });

  it.each(LEGAL)('%s ends with the note that it is not legal advice, and is dated', async (_path, page) => {
    const root = await render(page);

    expect(root.querySelectorAll('[data-disclaimer]')).toHaveLength(1);
    expect(root.querySelector('[data-disclaimer]')?.textContent?.trim()).toBe('Ce texte n’est pas un avis juridique.');
    expect(text(root)).toContain('Dernière mise à jour');
  });

  it.each(GUIDES)('%s is not dressed as a legal text', async (_path, page) => {
    const root = await render(page);

    expect(root.querySelector('[data-disclaimer]')).toBeNull();
  });
});

describe('the contact address', () => {
  it('is a marked placeholder until it is filled in, never a made-up address', async () => {
    expect(CONTACT_EMAIL).toBeNull();
    for (const page of [LegalNoticePage, PrivacyPage, TermsPage, HelpPage]) {
      const root = await render(page);
      const placeholder = root.querySelector('[data-placeholder=contact]');
      expect(placeholder?.tagName).toBe('MARK');
      expect(placeholder?.textContent).toContain('à compléter');
      expect(root.querySelector('a[href^="mailto:"]')).toBeNull();
    }
  });
});

describe('Mentions légales', () => {
  it('names the publisher, the project’s nature, and the four hosts', async () => {
    const page = text(await render(LegalNoticePage));

    expect(page).toContain('Yassine Balbali');
    expect(page).toContain('non commercial');
    for (const host of ['Vercel Inc.', 'Render Services, Inc.', 'Neon, LLC', 'Supabase Pte. Ltd.']) {
      expect(page).toContain(host);
    }
  });
});

describe('Politique de confidentialité', () => {
  it('gives the retention rules the server applies', async () => {
    const page = text(await render(PrivacyPage));

    // AuthMaintenanceSweeper: outbox 90 days, dead credentials 30 days; refresh tokens 90 days;
    // throttle counters 24 hours; listings 60 days; search filter counts 200 days.
    expect(page).toContain('90 jours après l’envoi');
    expect(page).toContain('effacée 30 jours après son expiration');
    expect(page).toContain('Effacés 24 heures après la dernière tentative');
    expect(page).toContain('En ligne 60 jours');
    expect(page).toContain('200 jours');
  });

  it('names every processor, and says what goes to Groq and that Brevo tracks clicks', async () => {
    const page = text(await render(PrivacyPage));

    for (const processor of ['Render', 'Neon', 'Supabase', 'Vercel', 'Brevo', 'Groq']) {
      expect(page).toContain(processor);
    }
    expect(page).toMatch(/texte tapé dans la recherche intelligente.*envoyé à Groq/);
    expect(page).toContain('enregistre donc les clics');
  });

  it('points to the export and the deletion, and says what deleting the account erases and what stays', async () => {
    const root = await render(PrivacyPage);
    const deletion = root.querySelector('[data-deletion]')?.textContent?.replace(/\s+/g, ' ');

    expect(root.querySelector('a[href="/mon-compte/donnees"]')).not.toBeNull();
    expect(deletion).toContain('vos annonces et leurs photos');
    expect(deletion).toContain('« Compte supprimé », sans leur texte');
    expect(deletion).toContain('signalement en cours');
    expect(root.querySelector('[data-gap]')).toBeNull();
  });

  it('gives the retention of deleted listings and of conversations the daily sweep applies', async () => {
    const page = text(await render(PrivacyPage));

    // DataRetentionSweeper: 30 days after deletion, a year after the last message.
    expect(page).toContain('effacée 30 jours plus tard');
    expect(page).toContain('un an après son dernier message');
    expect(page).not.toContain('Sans limite de durée aujourd’hui');
  });

  it('keeps the administration log a year, as the daily sweep does', async () => {
    const root = await render(PrivacyPage);
    const row = root.querySelector('[data-retention="admin-log"]')?.textContent?.replace(/\s+/g, ' ');

    // DataRetentionSweeper.ADMIN_ACTIONS, and the trigger in V27.
    expect(row).toContain('Un an après chaque action, puis effacé');
    expect(row).not.toContain('Sans limite');
  });

  it('points to the CNIL', async () => {
    const root = await render(PrivacyPage);

    expect(root.querySelector('a[href^="https://www.cnil.fr"]')).not.toBeNull();
  });
});

describe('Cookies', () => {
  it('lists every key the site keeps in the browser, and only those', async () => {
    const root = await render(CookiesPage);
    const listed = Array.from(root.querySelectorAll('[data-key]')).map((row) => row.getAttribute('data-key'));

    expect(listed.sort()).toEqual(
      [
        SESSION_STORAGE_KEY,
        FAVOURITES_STORAGE_KEY,
        RECENT_SEARCHES_STORAGE_KEY,
        SEARCH_VIEW_STORAGE_KEY,
        MODERATION_LAYOUT_STORAGE_KEY,
      ].sort(),
    );
  });

  it('says there is no cookie and no tracking', async () => {
    const page = text(await render(CookiesPage));

    expect(page).toContain('ne dépose aucun cookie');
    expect(page).toContain('ni mesure d’audience');
  });
});
