import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import {
  BASE_PATH,
  ConversationDetail,
  ConversationRole,
  ConversationSummary,
  ErrorCode,
  MessageReportReason,
  UserRole,
} from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { StoredTokens, TOKEN_STORE } from '../../core/auth/token-store';
import { ConversationView } from './conversation-view';
import { MessagesPage } from './messages-page';
import { NoConversation } from './no-conversation';

const BASE = 'http://api.test';
const INBOX = `${BASE}/api/v1/conversations`;
const UNREAD = `${BASE}/api/v1/conversations/unread`;
const conversationUrl = (id: string) => `${BASE}/api/v1/conversations/${id}`;

const SESSION: StoredTokens = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  accessTokenExpiresAt: Date.now() + 900_000,
};

const summary = (id: string, over: Partial<ConversationSummary> = {}): ConversationSummary => ({
  id,
  role: ConversationRole.BUYER,
  listing: { id: `l-${id}`, title: `Peugeot 308 ${id}`, priceAmount: 1_849_000, currencyCode: 'EUR', online: true },
  counterpart: { id: 's1', displayName: 'Garage Bellevue', role: UserRole.PROFESSIONNEL },
  lastMessage: { preview: 'Oui, toujours disponible.', sentByMe: false, hidden: false, sentAt: '2026-10-21T09:00:00Z' },
  unreadCount: 0,
  canReply: true,
  lastMessageAt: '2026-10-21T09:00:00Z',
  ...over,
});

const detail = (id: string, over: Partial<ConversationDetail> = {}): ConversationDetail => ({
  id,
  role: ConversationRole.BUYER,
  listing: summary(id).listing,
  counterpart: summary(id).counterpart,
  canReply: true,
  earlierOmitted: false,
  messages: [
    { id: 'm1', sentByMe: true, body: 'Bonjour, est-elle disponible ?', hidden: false, reportedByMe: false, sentAt: '2026-10-21T08:00:00Z' },
    { id: 'm2', sentByMe: false, body: 'Oui, toujours disponible.', hidden: false, reportedByMe: false, sentAt: '2026-10-21T09:00:00Z' },
  ],
  ...over,
});

const page = (items: ConversationSummary[]) => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: 1,
  hasNext: false,
});

/**
 * The inbox and the conversation beside it.
 *
 * <p>What matters: both sides' conversations are listed with what is unread, opening one
 * marks it read and updates the list and the header at once, a reply is sent and shown,
 * a received message can be reported, a hidden one shows a notice in its place, and nothing
 * anybody writes is ever treated as markup.
 */
