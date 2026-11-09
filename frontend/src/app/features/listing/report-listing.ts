import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked } from '@angular/core';

import { ErrorCode, ListingReportReason, ListingsService } from '../../core/api';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { LISTING_REPORT_REASONS } from '../../shared/listing/listing-report.fr';
import { Alert } from '../../shared/ui/alert';

/**
 * "Signaler cette annonce", under the description, for a signed-in visitor who is not its seller.
 *
 * <p>A reason, and a note that "Autre" requires. A listing is reported once per person: the
 * button gives way to a line saying so, whether the report was made now or on an earlier visit.
 * The listing does not change; a moderator decides, and the form says that too.
 */
@Component({
  selector: 'app-report-listing',
  imports: [Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (reported()) {
      <p class="meta" data-reported>
        @if (justReported()) {
          Merci : l’annonce a été signalée aux modérateurs.
        } @else {
          Vous avez signalé cette annonce.
        }
      </p>
    } @else if (!open()) {
      <button class="btn btn--quiet btn--sm report-open" type="button" data-action="report-listing" (click)="open.set(true)">
        Signaler cette annonce
      </button>
    } @else {
      <form class="report" (submit)="submit($event)" aria-labelledby="signaler-annonce">
        <p class="report__title" id="signaler-annonce">Signaler cette annonce</p>
        <fieldset class="report__reasons">
          <legend class="visually-hidden">Motif du signalement</legend>
          @for (option of reasons; track option.reason) {
            <label class="report__reason">
              <input
                type="radio"
                name="listing-report-reason"
                [value]="option.reason"
                [checked]="reason() === option.reason"
                (change)="reason.set(option.reason)"
              />
              {{ option.label }}
            </label>
          }
        </fieldset>
        <label class="visually-hidden" for="listing-report-note">Précisions</label>
        <textarea
          id="listing-report-note"
          class="input report__note"
          rows="3"
          maxlength="1000"
          [placeholder]="noteRequired() ? 'Expliquez ce qui ne va pas (obligatoire)' : 'Précisions (facultatif)'"
          [value]="note()"
          (input)="note.set($any($event.target).value)"
        ></textarea>
        <p class="meta report__hint">
          L’annonce reste en ligne jusqu’à la décision d’un modérateur. Le vendeur ne sait pas qui l’a signalée.
        </p>
        @if (failure()) {
          <app-alert kind="error">{{ failure() }}</app-alert>
        }
        <div class="report__actions">
          <button class="btn btn--danger btn--sm" type="submit" data-action="send-report" [disabled]="!canSubmit()">Signaler</button>
          <button class="btn btn--quiet btn--sm" type="button" (click)="open.set(false)">Annuler</button>
        </div>
      </form>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    .report {
      display: grid;
      gap: var(--s-2);
      max-width: 34rem;
      padding: var(--s-3);
      border: var(--border);
      border-radius: var(--r-md);
      background: var(--c-surface);
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
      min-height: 32px;
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
export class ReportListing {
  private readonly api = inject(ListingsService);
  private readonly errors = inject(ErrorMessageService);

  readonly listingId = input.required<string>();

  protected readonly reasons = LISTING_REPORT_REASONS;
  protected readonly open = signal(false);
  protected readonly reason = signal<ListingReportReason | null>(null);
  protected readonly note = signal('');
  protected readonly sending = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly reported = signal(false);
  protected readonly justReported = signal(false);

  protected readonly noteRequired = computed(() => this.reason() === ListingReportReason.OTHER);
  protected readonly canSubmit = computed(
    () => !!this.reason() && (!this.noteRequired() || this.note().trim().length > 0) && !this.sending(),
  );

  constructor() {
    // Whether this visitor already reported it, on an earlier visit. A failure here leaves the
    // button: the server still refuses a second report, and the form then says so.
    effect(() => {
      const id = this.listingId();
      untracked(() => {
        this.reported.set(false);
        this.justReported.set(false);
        this.open.set(false);
        this.api.listingReportStatus({ listingId: id }).subscribe({
          next: (status) => this.reported.set(!!status.reported),
          error: () => undefined,
        });
      });
    });
  }

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
      .reportListing({ listingId: this.listingId(), reportListingRequest: { reason, ...(note ? { note } : {}) } })
      .subscribe({
        next: () => {
          this.sending.set(false);
          this.justReported.set(true);
          this.reported.set(true);
        },
        error: (error: unknown) => {
          this.sending.set(false);
          const translated = this.errors.translate(error);
          if (translated.code === ErrorCode.LISTING_ALREADY_REPORTED) {
            this.reported.set(true);
            return;
          }
          this.failure.set(translated.message);
        },
      });
  }
}
