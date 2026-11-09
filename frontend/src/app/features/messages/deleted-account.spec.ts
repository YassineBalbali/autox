import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ConversationDetail, ConversationRole, ConversationSummary, UserRole } from '../../core/api';
import { InboxItem } from './inbox-item';
import { MessageThread } from './message-thread';

/**
 * A conversation after the other party deleted their account: it stays, under « Compte
 * supprimé », their messages without their text, and the listing perhaps gone with them.
 */
describe('a conversation with a deleted account', () => {
  function configure(): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: 'http://api.test' }],
    });
  }

  it('shows an erased message as erased, and offers no report on it', async () => {
    configure();
    const fixture = TestBed.createComponent(MessageThread);
    const conversation: ConversationDetail = {
      id: 'c-1',
      role: ConversationRole.BUYER,
      counterpart: { id: 'gone', displayName: 'Compte supprimé', role: UserRole.PARTICULIER },
      canReply: false,
      earlierOmitted: false,
      messages: [
        { id: 'm-1', sentByMe: true, body: 'Toujours disponible ?', hidden: false, erased: false, reportedByMe: false, sentAt: '2026-11-08T09:00:00Z' },
        { id: 'm-2', sentByMe: false, hidden: false, erased: true, reportedByMe: false, sentAt: '2026-11-08T09:05:00Z' },
      ],
    };
    fixture.componentRef.setInput('conversation', conversation);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;

    const erased = root.querySelector('[data-erased]');
    expect(erased?.textContent?.trim()).toBe('Message effacé : son auteur a supprimé son compte.');
    expect(erased?.closest('li')?.querySelector('.bubble__report')).toBeNull();
    expect(root.textContent).toContain('Compte supprimé :');
    expect(root.textContent).toContain('Toujours disponible ?');
  });

  it('says in the inbox that the listing is gone and the last message was erased', async () => {
    configure();
    const fixture = TestBed.createComponent(InboxItem);
    const item: ConversationSummary = {
      id: 'c-1',
      role: ConversationRole.BUYER,
      counterpart: { id: 'gone', displayName: 'Compte supprimé', role: UserRole.PARTICULIER },
      lastMessage: { sentByMe: false, hidden: false, erased: true, sentAt: '2026-11-08T09:05:00Z' },
      unreadCount: 0,
      canReply: false,
      lastMessageAt: '2026-11-08T09:05:00Z',
    };
    fixture.componentRef.setInput('item', item);
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;

    expect(root.querySelector('[data-listing-gone]')?.textContent?.trim()).toBe('Annonce supprimée');
    expect(root.querySelector('.item__preview')?.textContent).toContain('Message effacé');
    expect(root.querySelector('.item__who')?.textContent?.trim()).toBe('Compte supprimé');
  });
});