describe('MessagesPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const text = () => (root.textContent ?? '').replace(/\s+/g, ' ');

  async function visit(url: string, items: ConversationSummary[]): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(
          [
            {
              path: 'messages',
              component: MessagesPage,
              children: [
                { path: '', component: NoConversation },
                { path: ':id', component: ConversationView },
              ],
            },
          ],
          withComponentInputBinding(),
        ),
        { provide: BASE_PATH, useValue: BASE },
        { provide: TOKEN_STORE, useValue: { read: () => SESSION, write: () => undefined, clear: () => undefined } },
      ],
    });
    TestBed.inject(AuthService).restore();
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create(url);
    root = harness.fixture.nativeElement;
    await settle();
    open(UNREAD).forEach((request) => request.flush({ conversations: 0 }));
    open(`${BASE}/api/v1/reference/countries`).forEach((request) =>
      request.flush([{ code: 'FR', currencyCode: 'EUR', currencyMinorUnit: 2 }]),
    );
    open(INBOX).forEach((request) => request.flush(page(items)));
    await settle();
  }

  async function opened(id: string, conversation = detail(id)): Promise<void> {
    open(conversationUrl(id))[0].flush(conversation);
    await settle();
  }

  describe('the list', () => {
    it('lists each conversation: who, which car, the last words, and when', async () => {
      await visit('/messages', [summary('c1')]);
      const line = root.querySelector('app-inbox-item a') as HTMLAnchorElement;

      expect(line.getAttribute('href')).toBe('/messages/c1');
      expect(line.textContent).toContain('Garage Bellevue');
      expect(line.textContent).toContain('Peugeot 308 c1');
      expect(line.textContent).toContain('Oui, toujours disponible.');
    });

    it('marks what is unread, in numbers and in words', async () => {
      await visit('/messages', [summary('c1', { unreadCount: 2 })]);
      const line = root.querySelector('app-inbox-item a') as HTMLElement;

      expect(line.classList).toContain('item--unread');
      expect(line.querySelector('.count-badge')?.textContent?.trim()).toBe('2');
      expect(line.textContent).toContain('2 messages non lus');
    });

    it('says which side the user is on, and what they last wrote', async () => {
      await visit('/messages', [
        summary('c1', {
          role: ConversationRole.SELLER,
          lastMessage: { preview: 'Je passe samedi.', sentByMe: true, hidden: false, sentAt: '2026-10-21T09:00:00Z' },
        }),
        summary('c2', { lastMessage: { hidden: true, sentByMe: false, sentAt: '2026-10-21T09:00:00Z' } }),
      ]);
      const [first, second] = Array.from(root.querySelectorAll('app-inbox-item'));

      expect(first.textContent).toContain('Votre annonce ·');
      expect(first.textContent).toContain('Vous : Je passe samedi.');
      expect(second.textContent).toContain('Message masqué par la modération');
    });

    it('explains how a conversation starts when there is none', async () => {
      await visit('/messages', []);

      expect(text()).toContain('Aucune conversation pour l’instant.');
      expect(text()).toContain('« Contacter le vendeur »');
      expect(root.querySelector('a[href="/recherche"]')).not.toBeNull();
    });
  });

  describe('a conversation', () => {
    it('shows the messages, each side on its own side, with the listing above', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');

      const bubbles = Array.from(root.querySelectorAll('.bubble'));
      expect(bubbles.map((bubble) => bubble.classList.contains('bubble--mine'))).toEqual([true, false]);
      expect(root.querySelector('.conversation__listing-title')?.getAttribute('href')).toBe('/annonces/l-c1');
      expect(root.querySelector('app-inbox-item a')?.getAttribute('aria-current')).toBe('page');
    });

    it('marks it read once open, and the list and the header follow', async () => {
      await visit('/messages/c1', [summary('c1', { unreadCount: 1 })]);
      await opened('c1');

      const read = open(`${conversationUrl('c1')}/read`)[0];
      expect(read.request.method).toBe('POST');
      read.flush(null);
      await settle();

      expect(root.querySelector('app-inbox-item .count-badge')).toBeNull();
      expect(open(UNREAD)).toHaveLength(1);
    });

    it('shows what was written as text, never as markup', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1', detail('c1', {
        messages: [{ id: 'm1', sentByMe: false, body: '<img src=x onerror=alert(1)>\nLigne 2', hidden: false, sentAt: '2026-10-21T09:00:00Z' }],
      }));

      const body = root.querySelector('.bubble__body') as HTMLElement;
      expect(body.querySelector('img')).toBeNull();
      expect(body.textContent).toBe('<img src=x onerror=alert(1)>\nLigne 2');
    });

    it('sends a reply, shows it, and moves the conversation to the top of the list', async () => {
      await visit('/messages/c2', [summary('c1'), summary('c2')]);
      await opened('c2');
      const area = root.querySelector('#composer-message') as HTMLTextAreaElement;
      area.value = '  Je peux passer samedi.  ';
      area.dispatchEvent(new Event('input'));
      await settle();

      (root.querySelector('.composer__send') as HTMLButtonElement).click();
      const reply = open(`${conversationUrl('c2')}/messages`)[0];
      expect(reply.request.body).toEqual({ body: 'Je peux passer samedi.' });
      const after = detail('c2');
      reply.flush({
        ...after,
        messages: [...after.messages!, { id: 'm3', sentByMe: true, body: 'Je peux passer samedi.', hidden: false, sentAt: '2026-10-21T10:00:00Z' }],
      });
      await settle();

      expect(root.querySelectorAll('.bubble')).toHaveLength(3);
      expect(area.value).toBe('');
      expect(root.querySelector('app-inbox-item')?.textContent).toContain('Vous : Je peux passer samedi.');
    });

    it('sends with Ctrl+Entrée, and keeps Entrée for a new line', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');
      const area = root.querySelector('#composer-message') as HTMLTextAreaElement;
      area.value = 'Bonjour';
      area.dispatchEvent(new Event('input'));
      await settle();

      area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      expect(open(`${conversationUrl('c1')}/messages`)).toHaveLength(0);

      area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }));
      expect(open(`${conversationUrl('c1')}/messages`)).toHaveLength(1);
    });

    it('warns each side of its own risk under the reply box', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');
      expect(root.querySelector('#composer-hint')?.textContent).toContain('Ne payez jamais un véhicule avant de l’avoir vu');

      await visit('/messages/c2', [summary('c2', { role: ConversationRole.SELLER })]);
      await opened('c2', detail('c2', { role: ConversationRole.SELLER }));
      expect(root.querySelector('#composer-hint')?.textContent).toContain('faites vérifier tout chèque de banque');
    });

    it('explains a closed conversation instead of offering a reply that would be refused', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1', detail('c1', { canReply: false }));

      expect(root.querySelector('.composer')).toBeNull();
      expect(text()).toContain('Cette conversation est fermée');
    });

    it('says why a reply was refused', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');
      const area = root.querySelector('#composer-message') as HTMLTextAreaElement;
      area.value = 'Toujours là ?';
      area.dispatchEvent(new Event('input'));
      await settle();
      (root.querySelector('.composer__send') as HTMLButtonElement).click();

      open(`${conversationUrl('c1')}/messages`)[0].flush({ code: ErrorCode.CONFLICT }, { status: 409, statusText: 'Conflict' });
      await settle();

      expect(root.querySelector('.composer .alert--error')?.textContent).toContain('Cette conversation est fermée');
    });

    it('shows a hidden message as a notice, with nothing to report', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1', detail('c1', {
        messages: [{ id: 'm1', sentByMe: false, hidden: true, reportedByMe: true, sentAt: '2026-10-21T09:00:00Z' }],
      }));

      expect(root.querySelector('.bubble--hidden')?.textContent).toContain('Message masqué par la modération.');
      expect(root.querySelector('.bubble__report')).toBeNull();
    });
  });

  describe('reporting', () => {
    it('is offered on received messages only', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');

      const reportable = Array.from(root.querySelectorAll('.bubble')).map((bubble) => !!bubble.querySelector('.bubble__report'));
      expect(reportable).toEqual([false, true]);
    });

    it('sends the reason and note, then marks the message reported', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');
      (root.querySelector('.bubble__report') as HTMLButtonElement).click();
      await settle();

      const submit = root.querySelector('app-report-form button[type=submit]') as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
      const scam = root.querySelector('input[value=SCAM]') as HTMLInputElement;
      scam.click();
      const note = root.querySelector('app-report-form textarea') as HTMLTextAreaElement;
      note.value = 'Demande un virement avant la visite';
      note.dispatchEvent(new Event('input'));
      await settle();
      submit.click();

      const report = open(`${BASE}/api/v1/messages/m2/report`)[0];
      expect(report.request.body).toEqual({ reason: MessageReportReason.SCAM, note: 'Demande un virement avant la visite' });
      report.flush(null);
      await settle();

      expect(root.querySelector('app-report-form')).toBeNull();
      expect(root.querySelector('.bubble__reported')?.textContent).toContain('Signalé');
    });

    it('asks for a note when the reason is "Autre raison"', async () => {
      await visit('/messages/c1', [summary('c1')]);
      await opened('c1');
      (root.querySelector('.bubble__report') as HTMLButtonElement).click();
      await settle();
      (root.querySelector('input[value=OTHER]') as HTMLInputElement).click();
      await settle();

      expect((root.querySelector('app-report-form button[type=submit]') as HTMLButtonElement).disabled).toBe(true);
      expect((root.querySelector('app-report-form textarea') as HTMLTextAreaElement).placeholder).toContain('obligatoire');
    });
  });
});
