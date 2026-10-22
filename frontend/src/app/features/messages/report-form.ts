import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';

import { MessageReportReason, MessagesService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Alert } from '../../shared/ui/alert';
import { REPORT_REASONS } from './messaging';

/**
 * Reporting a received message: why, and a note. The note is required for "Autre raison",
 * which on its own tells a moderator nothing, and optional otherwise.
 *
 * <p>The message is not changed or removed by reporting it. A moderator reads it in context
 * and decides; the form says so, so nobody expects it to vanish.
 */
@Component({
  selector: 'app-report-form',
  imports: [Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="report" (submit)="submit($event)" [attr.aria-labelledby]="'report-title-' + messageId()">
      <p class="report__title" [id]="'report-title-' + messageId()" i18n="@@reportTitle">Signaler ce message</p>
      <fieldset class="report__reasons">
        <legend class="visually-hidden" i18n="@@reportReason">Raison du signalement</legend>
        @for (option of reasons; track option.reason) {
          <label class="report__reason">
            <input
              type="radio"
              [name]="'report-reason-' + messageId()"
              [value]="option.reason"
              [checked]="reason() === option.reason"
              (change)="reason.set(option.reason)"
            />
            {{ option.label }}
          </label>
        }
      </fieldset>
      <label class="visually-hidden" [for]="'report-note-' + messageId()" i18n="@@reportNoteLabel">Précisions</label>
      <textarea
        class="input report__note"
        rows="2"
        maxlength="500"
        [id]="'report-note-' + messageId()"
        [placeholder]="noteRequired() ? 'Expliquez ce qui ne va pas (obligatoire)' : 'Précisions (facultatif)'"
        [value]="note()"
        (input)="note.set($any($event.target).value)"
      ></textarea>
      <p class="meta report__hint" i18n="@@reportHint">
        Le message reste visible jusqu’à la décision d’un modérateur. L’autre personne n’est pas prévenue.
      </p>
      @if (failure()) {
        <app-alert kind="error">{{ failure() }}</app-alert>
      }
      <div class="report__actions">
        <button class="btn btn--danger btn--sm" type="submit" [disabled]="!canSubmit()" i18n="@@reportSubmit">
          Signaler
        </button>
        <button class="btn btn--quiet btn--sm" type="button" (click)="cancelled.emit()" i18n="@@cancel">Annuler</button>
      </div>
    </form>
  `,
  styles: `
    .report {
      display: grid;
      gap: var(--s-2);
      margin-top: var(--s-2);
      padding: var(--s-3);
      border: var(--border);
      border-radius: var(--r-md);
      background: var(--c-surface);
      color: var(--c-ink);
      text-align: left;
    }

    .report__title {
      margin: 0;
      font-size: var(--t-sm);
      font-weight: var(--fw-bold);
    }

    .report__reasons {
      display: grid;
      gap: var(--s-1);
      margin: 0;
      padding: 0;
      border: 0;
    }

    .report__reason {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      font-size: var(--t-sm);
    }

    .report__note {
      resize: vertical;
    }

    .report__hint {
      margin: 0;
    }

    .report__actions {
      display: flex;
      gap: var(--s-2);
    }
  `,
})
export class ReportForm {
  private readonly api = inject(MessagesService);
  private readonly errors = inject(ErrorMessageService);

  readonly messageId = input.required<string>();
  readonly reported = output<void>();
  readonly cancelled = output<void>();

  protected readonly reasons = REPORT_REASONS;
  protected readonly reason = signal<MessageReportReason | null>(null);
  protected readonly note = signal('');
  protected readonly sending = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected readonly noteRequired = computed(() => this.reason() === MessageReportReason.OTHER);
  protected readonly canSubmit = computed(
    () => !!this.reason() && (!this.noteRequired() || this.note().trim().length > 0) && !this.sending(),
  );

  protected submit(event: Event): void {
    event.preventDefault();
    const reason = this.reason();
    if (!this.canSubmit() || !reason) {
      return;
    }
    this.sending.set(true);
    this.failure.set(null);
    const note = this.note().trim();
    this.api
      .report({ id: this.messageId(), reportMessageRequest: { reason, ...(note ? { note } : {}) } })
      .subscribe({
        next: () => {
          this.sending.set(false);
          this.reported.emit();
        },
        error: (error: unknown) => {
          this.sending.set(false);
          this.failure.set(this.errors.translate(error).message);
        },
      });
  }
}
