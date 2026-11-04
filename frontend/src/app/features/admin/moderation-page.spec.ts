import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import {
  BASE_PATH,
  ErrorCode,
  ListingStatus,
  ModeratedListing,
  ModerationActionType,
  ModerationReason,
  ModerationStatus,
} from '../../core/api';
import { ModerationPage, PAGE_SIZE, VIEWS, viewOfSlug } from './moderation-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/admin/listings`;

const { APPROVE, REJECT, REMOVE, REINSTATE } = ModerationActionType;

const item = (id: string, actions: ModerationActionType[], status = ListingStatus.ACTIVE): ModeratedListing => ({
  listing: {
    id,
    status,
    moderationStatus: ModerationStatus.NOT_REVIEWED,
    title: `Annonce ${id}`,
    priceAmount: 1_000_000,
    currencyCode: 'EUR',
    images: [],
  },
  seller: { id: 'u1', displayName: 'Vendeur', email: 'vendeur@autox.test' },
  allowedActions: actions as unknown as Set<ModerationActionType>,
  trail: [],
});

const pending = (id: string) => item(id, [APPROVE, REJECT, REMOVE]);
const approved = (id: string) => item(id, [REMOVE]);
const removed = (id: string) => item(id, [REINSTATE], ListingStatus.REMOVED);

const page = (items: ModeratedListing[], over: object = {}) => ({
  items,
  page: 0,
  size: PAGE_SIZE,
  totalElements: items.length,
  totalPages: 1,
  hasNext: false,
  ...over,
});

/**
 * The moderation page.
 *
 * <p>What matters is that each decision reaches the right endpoint with the right body,
 * that the list afterwards is the API's and not this page's idea of it, and that a
 * decision the API refuses is explained in French — because on a queue two people work,
 * "somebody else got there first" is an ordinary event, not an error to hide.
 */
