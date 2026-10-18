import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { NotFoundPage } from '../../features/errors/not-found-page';
import { NotFound } from './not-found';

/**
 * The "this is not here" screen.
 *
 * <p>It must not look like a fault. Nothing broke — a listing sold, an address was
 * mistyped — so there is no red and no "erreur", only what happened and the way to what
 * is here.
 */
describe('NotFound', () => {
  @Component({
    imports: [NotFound],
    template: `<app-not-found heading="Cette annonce n’est plus disponible" message="Elle a pu être vendue." linkUrl="/?marque=peugeot" linkLabel="Voir les annonces" />`,
  })
  class Host {}

  async function render(type: typeof Host | typeof NotFoundPage = Host): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(type as typeof Host);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('says what happened, as the page’s heading', async () => {
    const root = await render();
    const headings = root.querySelectorAll('h1');

    // The only h1: this replaces the page's content, so it is the page's title.
    expect(headings).toHaveLength(1);
    expect(headings[0].textContent?.trim()).toBe('Cette annonce n’est plus disponible');
    expect(root.textContent).toContain('Elle a pu être vendue.');
  });

  it('offers a way on, to where it is told', async () => {
    const link = (await render()).querySelector('a') as HTMLAnchorElement;

    expect(link.getAttribute('href')).toBe('/?marque=peugeot');
    expect(link.textContent?.trim()).toBe('Voir les annonces');
  });

  it('follows a link that carries a query string, without mangling it', async () => {
    // The listing page passes the visitor's last search. Treated as a path, the "?" is
    // percent-encoded and the link leads to an address that matches no route — a dead
    // link on the one screen whose whole job is to offer a way out.
    const root = await render();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    (root.querySelector('a') as HTMLAnchorElement).click();

    expect(navigate).toHaveBeenCalledWith('/?marque=peugeot');
  });

  it('leaves a ctrl-click to the browser, so it can open in a new tab', async () => {
    const root = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl');
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true });

    (root.querySelector('a') as HTMLAnchorElement).dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not present itself as an error', async () => {
    const root = await render();

    expect(root.querySelector('.alert--error')).toBeNull();
    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect(root.textContent).not.toMatch(/erreur/i);
  });

  it('keeps the status number out of what a screen reader announces', async () => {
    // "404" is for people who know what it means; read aloud first, it is noise.
    const root = await render();
    expect(root.querySelector('.missing__code')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('has sensible words of its own for an address that matches no route', async () => {
    const root = await render(NotFoundPage);

    expect(root.querySelector('h1')?.textContent?.trim()).toBe('Cette page n’existe pas');
    expect(root.querySelector('a')?.getAttribute('href')).toBe('/recherche');
    expect(TestBed.inject(Title).getTitle()).toBe('Page introuvable · AutoX');
  });
});
