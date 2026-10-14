import { TestBed } from '@angular/core/testing';
import { Routes, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from '../app.routes';
import { FOOTER_COLUMNS, SiteFooter, resolveFooter } from './site-footer';

/**
 * The footer.
 *
 * <p>It lists pages that do not exist yet — among them the legal ones a French marketplace
 * is obliged to publish. The rule under test is that such an entry is never a link: the
 * wildcard route sends an unknown path to the home page, so a "Mentions légales" link
 * would not fail, it would open the search results and look as though the obligation were
 * met.
 */
describe('resolveFooter', () => {
  const columns = [{ title: 'Légal', items: [{ label: 'Mentions légales', path: 'mentions-legales' }] }];
  const available = (table: Routes) => resolveFooter(columns, table)[0].items[0].available;

  it('makes an entry a link once its page exists', () => {
    expect(available([{ path: 'mentions-legales', loadComponent: async () => class {} }])).toBe(true);
  });

  it('does not while it does not', () => {
    expect(available([])).toBe(false);
  });

  it('is not fooled by the wildcard, which matches every path', () => {
    expect(available([{ path: '**', redirectTo: '/' }])).toBe(false);
  });

  it('does not count a redirect as a page', () => {
    expect(available([{ path: 'mentions-legales', redirectTo: '/' }])).toBe(false);
  });
});

describe('the demonstration notice', () => {
  it('says, on every page, that the listings are invented and accounts are not for real use', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SiteFooter);
    await fixture.whenStable();
    const notice = fixture.nativeElement.querySelector('.footer__demo') as HTMLElement;
    const words = notice.textContent!.replace(/\s+/g, ' ').trim();

    expect(words).toContain('Site de démonstration.');
    expect(words).toContain('Les annonces, les vendeurs et les prix sont fictifs');
    expect(words).toContain('Les comptes créés ici ne sont pas destinés à un usage réel');
    expect(words).toContain('ni mot de passe utilisé ailleurs');
    // An aside, not part of the footer's navigation — and not hidden from anyone.
    expect(notice.getAttribute('role')).toBe('note');
    expect(notice.closest('nav')).toBeNull();
    expect(notice.hasAttribute('hidden')).toBe(false);
    expect(notice.getAttribute('aria-hidden')).toBeNull();
  });
});

describe('SiteFooter', () => {
  async function render(): Promise<HTMLElement> {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(SiteFooter);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('has the four columns a marketplace footer carries', async () => {
    const root = await render();

    expect(Array.from(root.querySelectorAll('.footer__title')).map((title) => title.textContent?.trim())).toEqual([
      'À propos',
      'Aide',
      'Informations légales',
      'Contact',
    ]);
  });

  it('lists the legal pages French law expects', () => {
    const legal = FOOTER_COLUMNS.find((column) => column.title === 'Informations légales')!;

    expect(legal.items.map((item) => item.label)).toEqual(
      expect.arrayContaining(['Mentions légales', 'Conditions générales', 'Politique de confidentialité']),
    );
  });

  it('links to nothing that does not exist', async () => {
    // Today that is every entry. The assertion is that there is not one dead link.
    const root = await render();
    const declared = FOOTER_COLUMNS.flatMap((column) => column.items);
    const existing = resolveFooter(FOOTER_COLUMNS, routes).flatMap((column) => column.items).filter((item) => item.available);

    expect(root.querySelectorAll('.footer__columns a')).toHaveLength(existing.length);
    expect(root.querySelectorAll('.footer__link--soon')).toHaveLength(declared.length - existing.length);
  });

  it('shows an unavailable entry as text, and tells a screen reader so', async () => {
    // Grey alone is a visual hint. aria-disabled is what says it to someone who cannot
    // see the grey.
    const root = await render();
    const soon = root.querySelector('.footer__link--soon') as HTMLElement;

    expect(soon.tagName).toBe('SPAN');
    expect(soon.getAttribute('aria-disabled')).toBe('true');
    expect(soon.hasAttribute('href')).toBe(false);
  });

  it('gives every entry a distinct path, so two can never claim one page', () => {
    const paths = FOOTER_COLUMNS.flatMap((column) => column.items).map((item) => item.path);
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('sits in the shared container and signs off with the mark and the year', async () => {
    const root = await render();

    expect(root.querySelector('.page')).not.toBeNull();
    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe('Pied de page');
    expect(root.querySelector('app-logo')).not.toBeNull();
    expect(root.querySelector('.footer__legal')?.textContent).toContain(String(new Date().getFullYear()));
  });
});