describe('ModerationPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let router: Router;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);

  async function visit(url = '/admin/moderation'): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'admin/moderation', component: ModerationPage }], withComponentInputBinding()),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    open(`${BASE}/api/v1/reference/countries`).forEach((request) =>
      request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
  }

  async function show(items: ModeratedListing[], url = '/admin/moderation', over: object = {}): Promise<void> {
    await visit(url);
    open(LIST)[0].flush(page(items, over));
    await settle();
  }

  const cards = () => Array.from(root.querySelectorAll('app-moderation-card article')).map((card) => card.getAttribute('data-listing'));
  const card = (id: string) => root.querySelector(`[data-listing="${id}"]`) as HTMLElement;
  const press = async (id: string, action: string) => {
    (card(id).querySelector(`[data-action=${action}]`) as HTMLButtonElement).click();
    await settle();
  };
  const choose = async (id: string, reason: ModerationReason, note?: string) => {
    const select = card(id).querySelector('select') as HTMLSelectElement;
    select.value = reason;
    select.dispatchEvent(new Event('change'));
    if (note !== undefined) {
      const area = card(id).querySelector('textarea') as HTMLTextAreaElement;
      area.value = note;
      area.dispatchEvent(new Event('input'));
    }
    await settle();
  };
  const text = () => (root.textContent ?? '').replace(/\s+/g, ' ');

  describe('the three lists', () => {
    it('opens on the queue: listings awaiting review, ten at a time', async () => {
      await visit();
      const request = open(LIST)[0];

      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('view')).toBe('PENDING');
      expect(request.request.params.get('page')).toBe('0');
      expect(request.request.params.get('size')).toBe('10');
    });

    it('offers each list as an address', async () => {
      await show([pending('a')]);
      const links = Array.from(root.querySelectorAll('.moderation__view')).map((link) => [
        link.textContent?.replace(/\s+/g, ' ').trim(),
        link.getAttribute('href'),
      ]);

      expect(links).toEqual([
        ['À vérifier (1)', '/admin/moderation'],
        ['En ligne', '/admin/moderation?vue=en-ligne'],
        ['Retirées', '/admin/moderation?vue=retirees'],
      ]);
      expect(root.querySelector('.moderation__view--active')?.getAttribute('aria-current')).toBe('page');
    });

    it.each([
      ['en-ligne', 'LIVE'],
      ['retirees', 'REMOVED'],
      ['a-verifier', 'PENDING'],
    ])('asks the API for the list named in the address: %s', async (slug, view) => {
      await visit(`/admin/moderation?vue=${slug}`);

      expect(open(LIST)[0].request.params.get('view')).toBe(view);
    });

    it('falls back to the queue for a list it does not know', async () => {
      await visit('/admin/moderation?vue=tout');

      expect(open(LIST)[0].request.params.get('view')).toBe('PENDING');
      expect(viewOfSlug(undefined)).toBe(VIEWS[0]);
    });

    it('fetches again when the list changes, and forgets what was said about the last one', async () => {
      await show([pending('a')]);
      await press('a', 'approve');
      open(`${LIST}/a/approve`)[0].flush(approved('a'));
      await settle();
      open(LIST)[0].flush(page([]));
      await settle();
      expect(root.querySelector('.alert--success')).not.toBeNull();

      await router.navigateByUrl('/admin/moderation?vue=retirees');
      await settle();

      expect(open(LIST)[0].request.params.get('view')).toBe('REMOVED');
      expect(root.querySelector('.alert--success')).toBeNull();
    });

    it('renders a card per listing, in the API’s order', async () => {
      await show([pending('a'), pending('b'), pending('c')]);

      expect(cards()).toEqual(['a', 'b', 'c']);
    });

    it.each([
      ['/admin/moderation', 'La file est vide.'],
      ['/admin/moderation?vue=en-ligne', 'Aucune annonce en ligne.'],
      ['/admin/moderation?vue=retirees', 'Aucune annonce retirée.'],
    ])('says what an empty list means: %s', async (url, sentence) => {
      await show([], url);

      expect(text()).toContain(sentence);
    });

    it('says it is loading, and explains a failure with a way to retry', async () => {
      await visit();
      expect(root.querySelector('[aria-busy=true]')?.textContent).toContain('Chargement des annonces');

      open(LIST)[0].flush({ code: ErrorCode.FORBIDDEN }, { status: 403, statusText: 'Forbidden' });
      await settle();
      expect(root.querySelector('.alert--error')).not.toBeNull();
      expect(text()).not.toContain('FORBIDDEN');

      (Array.from(root.querySelectorAll('button')).find((button) => button.textContent?.includes('Réessayer')) as HTMLElement).click();
      await settle();
      open(LIST)[0].flush(page([pending('a')]));
      await settle();
      expect(cards()).toEqual(['a']);
    });
  });

  describe('pages', () => {
    it('asks for the page in the address, counted from one there and from zero by the API', async () => {
      await visit('/admin/moderation?page=3');

      expect(open(LIST)[0].request.params.get('page')).toBe('2');
    });

    it.each(['0', '-2', 'abc', '1.5'])('treats ?page=%s as the first page', async (value) => {
      await visit(`/admin/moderation?page=${value}`);

      expect(open(LIST)[0].request.params.get('page')).toBe('0');
    });

    it('links to the pages on either side, keeping the list', async () => {
      await show([removed('a')], '/admin/moderation?vue=retirees&page=2', { page: 1, totalPages: 3, totalElements: 21 });
      const links = Array.from(root.querySelectorAll('.moderation__pages a')).map((link) => link.getAttribute('href'));

      expect(text()).toContain('Page 2 sur 3');
      expect(links).toEqual(['/admin/moderation?vue=retirees', '/admin/moderation?vue=retirees&page=3']);
    });

    it('shows no pagination for a single page', async () => {
      await show([pending('a')]);

      expect(root.querySelector('.moderation__pages')).toBeNull();
    });

    it('steps back when the last listing of the last page has been decided, keeping the confirmation', async () => {
      await show([pending('z')], '/admin/moderation?page=2', { page: 1, totalPages: 2, totalElements: 11 });
      await press('z', 'approve');
      open(`${LIST}/z/approve`)[0].flush(approved('z'));
      await settle();
      // The page that was being looked at is now empty.
      open(LIST)[0].flush(page([], { page: 1, totalPages: 1, totalElements: 10 }));
      await settle();

      expect(router.url).toBe('/admin/moderation');
      expect(open(LIST)[0].request.params.get('page')).toBe('0');
      expect(root.querySelector('.alert--success')?.textContent).toContain('Annonce approuvée.');
    });
  });

  describe('deciding', () => {
    it('approves with one call, then shows the list as the API now has it', async () => {
      await show([pending('a'), pending('b')]);
      await press('a', 'approve');

      const request = open(`${LIST}/a/approve`)[0];
      expect(request.request.method).toBe('POST');
      request.flush(approved('a'));
      await settle();

      // Not patched locally: asked for again.
      const refetch = open(LIST);
      expect(refetch).toHaveLength(1);
      refetch[0].flush(page([pending('b')]));
      await settle();

      expect(cards()).toEqual(['b']);
      expect(root.querySelector('.alert--success')?.textContent).toContain('Annonce approuvée.');
    });

    it('rejects with the reason and the note', async () => {
      await show([pending('a')]);
      await press('a', 'refuse');
      await choose('a', ModerationReason.SUSPECTED_FRAUD, 'Paiement par mandat.');
      await press('a', 'confirm-refusal');

      const request = open(`${LIST}/a/reject`)[0];
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ reasonCode: 'SUSPECTED_FRAUD', note: 'Paiement par mandat.' });
      request.flush(removed('a'));
      await settle();
      expect(root.querySelector('.alert--success')?.textContent).toContain('Annonce refusée');
    });

    it('sends no note field when none was written', async () => {
      await show([pending('a')]);
      await press('a', 'refuse');
      await choose('a', ModerationReason.DUPLICATE);
      await press('a', 'confirm-refusal');

      expect(open(`${LIST}/a/reject`)[0].request.body).toEqual({ reasonCode: 'DUPLICATE' });
    });

    it('takes down an approved listing through the takedown endpoint, not the rejection one', async () => {
      await show([approved('a')], '/admin/moderation?vue=en-ligne');
      await press('a', 'refuse');
      await choose('a', ModerationReason.PROHIBITED_CONTENT);
      await press('a', 'confirm-refusal');

      expect(open(`${LIST}/a/remove`)[0].request.body).toEqual({ reasonCode: 'PROHIBITED_CONTENT' });
      backend.expectNone(`${LIST}/a/reject`);
    });

    it('reinstates, with the note when there is one', async () => {
      await show([removed('a')], '/admin/moderation?vue=retirees');
      await press('a', 'reinstate');
      const area = card('a').querySelector('textarea') as HTMLTextAreaElement;
      area.value = 'Vérifié.';
      area.dispatchEvent(new Event('input'));
      await settle();
      await press('a', 'confirm-reinstatement');

      const request = open(`${LIST}/a/reinstate`)[0];
      expect(request.request.body).toEqual({ note: 'Vérifié.' });
      request.flush(approved('a'));
      await settle();
      expect(root.querySelector('.alert--success')?.textContent).toContain('Annonce réintégrée.');
    });

    it('sends nothing until a refusal has its reason', async () => {
      await show([pending('a')]);
      await press('a', 'refuse');
      await press('a', 'confirm-refusal');

      backend.expectNone(`${LIST}/a/reject`);
      backend.expectNone(`${LIST}/a/remove`);
    });

    it('marks the card busy and ignores a second decision meanwhile', async () => {
      await show([pending('a'), pending('b')]);
      await press('a', 'approve');

      expect(card('a').getAttribute('aria-busy')).toBe('true');
      expect(card('b').getAttribute('aria-busy')).toBe('false');
      await press('b', 'approve');
      backend.expectNone(`${LIST}/b/approve`);
    });
  });

  describe('when the API refuses a decision', () => {
    it('explains on the card, in French, and refreshes the list', async () => {
      await show([pending('a'), pending('b')]);
      await press('a', 'approve');
      open(`${LIST}/a/approve`)[0].flush(
        { code: ErrorCode.CONFLICT, message: 'Only a listing that is online and awaiting review…' },
        { status: 409, statusText: 'Conflict' },
      );
      await settle();
      // Still in this list as far as the refetch says: the explanation sits on its card.
      open(LIST)[0].flush(page([pending('a'), pending('b')]));
      await settle();

      expect(card('a').querySelector('.alert--error')?.textContent).toContain('Cette action n’est pas possible dans l’état actuel.');
      expect(card('b').querySelector('.alert--error')).toBeNull();
      expect(text()).not.toContain('CONFLICT');
      expect(text()).not.toContain('awaiting review');
    });

    it('explains above the list when the listing has left it', async () => {
      // A colleague rejected it a second ago: the refetch no longer contains it, and the
      // reason the click did nothing must not vanish with the card.
      await show([pending('a'), pending('b')]);
      await press('a', 'approve');
      open(`${LIST}/a/approve`)[0].flush({ code: ErrorCode.CONFLICT }, { status: 409, statusText: 'Conflict' });
      await settle();
      open(LIST)[0].flush(page([pending('b')]));
      await settle();

      expect(cards()).toEqual(['b']);
      const stray = root.querySelector('[data-failure=stray]');
      expect(stray?.textContent).toContain('Cette action n’est pas possible dans l’état actuel.');
      expect(stray?.textContent).toContain('La liste a été actualisée.');
    });

    it('explains a concurrent change', async () => {
      await show([pending('a')]);
      await press('a', 'approve');
      open(`${LIST}/a/approve`)[0].flush({ code: ErrorCode.CONCURRENT_MODIFICATION }, { status: 409, statusText: 'Conflict' });
      await settle();
      open(LIST)[0].flush(page([pending('a')]));
      await settle();

      expect(card('a').querySelector('.alert--error')?.textContent).toContain('modifiée entre-temps');
    });

    it('lets the moderator act again afterwards', async () => {
      await show([pending('a')]);
      await press('a', 'approve');
      open(`${LIST}/a/approve`)[0].flush(null, { status: 500, statusText: 'Error' });
      await settle();
      open(LIST)[0].flush(page([pending('a')]));
      await settle();

      await press('a', 'approve');
      expect(open(`${LIST}/a/approve`)).toHaveLength(1);
    });
  });
});
