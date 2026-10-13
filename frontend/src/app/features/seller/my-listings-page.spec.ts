import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, ListingQuota, ListingStatus, ListingSummary } from '../../core/api';
import { FlashService } from '../../core/ui/flash.service';
import { NBSP } from '../../shared/format/number';
import { MyListingsPage, statusOfSlug } from './my-listings-page';

const BASE = 'http://api.test';
const MINE = `${BASE}/api/v1/listings/mine`;
const QUOTA = `${BASE}/api/v1/listings/mine/quota`;

@Component({ template: '' })
class Stub {}

const { DRAFT, ACTIVE, PAUSED, SOLD, EXPIRED, REMOVED } = ListingStatus;

/** A row as the API sends it. `allowedTransitions` arrives as an array. */
const row = (
  id: string,
  status: ListingStatus,
  transitions: ListingStatus[],
  over: Partial<ListingSummary> = {},
): ListingSummary => ({
  id,
  status,
  title: `Annonce ${id}`,
  priceAmount: 1_250_000,
  currencyCode: 'EUR',
  make: 'Peugeot',
  model: '308',
  firstRegistrationYear: 2019,
  mileageKm: 84_000,
  city: 'Lyon',
  expiresAt: '2026-12-12T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  allowedTransitions: transitions as unknown as Set<ListingStatus>,
  editable: status !== SOLD,
  renewable: status === ACTIVE || status === EXPIRED,
  ...over,
});

const draft = (id = 'd1', over: Partial<ListingSummary> = {}) => row(id, DRAFT, [ACTIVE, REMOVED], over);
const online = (id = 'a1') => row(id, ACTIVE, [PAUSED, SOLD, EXPIRED, REMOVED]);
const paused = (id = 'p1') => row(id, PAUSED, [ACTIVE, SOLD, REMOVED]);
const sold = (id = 's1') => row(id, SOLD, [REMOVED]);
const expired = (id = 'e1') => row(id, EXPIRED, [ACTIVE, REMOVED]);

const page = (items: ListingSummary[], over: object = {}) => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: 1,
  hasNext: false,
  ...over,
});

/**
 * "Mes annonces".
 *
 * <p>Two properties carry the weight. The buttons on a row are the ones the API said it
 * would accept — never a guess from the status. And when the API refuses an action anyway,
 * the seller reads a French sentence on the row concerned, never a code, and the row is
 * refreshed so that its buttons are true again.
 */
