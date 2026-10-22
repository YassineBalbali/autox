import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BASE_PATH } from '../api';
import { AuthService } from '../auth/auth.service';
import { StoredTokens, TOKEN_STORE } from '../auth/token-store';
import { UNREAD_POLL_MS, UnreadMessages } from './unread-messages';

const BASE = 'http://api.test';
const UNREAD = `${BASE}/api/v1/conversations/unread`;

const SESSION: StoredTokens = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  accessTokenExpiresAt: Date.now() + 900_000,
};

/**
 * The number on the header's "Messages" link: asked for on sign-in, then once a minute
 * while the tab is in view — and not at all while it is hidden, or for nobody.
 */
describe('UnreadMessages', () => {
  let backend: HttpTestingController;
  let unread: UnreadMessages;

  const pending = (): TestRequest[] => backend.match(UNREAD);

  function start(signedIn: boolean): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: BASE_PATH, useValue: BASE },
        {
          provide: TOKEN_STORE,
          useValue: { read: () => (signedIn ? SESSION : null), write: () => undefined, clear: () => undefined },
        },
      ],
    });
    TestBed.inject(AuthService).restore();
    backend = TestBed.inject(HttpTestingController);
    unread = TestBed.inject(UnreadMessages);
    TestBed.tick();
  }

  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  });

  it('asks as soon as there is a session, and shows the answer', () => {
    start(true);

    pending()[0].flush({ conversations: 3 });

    expect(unread.count()).toBe(3);
  });

  it('asks nothing, and counts nothing, for a visitor who is not signed in', () => {
    start(false);
    vi.advanceTimersByTime(UNREAD_POLL_MS * 3);

    expect(pending()).toHaveLength(0);
    expect(unread.count()).toBe(0);
  });

  it('asks again every minute while the tab is in view', () => {
    start(true);
    pending()[0].flush({ conversations: 0 });

    vi.advanceTimersByTime(UNREAD_POLL_MS);
    pending()[0].flush({ conversations: 1 });

    expect(unread.count()).toBe(1);
  });

  it('does not ask while the tab is hidden, and asks the moment it is shown again', () => {
    start(true);
    pending()[0].flush({ conversations: 0 });

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    vi.advanceTimersByTime(UNREAD_POLL_MS * 5);
    expect(pending()).toHaveLength(0);

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(pending()).toHaveLength(1);
  });

  it('keeps the last count when a poll fails: a missed tick is not news', () => {
    start(true);
    pending()[0].flush({ conversations: 2 });

    unread.refresh();
    pending()[0].flush(null, { status: 503, statusText: 'Unavailable' });

    expect(unread.count()).toBe(2);
  });
});
