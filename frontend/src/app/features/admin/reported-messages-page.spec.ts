import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';

import { BASE_PATH, ErrorCode, MessageReportReason, ReportedMessage, UserRole } from '../../core/api';
import { ReportedMessagesPage } from './reported-messages-page';

const BASE = 'http://api.test';
const LIST = `${BASE}/api/v1/admin/message-reports`;

const report = (id: string, over: Partial<ReportedMessage> = {}): ReportedMessage => ({
  reportId: id,
  reason: MessageReportReason.SCAM,
  note: 'Demande un virement',
  reportedAt: '2026-10-21T10:00:00Z',
  reporter: { id: 'seller', displayName: 'Garage Bellevue', role: UserRole.PROFESSIONNEL },
  sender: { id: 'buyer', displayName: 'Karim D.', role: UserRole.PARTICULIER },
  listing: { id: 'l1', title: 'Peugeot 308', online: true },
  messageId: `m-${id}`,
  context: [
    { id: 'm0', senderId: 'seller', body: 'Bonjour, oui.', hidden: false, reported: false, sentAt: '2026-10-21T08:00:00Z' },
    { id: `m-${id}`, senderId: 'buyer', body: 'Payez par mandat <b>cash</b>', hidden: false, reported: true, sentAt: '2026-10-21T09:00:00Z' },
  ],
  ...over,
});

const page = (items: ReportedMessage[]) => ({ items, page: 0, size: 10, totalElements: items.length, totalPages: 1, hasNext: false });

/**
 * Reported messages, for a moderator: the reason, the note, the reported message among the
 * ones around it, and the two decisions, each of which reaches its own endpoint.
 */
describe('ReportedMessagesPage', () => {
  let harness: RouterTestingHarness;
  let backend: HttpTestingController;
  let root: HTMLElement;

  const settle = () => harness.fixture.whenStable();
  const open = (url: string): TestRequest[] =>
    backend.match((request) => request.url === url).filter((request) => !request.cancelled);
  const press = async (label: string) => {
    (Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as HTMLButtonElement).click();
    await settle();
  };

  async function show(items: ReportedMessage[]): Promise<void> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'admin/moderation/messages', component: ReportedMessagesPage }]),
        { provide: BASE_PATH, useValue: BASE },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create('/admin/moderation/messages');
    root = harness.fixture.nativeElement;
    await settle();
    open(LIST)[0].flush(page(items));
    await settle();
  }

  it('shows why, who reported it, and the reported message among those around it', async () => {
    await show([report('r1')]);
    const card = root.querySelector('.report-card') as HTMLElement;

    expect(card.querySelector('h2')?.textContent?.trim()).toBe('Arnaque ou demande de paiement suspecte');
    // With its spaces: the sentence is not three pieces run together.
    const words = card.textContent?.replace(/\s+/g, ' ');
    expect(words).toContain('Signalé par Garage Bellevue le');
    expect(words).toContain('Au sujet de Peugeot 308');
    expect(card.querySelector('.report-card__note')?.textContent).toContain('Demande un virement');
    const reported = card.querySelector('.context--reported') as HTMLElement;
    expect(reported.textContent).toContain('Karim D.');
    // As text: a moderator reading a report is not to be handed markup either.
    expect(reported.querySelector('b')).toBeNull();
    expect(reported.querySelector('.context__body')?.textContent).toBe('Payez par mandat <b>cash</b>');
  });

  it('is the fourth tab of moderation, and the one marked current', async () => {
    await show([report('r1')]);
    const tabs = Array.from(root.querySelectorAll('.moderation__view'));

    expect(tabs).toHaveLength(4);
    expect(tabs[3].getAttribute('aria-current')).toBe('page');
    expect(tabs[3].textContent).toContain('Messages signalés (1)');
  });

  it('hides a message through its own endpoint, says what that did, and fetches the queue again', async () => {
    await show([report('r1')]);
    await press('Masquer le message');

    const hide = open(`${LIST}/r1/hide`)[0];
    expect(hide.request.method).toBe('POST');
    hide.flush(null);
    await settle();
    open(LIST)[0].flush(page([]));
    await settle();

    expect(root.querySelector('.alert--success')?.textContent).toContain('Message masqué');
    expect(root.textContent).toContain('Aucun message signalé en attente.');
  });

  it('dismisses a report, leaving the message', async () => {
    await show([report('r1')]);
    await press('Classer sans suite');

    open(`${LIST}/r1/dismiss`)[0].flush(null);
    await settle();

    expect(root.querySelector('.alert--success')?.textContent).toContain('classé sans suite');
  });

  it('says so on the card when another moderator decided it first', async () => {
    await show([report('r1')]);
    await press('Masquer le message');

    open(`${LIST}/r1/hide`)[0].flush({ code: ErrorCode.CONFLICT }, { status: 409, statusText: 'Conflict' });
    await settle();

    expect(root.querySelector('.report-card .alert--error')).not.toBeNull();
  });
});