describe('MyListingsPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  async function visit(url = '/mes-annonces', flash?: string): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            { path: 'mes-annonces', component: MyListingsPage },
            { path: 'mes-annonces/:id/modifier', component: Stub },
            { path: 'vendre', component: Stub },
            { path: 'annonces/:id', component: Stub },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    if (flash) {
      TestBed.inject(FlashService).set(flash);
    }
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    open(`${BASE}/api/v1/reference/countries`).forEach((request) =>
      request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
  }

  async function show(items: ListingSummary[], quota: ListingQuota = { active: 1, limit: 5, canPublish: true }) {
    await visit();
    open(MINE)[0].flush(page(items));
    open(QUOTA)[0].flush(quota);
    await settle();
  }

  /** Answers the reload that follows every action. */
  async function answerReload(items: ListingSummary[], quota: ListingQuota = { active: 1, limit: 5, canPublish: true }) {
    open(MINE).forEach((request) => request.flush(page(items)));
    open(QUOTA).forEach((request) => request.flush(quota));
    await settle();
  }

  const rows = () => Array.from(root.querySelectorAll('.mine__row')) as HTMLElement[];
  const rowOf = (id: string) => root.querySelector(`[data-listing="${id}"]`) as HTMLElement;
  const actionsOf = (id: string) =>
    Array.from(rowOf(id).querySelectorAll('[data-action]')).map((element) => element.textContent?.trim());
  const act = async (id: string, action: string) => {
    (rowOf(id).querySelector(`[data-action=${action}]`) as HTMLElement).click();
    await settle();
  };
  const text = () => (root.textContent ?? '').replace(/\s+/g, ' ');

  describe('the list', () => {
    it('asks for the caller’s own listings, first page', async () => {
      await visit();
      const request = open(MINE)[0];

      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('20');
      expect(request.request.params.has('status')).toBe(false);
    });

    it('shows each listing with its state, its facts, its price and its date', async () => {
      await show([online()]);
      const content = rowOf('a1').textContent!.replace(/\s+/g, ' ');

      expect(content).toContain('Annonce a1');
      expect(rowOf('a1').querySelector('.tag')?.textContent?.trim()).toBe('En ligne');
      expect(Array.from(rowOf('a1').querySelectorAll('.mine__facts li')).map((fact) => fact.textContent)).toEqual([
        'Peugeot 308',
        '2019',
        `84${NBSP}000${NBSP}km`,
        'Lyon',
      ]);
      expect(rowOf('a1').querySelector('app-price')?.textContent).toContain(`12${NBSP}500${NBSP}€`);
      expect(content).toContain('Expire le 12 décembre 2026');
    });

    it.each([
      [draft(), 'Brouillon', 'Modifiée le 1 octobre 2026'],
      [paused(), 'En pause', 'En pause · expire le 12 décembre 2026'],
      [sold(), 'Vendue', 'Modifiée le 1 octobre 2026'],
      [expired(), 'Expirée', 'Expirée le 12 décembre 2026'],
    ])('labels a listing in French with the date that matters in its state', async (listing, label, date) => {
      await show([listing]);

      expect(rows()[0].querySelector('.tag')?.textContent?.trim()).toBe(label);
      expect(rows()[0].textContent).toContain(date);
      expect(rows()[0].textContent).not.toContain(listing.status);
    });

    it('links an online listing to its public page, and no other', async () => {
      await show([online(), draft()]);

      expect(rowOf('a1').querySelector('.row__title a')?.getAttribute('href')).toBe('/annonces/a1');
      // A draft has no public page: a link would be a 404.
      expect(rowOf('d1').querySelector('.row__title a')).toBeNull();
    });

    it('leaves out a fact the listing does not have, rather than printing a blank', async () => {
      await show([draft('d1', { city: undefined, firstRegistrationYear: undefined, mileageKm: 0 })]);

      expect(Array.from(rowOf('d1').querySelectorAll('.mine__facts li')).map((fact) => fact.textContent)).toEqual([
        'Peugeot 308',
        `0${NBSP}km`,
      ]);
    });
  });

  describe('the actions offered', () => {
    it('offers each listing what the API allows for it', async () => {
      await show([draft(), online(), paused(), expired(), sold()]);

      expect(actionsOf('d1')).toEqual(['Modifier', 'Mettre en ligne', 'Supprimer']);
      expect(actionsOf('a1')).toEqual(['Modifier', 'Mettre en pause', 'Renouveler', 'Marquer comme vendue', 'Supprimer']);
      expect(actionsOf('p1')).toEqual(['Modifier', 'Mettre en ligne', 'Marquer comme vendue', 'Supprimer']);
      expect(actionsOf('e1')).toEqual(['Modifier', 'Renouveler', 'Supprimer']);
      expect(actionsOf('s1')).toEqual(['Supprimer']);
    });

    it('offers nothing the API did not list, whatever the status suggests', async () => {
      await show([row('x', PAUSED, [], { editable: false })]);

      expect(actionsOf('x')).toEqual([]);
    });

    it('makes "Modifier" a link to the editor', async () => {
      await show([draft()]);

      expect(rowOf('d1').querySelector('[data-action=edit]')?.getAttribute('href')).toBe('/mes-annonces/d1/modifier');
    });
  });

  describe('acting', () => {
    it.each([
      ['pause', 'a1', online(), `${BASE}/api/v1/listings/a1/unpublish`, 'en pause'],
      ['publish', 'p1', paused(), `${BASE}/api/v1/listings/p1/publish`, 'est en ligne'],
      ['renew', 'e1', expired(), `${BASE}/api/v1/listings/e1/renew`, 'renouvelée'],
    ])('%s calls its endpoint at once, then says so and reloads', async (action, id, listing, url, said) => {
      await show([listing]);
      await act(id, action);

      const request = open(url)[0];
      expect(request.request.method).toBe('POST');
      request.flush({ id });
      await settle();

      expect(root.querySelector('.alert--success')?.textContent).toContain(said);
      // The list and the quota are both asked for again: either may have changed.
      expect(open(MINE)).toHaveLength(1);
      expect(open(QUOTA)).toHaveLength(1);
    });

    it('shows the row as the API now describes it', async () => {
      await show([online()]);
      await act('a1', 'pause');
      open(`${BASE}/api/v1/listings/a1/unpublish`)[0].flush({ id: 'a1' });
      await settle();
      await answerReload([row('a1', PAUSED, [ACTIVE, SOLD, REMOVED])]);

      expect(rowOf('a1').querySelector('.tag')?.textContent?.trim()).toBe('En pause');
      expect(actionsOf('a1')).toEqual(['Modifier', 'Mettre en ligne', 'Marquer comme vendue', 'Supprimer']);
    });

    it('disables every button while an action is running', async () => {
      await show([online(), paused()]);
      await act('a1', 'pause');

      const buttons = Array.from(root.querySelectorAll('button[data-action]')) as HTMLButtonElement[];
      expect(buttons.length).toBeGreaterThan(0);
      expect(buttons.every((button) => button.disabled)).toBe(true);
      expect(rowOf('a1').getAttribute('aria-busy')).toBe('true');
    });
  });

  describe('actions that cannot be undone', () => {
    it('asks before marking as sold, and sends nothing until the answer', async () => {
      await show([online()]);
      await act('a1', 'sold');

      expect(rowOf('a1').querySelector('.mine__confirm')?.textContent).toContain(
        'Marquer cette annonce comme vendue ? Elle ne sera plus visible ni modifiable.',
      );
      expect(open(`${BASE}/api/v1/listings/a1/sold`)).toHaveLength(0);
    });

    it('marks as sold on yes', async () => {
      await show([online()]);
      await act('a1', 'sold');
      (rowOf('a1').querySelector('[data-confirm=yes]') as HTMLElement).click();
      await settle();

      const request = open(`${BASE}/api/v1/listings/a1/sold`)[0];
      expect(request.request.method).toBe('POST');
      request.flush({ id: 'a1' });
      await settle();
      expect(root.querySelector('.alert--success')?.textContent).toContain('marquée comme vendue');
    });

    it('does nothing on "Annuler"', async () => {
      await show([online()]);
      await act('a1', 'delete');
      (rowOf('a1').querySelector('[data-confirm=no]') as HTMLElement).click();
      await settle();

      expect(root.querySelector('.mine__confirm')).toBeNull();
      backend.expectNone(`${BASE}/api/v1/listings/a1`);
      expect(rows()).toHaveLength(1);
    });

    it('deletes on yes, and the row is gone after the reload', async () => {
      await show([draft(), online()]);
      await act('d1', 'delete');
      expect(rowOf('d1').querySelector('.mine__confirm')?.textContent).toContain('Supprimer définitivement cette annonce ?');
      (rowOf('d1').querySelector('[data-confirm=yes]') as HTMLElement).click();
      await settle();

      const request = open(`${BASE}/api/v1/listings/d1`)[0];
      expect(request.request.method).toBe('DELETE');
      request.flush(null);
      await settle();
      await answerReload([online()]);

      expect(rowOf('d1')).toBeNull();
      expect(root.querySelector('.alert--success')?.textContent).toContain('supprimée');
    });

    it('asks on the row concerned only', async () => {
      await show([online('a1'), online('a2')]);
      await act('a2', 'sold');

      expect(rowOf('a1').querySelector('.mine__confirm')).toBeNull();
      expect(rowOf('a2').querySelector('.mine__confirm')).not.toBeNull();
    });
  });

  describe('when the API refuses', () => {
    const refuse = async (id: string, code: ErrorCode, status: number) => {
      await act(id, 'publish');
      open(`${BASE}/api/v1/listings/${id}/publish`)[0].flush(
        { code, message: 'developer wording', detail: 'more developer wording' },
        { status, statusText: 'Refused' },
      );
      await settle();
    };

    it('explains that a photo is needed, and links to where to add one', async () => {
      await show([draft()]);
      await refuse('d1', ErrorCode.PHOTO_REQUIRED, 422);

      const failure = rowOf('d1').querySelector('.mine__failure') as HTMLElement;
      expect(failure.textContent).toContain('Ajoutez au moins une photo avant de publier.');
      expect(failure.getAttribute('role')).toBe('alert');
      expect(failure.querySelector('a')?.getAttribute('href')).toBe('/mes-annonces/d1/modifier?etape=photos');
    });

    it('explains the cap on online listings', async () => {
      await show([draft()]);
      await refuse('d1', ErrorCode.ACTIVE_LISTING_LIMIT_REACHED, 409);

      const failure = rowOf('d1').querySelector('.mine__failure') as HTMLElement;
      expect(failure.textContent).toContain('Vous avez atteint le nombre maximum d’annonces en ligne.');
      // The photo link is for the photo rule only.
      expect(failure.querySelector('a')).toBeNull();
    });

    it('explains a transition that is no longer valid, and refreshes the row', async () => {
      // Sold in another tab since this list was loaded.
      await show([paused()]);
      await refuse('p1', ErrorCode.CONFLICT, 409);

      expect(rowOf('p1').querySelector('.mine__failure')?.textContent).toContain(
        'Cette action n’est pas possible dans l’état actuel.',
      );
      await answerReload([sold('p1')]);
      expect(actionsOf('p1')).toEqual(['Supprimer']);
      // The explanation survives the refresh: it is why the buttons changed.
      expect(rowOf('p1').querySelector('.mine__failure')).not.toBeNull();
    });

    it('never shows the code or the API’s own wording', async () => {
      await show([draft()]);
      await refuse('d1', ErrorCode.PHOTO_REQUIRED, 422);

      expect(text()).not.toContain('PHOTO_REQUIRED');
      expect(text()).not.toContain('developer wording');
    });

    it('clears the explanation when another action is started', async () => {
      await show([draft(), online()]);
      await refuse('d1', ErrorCode.PHOTO_REQUIRED, 422);
      await answerReload([draft(), online()]);
      await act('a1', 'pause');

      expect(root.querySelector('.mine__failure')).toBeNull();
    });
  });

  describe('the cap on a private seller’s online listings', () => {
    it('says how many are online out of how many', async () => {
      await show([online()], { active: 3, limit: 5, canPublish: true });

      expect(root.querySelector('.mine__quota')?.textContent).toBe(
        '3 annonces en ligne sur 5 possibles avec un compte particulier.',
      );
    });

    it('agrees in number with one listing', async () => {
      await show([online()], { active: 1, limit: 5, canPublish: true });

      expect(root.querySelector('.mine__quota')?.textContent).toContain('1 annonce en ligne sur 5');
    });

    it('says nothing to a dealer, who has no cap', async () => {
      await show([online()], { active: 40, canPublish: true });

      expect(root.querySelector('.mine__quota')).toBeNull();
    });

    it('warns, at the cap, on the rows that could otherwise go online', async () => {
      await show([draft(), online(), sold()], { active: 5, limit: 5, canPublish: false });

      expect(rowOf('d1').querySelector('.mine__cap')?.textContent).toContain('Maximum d’annonces en ligne atteint');
      expect(rowOf('a1').querySelector('.mine__cap')).toBeNull();
      expect(rowOf('s1').querySelector('.mine__cap')).toBeNull();
    });

    it('still lists the listings when the quota cannot be read', async () => {
      await visit();
      open(MINE)[0].flush(page([online()]));
      open(QUOTA)[0].flush(null, { status: 500, statusText: 'Error' });
      await settle();

      expect(rows()).toHaveLength(1);
      expect(root.querySelector('.mine__quota')).toBeNull();
    });
  });

  describe('filtering by state', () => {
    it('offers the five states and "Toutes", each as an address', async () => {
      await show([online()]);
      const links = Array.from(root.querySelectorAll('.mine__filter')).map((link) => [
        link.textContent?.trim(),
        link.getAttribute('href'),
      ]);

      expect(links).toEqual([
        ['Toutes', '/mes-annonces'],
        ['Brouillon', '/mes-annonces?statut=brouillon'],
        ['En ligne', '/mes-annonces?statut=en-ligne'],
        ['En pause', '/mes-annonces?statut=en-pause'],
        ['Vendue', '/mes-annonces?statut=vendue'],
        ['Expirée', '/mes-annonces?statut=expiree'],
      ]);
    });

    it('asks the API for the state named in the address, and marks that filter', async () => {
      await visit('/mes-annonces?statut=en-pause');

      expect(open(MINE)[0].request.params.get('status')).toBe('PAUSED');
      await settle();
      expect(root.querySelector('.mine__filter--active')?.textContent?.trim()).toBe('En pause');
    });

    it('fetches again when the filter changes', async () => {
      await show([online()]);
      await TestBed.inject(Router).navigateByUrl('/mes-annonces?statut=vendue');
      await settle();

      expect(open(MINE)[0].request.params.get('status')).toBe('SOLD');
    });

    it('ignores a state it does not know rather than sending it', async () => {
      await visit('/mes-annonces?statut=REMOVED');

      expect(open(MINE)[0].request.params.has('status')).toBe(false);
      expect(statusOfSlug('n-importe-quoi')).toBeUndefined();
      expect(statusOfSlug(undefined)).toBeUndefined();
    });

    it('says a filter is empty, which is not the same as having no listings', async () => {
      await visit('/mes-annonces?statut=vendue');
      open(MINE)[0].flush(page([]));
      await settle();

      expect(text()).toContain('Aucune annonce dans cet état.');
      expect(text()).not.toContain('Vous n’avez pas encore d’annonce');
    });
  });

  describe('states of the page', () => {
    it('says it is loading', async () => {
      await visit();

      expect(root.querySelector('[aria-busy=true]')?.textContent).toContain('Chargement de vos annonces');
    });

    it('invites a seller with no listing to create the first', async () => {
      await show([]);

      expect(text()).toContain('Vous n’avez pas encore d’annonce.');
      expect(root.querySelector('.mine__state a')?.getAttribute('href')).toBe('/vendre');
    });

    it('always offers "Déposer une annonce"', async () => {
      await show([online()]);

      expect(root.querySelector('.mine__head a')?.getAttribute('href')).toBe('/vendre');
    });

    it('explains a failure in French and retries on demand', async () => {
      await visit();
      open(MINE)[0].flush({ code: ErrorCode.INTERNAL_ERROR }, { status: 500, statusText: 'Error' });
      await settle();

      expect(root.querySelector('.alert--error')).not.toBeNull();
      expect(text()).not.toContain('INTERNAL_ERROR');

      (Array.from(root.querySelectorAll('button')).find((button) => button.textContent?.includes('Réessayer')) as HTMLElement).click();
      await settle();
      open(MINE)[0].flush(page([online()]));
      await settle();

      expect(rows()).toHaveLength(1);
    });

    it('shows, once, the message carried over from the editor', async () => {
      await visit('/mes-annonces', 'Votre annonce est en ligne.');
      open(MINE)[0].flush(page([online()]));
      await settle();

      expect(root.querySelector('.alert--success')?.textContent).toContain('Votre annonce est en ligne.');
      expect(TestBed.inject(FlashService).take()).toBeNull();
    });

    it('loads the next page after the ones on screen', async () => {
      await visit();
      open(MINE)[0].flush(page([online('a1')], { hasNext: true, totalElements: 2 }));
      await settle();

      (Array.from(root.querySelectorAll('button')).find((button) => button.textContent?.includes('Voir plus')) as HTMLElement).click();
      await settle();
      const next = open(MINE)[0];
      expect(next.request.params.get('page')).toBe('1');
      next.flush(page([online('a2')], { page: 1 }));
      await settle();

      expect(rows().map((element) => element.getAttribute('data-listing'))).toEqual(['a1', 'a2']);
      expect(text()).not.toContain('Voir plus');
    });
  });
});
