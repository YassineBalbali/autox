import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ConversationRole, ConversationSummary, UserRole } from '../../core/api';
import { InboxItem } from './inbox-item';

/** A conversation a visitor started from a dealer's page: about no listing, and not a deleted one. */
describe('a conversation from a dealer’s page', () => {
  async function inbox(role: ConversationRole): Promise<HTMLElement> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), { provide: BASE_PATH, useValue: 'http://api.test' }],
    });
    const fixture = TestBed.createComponent(InboxItem);
    const item: ConversationSummary = {
      id: 'c-1',
      role,
      counterpart: { id: 'u-2', displayName: role === ConversationRole.SELLER ? 'Camille R.' : 'Garage Bellevue', role: UserRole.PARTICULIER },
      lastMessage: { preview: 'Ouverts samedi ?', sentByMe: false, hidden: false, erased: false, sentAt: '2026-11-08T09:05:00Z' },
      unreadCount: 1,
      canReply: true,
      lastMessageAt: '2026-11-08T09:05:00Z',
      dealerPage: { slug: 'garage-bellevue' },
    };
    fixture.componentRef.setInput('item', item);
    await fixture.whenStable();
    return fixture.nativeElement;
  }

  it('says where the dealer was written from, and not that a listing was deleted', async () => {
    const dealer = await inbox(ConversationRole.SELLER);
    expect(dealer.querySelector('[data-dealer-page]')?.textContent?.trim()).toBe('Depuis votre page professionnelle');
    expect(dealer.querySelector('[data-listing-gone]')).toBeNull();
    expect(dealer.textContent).not.toContain('Votre annonce');

    const visitor = await inbox(ConversationRole.BUYER);
    expect(visitor.querySelector('[data-dealer-page]')?.textContent?.trim()).toBe('Depuis sa page professionnelle');
  });
});
