import { Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { routes } from '../../app.routes';
import CONTENT from '../../../../content/help.fr.json';
import { GUIDES, guide, guideSegments } from './guide';

/** frontend/content/help.fr.json: the help pages' one source, and its markup. */

/** Every path the router knows, children joined to their parent; parameters as written. */
function paths(list: Routes, parent = ''): string[] {
  return list.flatMap((route) => {
    const path = [parent, route.path ?? ''].filter(Boolean).join('/');
    return [`/${path}`, ...paths(route.children ?? [], path)];
  });
}

describe('guideSegments', () => {
  it('reads links, bold, links inside bold, and the contact address', () => {
    expect(guideSegments('**Cherchez** depuis l’[accueil](/), ou {contact}.')).toEqual([
      { text: 'Cherchez', strong: true },
      { text: ' depuis l’' },
      { text: 'accueil', path: '/' },
      { text: ', ou ' },
      { text: '', contact: true },
      { text: '.' },
    ]);
    expect(guideSegments('**Votre page dans l’[annuaire](/professionnels)** : texte')).toEqual([
      { text: 'Votre page dans l’', strong: true },
      { text: 'annuaire', strong: true, path: '/professionnels' },
      { text: ' : texte' },
    ]);
  });

  it('leaves text without markup as it is', () => {
    expect(guideSegments('Tout. AutoX ne vend rien.')).toEqual([
      { text: 'Tout. AutoX ne vend rien.' },
    ]);
  });
});

describe('the help content', () => {
  const known = new Set(paths(routes));
  const texts = GUIDES.flatMap((page) =>
    page.sections.flatMap((section) =>
      section.blocks.flatMap((block) => (block.kind === 'p' ? [block.segments] : block.items)),
    ),
  );

  it('has the seven guide pages, each at its route, and none of the legal ones', () => {
    expect(GUIDES.map((page) => page.path).sort()).toEqual(
      [
        '/a-propos',
        '/aide',
        '/comment-ca-marche',
        '/conseils-achat',
        '/conseils-vente',
        '/pro',
        '/signalement',
      ].sort(),
    );
    for (const page of GUIDES) {
      expect(page.path, page.id).toBe(`/${page.id}`);
      expect(known.has(page.path), page.path).toBe(true);
      expect(page.title.trim(), page.id).toBeTruthy();
    }
    for (const legal of [
      '/mentions-legales',
      '/conditions-generales',
      '/confidentialite',
      '/cookies',
    ]) {
      expect(
        GUIDES.some((page) => page.path === legal),
        legal,
      ).toBe(false);
    }
  });

  it('links only to paths the site has', () => {
    const links = texts.flat().flatMap((segment) => (segment.path ? [segment.path] : []));

    expect(links.length).toBeGreaterThan(20);
    for (const link of links) expect(known.has(link), link).toBe(true);
  });

  it('gives each section an id unique in its page, and a heading unless it opens the page', () => {
    for (const page of GUIDES) {
      const ids = page.sections.map((section) => section.id);
      expect(new Set(ids).size, page.id).toBe(ids.length);
      for (const id of ids) expect(id, page.id).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
      page.sections.forEach((section, index) => {
        if (index > 0) expect(section.heading?.trim(), `${page.id} ${section.id}`).toBeTruthy();
      });
    }
  });

  it('leaves no markup unread: every bold closed, every bracket a link', () => {
    for (const page of CONTENT.pages) {
      for (const section of page.sections) {
        for (const block of section.blocks as { p?: string; ul?: string[]; ol?: string[] }[]) {
          for (const raw of block.p !== undefined ? [block.p] : (block.ul ?? block.ol ?? [])) {
            expect(raw.split('**').length % 2, raw).toBe(1);
          }
        }
      }
    }
    for (const segment of texts.flat()) {
      expect(segment.text, segment.text).not.toMatch(/[[\]{}*]/);
    }
  });

  it('refuses a page it does not have', () => {
    expect(() => guide('mentions-legales')).toThrow(/no page/);
  });
});
