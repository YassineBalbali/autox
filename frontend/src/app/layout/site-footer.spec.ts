import { TestBed } from '@angular/core/testing';
import { Routes, provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from '../app.routes';
import { FOOTER_COLUMNS, SiteFooter, resolveFooter } from './site-footer';

/**
 * The footer.
 *
 * <p>Every entry is a page that exists, the legal ones a French site is obliged to publish
 * among them. The rule kept from when they did not: an entry without a page is never a link,
 * since the wildcard route would answer it with something else, and a "Mentions légales" link
 * would look as though the obligation were met.
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

  it('has the columns a marketplace footer carries, the working pages first', async () => {
    const root = await render();

    expect(Array.from(root.querySelectorAll('.footer__title-text')).map((title) => title.textContent?.trim())).toEqual([
      'AutoX',
      'À propos',
      'Aide',
      'Informations légales',
    ]);
  });

  it('lists the legal pages French law expects', () => {
    const legal = FOOTER_COLUMNS.find((column) => column.title === 'Informations légales')!;

    expect(legal.items.map((item) => item.label)).toEqual(
      expect.arrayContaining(['Mentions légales', 'Conditions générales', 'Politique de confidentialité']),
    );
  });

  it('links every entry to a page that exists', async () => {
    const root = await render();
    const declared = FOOTER_COLUMNS.flatMap((column) => column.items);

    expect(resolveFooter(FOOTER_COLUMNS, routes).flatMap((column) => column.items).every((item) => item.available)).toBe(true);
    expect(Array.from(root.querySelectorAll('.footer__columns a')).map((link) => link.getAttribute('href'))).toEqual(
      declared.map((item) => '/' + item.path),
    );
  });

  it('offers nothing with nothing behind it: no report link, no contact form, no press page', () => {
    const paths = FOOTER_COLUMNS.flatMap((column) => column.items).map((item) => item.path);

    expect(paths).not.toContain('signalement');
    expect(paths).not.toContain('contact');
    expect(paths).not.toContain('presse');
  });

  it('gives every entry a distinct path, so two can never claim one page', () => {
    const paths = FOOTER_COLUMNS.flatMap((column) => column.items).map((item) => item.path);
    expect(new Set(paths).size).toBe(paths.length);
  });

  describe('on a phone, folded', () => {
    const toggles = (root: HTMLElement) => Array.from(root.querySelectorAll<HTMLButtonElement>('.footer__toggle'));
    const list = (root: HTMLElement, button: HTMLButtonElement) =>
      root.querySelector('#' + button.getAttribute('aria-controls')) as HTMLElement;

    it('gives each column a button in its heading, which names the list it controls', async () => {
      const root = await render();

      expect(toggles(root).map((button) => button.textContent?.trim())).toEqual([
        'AutoX',
        'À propos',
        'Aide',
        'Informations légales',
      ]);
      for (const button of toggles(root)) {
        expect(button.parentElement?.tagName).toBe('H2');
        expect(list(root, button).tagName).toBe('UL');
      }
    });

    it('starts folded, and opens and closes each column on its own', async () => {
      TestBed.configureTestingModule({ providers: [provideRouter([])] });
      const fixture = TestBed.createComponent(SiteFooter);
      await fixture.whenStable();
      const root: HTMLElement = fixture.nativeElement;
      const [first, second] = toggles(root);

      expect(toggles(root).every((button) => button.getAttribute('aria-expanded') === 'false')).toBe(true);
      expect(list(root, first).classList).not.toContain('footer__list--open');

      first.click();
      await fixture.whenStable();
      expect(first.getAttribute('aria-expanded')).toBe('true');
      expect(list(root, first).classList).toContain('footer__list--open');
      expect(second.getAttribute('aria-expanded')).toBe('false');

      second.click();
      await fixture.whenStable();
      // Opening one does not close another: someone comparing two columns keeps both.
      expect(first.getAttribute('aria-expanded')).toBe('true');
      expect(second.getAttribute('aria-expanded')).toBe('true');

      first.click();
      await fixture.whenStable();
      expect(first.getAttribute('aria-expanded')).toBe('false');
      expect(list(root, first).classList).not.toContain('footer__list--open');
    });
  });

  it('sits in the shared container and signs off with the mark and the year', async () => {
    const root = await render();

    expect(root.querySelector('.page')).not.toBeNull();
    expect(root.querySelector('nav')?.getAttribute('aria-label')).toBe('Pied de page');
    expect(root.querySelector('app-logo')).not.toBeNull();
    expect(root.querySelector('.footer__legal')?.textContent).toContain(String(new Date().getFullYear()));
  });
});
