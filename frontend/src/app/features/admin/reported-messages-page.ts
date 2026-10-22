import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { AdminService, ReportedMessage } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { SellerBadge } from '../../shared/listing/seller-badge';
import { Alert } from '../../shared/ui/alert';
import { REPORT_REASONS, sentAtFull } from '../messages/messaging';
import { VIEWS } from './moderation-page';

const PAGE_SIZE = 10;

/**
 * The reported messages: each with the reason, the reporter's note, and the messages around
 * it, which are the only part of a conversation a moderator ever sees.
 *
 * <p>Two decisions. Hiding the message replaces its text with a notice on both sides — the
 * text is kept, for the record. Dismissing leaves it as it was. Either one closes the report.
 */
@Component({
  selector: 'app-reported-messages-page',
  imports: [RouterLink, Alert, SellerBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reported-messages-page.html',
  // The page's frame, tabs included, is the listing moderation page's.
  styleUrls: ['./moderation-page.scss', './reported-messages-page.scss'],
})
export class ReportedMessagesPage {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);

  protected readonly views = VIEWS;
  protected readonly sentAtFull = sentAtFull;

  protected readonly items = signal<ReportedMessage[]>([]);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);

  /** The report being decided, so its buttons are disabled while the call is out. */
  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly failures = signal<Readonly<Record<string, string>>>({});

  protected readonly hasPrevious = computed(() => this.page() > 0);
  protected readonly hasNext = computed(() => this.page() + 1 < this.totalPages());

  constructor() {
    this.load();
  }

  protected reasonLabel(item: ReportedMessage): string {
    return REPORT_REASONS.find((option) => option.reason === item.reason)?.label ?? '';
  }

  protected senderName(item: ReportedMessage, senderId: string | undefined): string {
    return senderId === item.sender?.id ? (item.sender?.displayName ?? '') : (item.reporter?.displayName ?? '');
  }

  protected goTo(page: number): void {
    this.page.set(page);
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.api.openReports({ page: this.page(), size: PAGE_SIZE }).subscribe({
      next: (page) => {
        const items = page.items ?? [];
        if (items.length === 0 && this.page() > 0) {
          // The last report on the last page was just decided: step back rather than show nothing.
          this.goTo(this.page() - 1);
          return;
        }
        this.items.set(items);
        this.total.set(page.totalElements ?? 0);
        this.totalPages.set(page.totalPages ?? 0);
        this.state.set('ready');
      },
      error: (error: unknown) => {
        this.loadFailure.set(this.errors.translate(error).message);
        this.state.set('error');
      },
    });
  }

  protected hide(item: ReportedMessage): void {
    this.decide(item, (id) => this.api.hide({ id }), 'Message masqué. Les deux personnes voient désormais un avis à sa place.');
  }

  protected dismiss(item: ReportedMessage): void {
    this.decide(item, (id) => this.api.dismiss({ id }), 'Signalement classé sans suite. Le message reste visible.');
  }

  private decide(item: ReportedMessage, call: (id: string) => Observable<unknown>, done: string): void {
    const id = item.reportId;
    if (!id || this.busy() !== null) {
      return;
    }
    this.busy.set(id);
    this.notice.set(null);
    this.failures.update(({ [id]: _, ...rest }) => rest);
    call(id).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(done);
        this.load();
      },
      error: (error: unknown) => {
        this.busy.set(null);
        this.failures.update((failures) => ({ ...failures, [id]: this.errors.translate(error).message }));
      },
    });
  }
}
