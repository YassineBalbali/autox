import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { ConversationDetail, MessageView } from '../../core/api';
import { Icon } from '../../shared/ui/icon';
import { sentAtFull } from './messaging';
import { ReportForm } from './report-form';

const DAY = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const TIME = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' });

/** One day's messages, under the day's name. */
interface Day {
  readonly key: string;
  readonly label: string;
  readonly messages: readonly MessageView[];
}

/**
 * The messages of a conversation, by day: the other side's on the left, the reader's on the
 * right, a hidden one as a notice in its place.
 *
 * <p>Each message is text, interpolated and never bound as HTML, so nothing anybody sends
 * becomes markup on the other side; line breaks are kept by the stylesheet. A received
 * message can be reported from here, once. None can be edited or deleted.
 */
@Component({
  selector: 'app-message-thread',
  imports: [Icon, ReportForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './message-thread.html',
  styleUrl: './message-thread.scss',
})
export class MessageThread {
  readonly conversation = input.required<ConversationDetail>();
  /** A message was reported; the id is the message's. */
  readonly reported = output<string>();

  /** The message whose report form is open, if any. */
  protected readonly reporting = signal<string | null>(null);

  protected readonly sentAtFull = sentAtFull;

  protected readonly days = computed<Day[]>(() => {
    const days: { key: string; label: string; messages: MessageView[] }[] = [];
    for (const message of this.conversation().messages ?? []) {
      const date = new Date(message.sentAt ?? '');
      const key = Number.isNaN(date.getTime()) ? '' : date.toDateString();
      if (days.at(-1)?.key !== key) {
        days.push({ key, label: dayLabel(date), messages: [] });
      }
      days.at(-1)!.messages.push(message);
    }
    return days;
  });

  protected time(iso: string | undefined): string {
    const date = new Date(iso ?? '');
    return Number.isNaN(date.getTime()) ? '' : TIME.format(date);
  }

  protected markReported(messageId: string): void {
    this.reporting.set(null);
    this.reported.emit(messageId);
  }
}

/** "Aujourd’hui", "Hier", or "mardi 21 octobre 2026". */
export function dayLabel(date: Date, now = new Date()): string {
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startOfDay = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const days = Math.round((startOfToday - startOfDay) / 86_400_000);
  if (days === 0) {
    return 'Aujourd’hui';
  }
  if (days === 1) {
    return 'Hier';
  }
  return DAY.format(date);
}
