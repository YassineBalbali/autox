import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AdminService, ModerationReason, PendingListing } from '../../../core/api';
import { ErrorMessageService } from '../../../core/errors/error-message.service';
import { MODERATION_REASONS, MODERATION_REASON_LABELS, reasonRequiresNote } from '../../../shared/listing/moderation.fr';
import { formatPrice } from '../../../shared/listing/price';
import { Icon } from '../../../shared/ui/icon';
import { waitingFr } from './dashboard-model';

/** What happened to a listing from here, for the page to update its counts. */
export interface Moderated {
  readonly id: string;
  readonly action: 'approved' | 'rejected';
  readonly demo: boolean;
}

/**
 * The queue's oldest listings, with the two decisions a moderator makes most: accept, or refuse
 * with a reason. The same endpoints as the moderation page, so the same rules and the same trail;
 * anything that needs the listing read in full is one link away.
 */
@Component({
  selector: 'app-pending-queue',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shown().length === 0) {
      <div class="queue__empty" data-empty="queue">
        <app-icon name="circle-check" [size]="28" />
        <p>La file est vide : aucune annonce n’attend de modération.</p>
      </div>
    } @else {
      <ul class="queue" role="list">
        @for (listing of shown(); track listing.id) {
          <li class="queue__item" [attr.data-pending]="listing.id">
            <div class="queue__head">
              <a class="queue__title" [routerLink]="['/admin/moderation']" [title]="listing.title">{{ listing.label }}</a>
              @if (listing.demo) {
                <span class="queue__tag" data-demo-tag>Démo</span>
              }
            </div>
            <p class="queue__meta">
              {{ price(listing) }} · {{ listing.sellerName }}
              @if (listing.damaged) {
                · <span class="queue__flag">Endommagé</span>
              }
              <span class="queue__waiting">
                <app-icon name="clock" [size]="12" /> {{ waiting(listing) }}
              </span>
            </p>

            @if (refusing() === listing.id) {
              <form class="queue__refuse" (submit)="reject($event, listing)">
                <label class="visually-hidden" [attr.for]="'motif-' + listing.id">Motif du refus</label>
                <select class="input" [id]="'motif-' + listing.id" (change)="pickReason($event)" data-reason>
                  <option value="" disabled [selected]="!reason()">Motif du refus…</option>
                  @for (option of reasons; track option.value) {
                    <option [value]="option.value" [selected]="option.value === reason()">{{ option.label }}</option>
                  }
                </select>
                @if (needsNote()) {
                  <label class="visually-hidden" [attr.for]="'note-' + listing.id">Précision pour les modérateurs</label>
                  <input class="input" type="text" maxlength="500" [id]="'note-' + listing.id" placeholder="Précisez le motif" (input)="editNote($event)" data-note />
                }
                <div class="queue__actions">
                  <button class="btn btn--danger btn--sm" type="submit" data-action="confirm-reject" [disabled]="!canReject() || busy() === listing.id">Refuser</button>
                  <button class="btn btn--quiet btn--sm" type="button" (click)="cancel()">Annuler</button>
                </div>
              </form>
            } @else {
              <div class="queue__actions">
                <button class="btn btn--primary btn--sm" type="button" data-action="approve" [disabled]="busy() === listing.id" (click)="approve(listing)">
                  <app-icon name="check" [size]="14" [strokeWidth]="2.5" /> Accepter
                </button>
                <button class="btn btn--quiet btn--sm" type="button" data-action="reject" [disabled]="busy() === listing.id" (click)="startRefusal(listing)">
                  Refuser…
                </button>
              </div>
            }
            @if (failure()?.id === listing.id) {
              <p class="queue__failure" role="alert">{{ failure()?.message }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .queue {
      display: grid;
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .queue__item {
      display: grid;
      gap: var(--s-1);
      padding: var(--s-3) 0;
      border-top: 1px solid var(--c-line);

      &:first-child {
        border-top: 0;
        padding-top: 0;
      }
    }

    .queue__head {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      min-width: 0;
    }

    .queue__title {
      overflow: hidden;
      font-weight: var(--fw-semibold);
      color: var(--c-ink);
      text-decoration: none;
      white-space: nowrap;
      text-overflow: ellipsis;

      &:hover {
        text-decoration: underline;
      }
    }

    .queue__tag {
      flex: none;
      padding: 0 6px;
      border-radius: 999px;
      background: var(--c-surface-sunk);
      color: var(--c-ink-soft);
      font-size: var(--t-2xs);
      font-weight: var(--fw-bold);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .queue__meta {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0 var(--s-1);
      margin: 0;
      font-size: var(--t-xs);
      color: var(--c-ink-soft);
    }

    .queue__flag {
      color: var(--c-danger);
      font-weight: var(--fw-semibold);
    }

    .queue__waiting {
      display: inline-flex;
      align-items: center;
      gap: 3px;
      margin-left: auto;
      color: var(--c-warn);
      font-weight: var(--fw-semibold);
    }

    .queue__actions,
    .queue__refuse {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-2);
      margin-top: var(--s-1);
    }

    .queue__refuse .input {
      flex: 1 1 12rem;
      min-width: 0;
    }

    .queue__failure {
      margin: 0;
      font-size: var(--t-xs);
      color: var(--c-danger);
    }

    .queue__empty {
      display: grid;
      justify-items: center;
      gap: var(--s-2);
      padding: var(--s-4) var(--s-2);
      color: var(--c-success);
      text-align: center;

      p {
        margin: 0;
        font-size: var(--t-sm);
        color: var(--c-ink-soft);
      }
    }
  `,
})
export class PendingQueue {
  private readonly api = inject(AdminService);
  private readonly errors = inject(ErrorMessageService);

  /** Oldest first: the dashboard's choice of real or real and demo. */
  readonly listings = input.required<readonly PendingListing[]>();
  readonly moderated = output<Moderated>();

  protected readonly reasons = MODERATION_REASONS.map((reason) => ({ value: reason, label: MODERATION_REASON_LABELS[reason] }));
  protected readonly done = signal<ReadonlySet<string>>(new Set());
  protected readonly shown = computed(() => this.listings().filter((listing) => !this.done().has(listing.id)));
  protected readonly busy = signal<string | null>(null);
  protected readonly refusing = signal<string | null>(null);
  protected readonly reason = signal<ModerationReason | ''>('');
  protected readonly note = signal('');
  protected readonly failure = signal<{ id: string; message: string } | null>(null);
  protected readonly needsNote = computed(() => reasonRequiresNote(this.reason()));
  protected readonly canReject = computed(() => !!this.reason() && (!this.needsNote() || this.note().trim().length > 0));

  protected price(listing: PendingListing): string {
    const amount = formatPrice(listing.priceAmount, listing.currencyCode, 2);
    return listing.leasing ? `Location · ${amount} comptant` : amount;
  }

  protected waiting(listing: PendingListing): string {
    return waitingFr(listing.createdAt) ?? '';
  }

  protected approve(listing: PendingListing): void {
    this.begin(listing);
    this.api.approve({ id: listing.id }).subscribe({
      next: () => this.finish(listing, 'approved'),
      error: (error: unknown) => this.fail(listing, error),
    });
  }

  protected startRefusal(listing: PendingListing): void {
    this.failure.set(null);
    this.reason.set('');
    this.note.set('');
    this.refusing.set(listing.id);
  }

  protected cancel(): void {
    this.refusing.set(null);
  }

  protected pickReason(event: Event): void {
    this.reason.set((event.target as HTMLSelectElement).value as ModerationReason);
  }

  protected editNote(event: Event): void {
    this.note.set((event.target as HTMLInputElement).value);
  }

  protected reject(event: Event, listing: PendingListing): void {
    event.preventDefault();
    const reason = this.reason();
    if (!reason || !this.canReject()) {
      return;
    }
    this.begin(listing);
    this.api
      .reject({ id: listing.id, moderationDecisionRequest: { reasonCode: reason, note: this.note().trim() || undefined } })
      .subscribe({
        next: () => this.finish(listing, 'rejected'),
        error: (error: unknown) => this.fail(listing, error),
      });
  }

  private begin(listing: PendingListing): void {
    this.failure.set(null);
    this.busy.set(listing.id);
  }

  private finish(listing: PendingListing, action: Moderated['action']): void {
    this.busy.set(null);
    this.refusing.set(null);
    this.done.update((done) => new Set([...done, listing.id]));
    this.moderated.emit({ id: listing.id, action, demo: listing.demo });
  }

  private fail(listing: PendingListing, error: unknown): void {
    this.busy.set(null);
    this.failure.set({ id: listing.id, message: this.errors.messageFor(error) });
  }
}
