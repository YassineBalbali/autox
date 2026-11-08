import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { ModeratedListing, ModerationActionType, ModerationReason } from '../../core/api';
import { SelectField, SelectOption } from '../../shared/forms/select-field';
import { TextField } from '../../shared/forms/text-field';
import { requiredText } from '../../shared/forms/validation';
import { MODERATION_REASONS, MODERATION_REASON_LABELS, reasonRequiresNote } from '../../shared/listing/moderation.fr';
import { Alert } from '../../shared/ui/alert';

/** Mirrors `ModerationDecisionRequest.MAX_NOTE_LENGTH`. */
export const MAX_NOTE_LENGTH = 2000;

/** What a moderator decided about one listing. The page turns it into the API call. */
export interface ModerationDecision {
  readonly action: ModerationActionType;
  /** Present for REJECT and REMOVE. */
  readonly reasonCode?: ModerationReason;
  readonly note?: string;
}

/**
 * What a moderator can do with one listing: the buttons, and the short forms behind the two
 * that need more than a click. The same on a card and on a row of the list.
 *
 * <p><b>The buttons are the ones the API says it would accept</b> — `allowedActions`, sent
 * with the listing — never a guess from its status. The one choice made here is of
 * display: where the API would accept both a refusal and a takedown (a listing nobody has
 * reviewed yet), only "Refuser" is offered, since for that listing they are the same act.
 *
 * <p>Approving is one click. Refusing and taking down open a short form, because a reason
 * is required and is what the seller will be told. Reinstating asks first, with room for a
 * note.
 */
@Component({
  selector: 'app-moderation-decision',
  imports: [ReactiveFormsModule, SelectField, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (failure()) {
      <app-alert kind="error">{{ failure() }}</app-alert>
    }

    @switch (open()) {
      @case ('refuse') {
        <form class="stack form--rows decision__form" [formGroup]="refusal" (ngSubmit)="submitRefusal()" novalidate>
          <app-select-field
            label="Motif"
            i18n-label="@@moderationReason"
            hint="Le vendeur verra ce motif."
            i18n-hint="@@moderationReasonHint"
            [control]="refusal.controls.reason"
            [options]="reasonOptions"
          />
          <app-text-field
            label="Note interne"
            i18n-label="@@moderationNote"
            type="textarea"
            hint="Visible des modérateurs uniquement."
            i18n-hint="@@moderationNoteHint"
            [optional]="!noteRequired()"
            [rows]="3"
            [maxLength]="maxNote"
            [control]="refusal.controls.note"
          />
          <div class="form-actions">
            <button class="btn btn--danger" type="submit" data-action="confirm-refusal" [disabled]="busy()">
              {{ refusalLabel() }}
            </button>
            <button class="btn btn--quiet" type="button" (click)="cancel()" i18n="@@cancel">Annuler</button>
          </div>
        </form>
      }

      @case ('reinstate') {
        <form class="stack form--rows decision__form" [formGroup]="reinstatement" (ngSubmit)="submitReinstatement()" novalidate>
          <p i18n="@@moderationReinstateQuestion">
            Réintégrer cette annonce ? Elle redevient visible, sauf si elle a expiré entre-temps ou si
            le vendeur a déjà atteint son nombre maximum d’annonces en ligne.
          </p>
          <app-text-field
            label="Note interne"
            i18n-label="@@moderationNote"
            type="textarea"
            hint="Visible des modérateurs uniquement."
            i18n-hint="@@moderationNoteHint"
            [optional]="true"
            [rows]="2"
            [maxLength]="maxNote"
            [control]="reinstatement.controls.note"
          />
          <div class="form-actions">
            <button class="btn btn--primary" type="submit" data-action="confirm-reinstatement" [disabled]="busy()" i18n="@@moderationReinstate">
              Réintégrer
            </button>
            <button class="btn btn--quiet" type="button" (click)="cancel()" i18n="@@cancel">Annuler</button>
          </div>
        </form>
      }

      @default {
        <div class="decision__actions">
          @if (canApprove()) {
            <button class="btn btn--primary" [class.btn--sm]="compact()" type="button" data-action="approve" [disabled]="busy()" (click)="approve()" i18n="@@moderationApprove">
              Approuver
            </button>
          }
          @if (refusalAction()) {
            <button class="btn btn--danger" [class.btn--sm]="compact()" type="button" data-action="refuse" [disabled]="busy()" (click)="openForm('refuse')">
              {{ refusalLabel() }}
            </button>
          }
          @if (canReinstate()) {
            <button class="btn btn--quiet" [class.btn--sm]="compact()" type="button" data-action="reinstate" [disabled]="busy()" (click)="openForm('reinstate')" i18n="@@moderationReinstate">
              Réintégrer
            </button>
          }
        </div>
      }
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: var(--s-2);
    }

    .decision__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-2);
    }

    .decision__form {
      max-width: 44rem;
    }
  `,
})
export class ModerationDecisionControls {
  readonly item = input.required<ModeratedListing>();
  /** True while a decision on this listing is on its way to the API. */
  readonly busy = input(false);
  /** Why the last decision on this listing was refused, in French. */
  readonly failure = input<string | null>(null);
  /** Small buttons, for a row of the list. */
  readonly compact = input(false);

  readonly decided = output<ModerationDecision>();

  /** Whether a form is open: a row gives the form its whole width while it is. */
  readonly formOpen = computed(() => this.open() !== null);

  protected readonly maxNote = MAX_NOTE_LENGTH;

  /** Which of the two forms is open, if either. */
  protected readonly open = signal<'refuse' | 'reinstate' | null>(null);

  protected readonly refusal = new FormGroup({
    reason: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_NOTE_LENGTH)] }),
  });
  protected readonly reinstatement = new FormGroup({
    note: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_NOTE_LENGTH)] }),
  });

  protected readonly reasonOptions: SelectOption[] = MODERATION_REASONS.map((reason) => ({
    value: reason,
    label: MODERATION_REASON_LABELS[reason],
  }));

  /** Mirror of the reason control, for the note's "(optionnel)". */
  protected readonly noteRequired = signal(false);

  // The generated type says Set; the wire says array. Spreading is right for both.
  private readonly allowed = computed(() => [...(this.item().allowedActions ?? [])]);

  protected readonly canApprove = computed(() => this.allowed().includes(ModerationActionType.APPROVE));
  protected readonly canReinstate = computed(() => this.allowed().includes(ModerationActionType.REINSTATE));

  /** The one way of taking this listing off the site that is offered, if any. */
  protected readonly refusalAction = computed<ModerationActionType | null>(() => {
    if (this.allowed().includes(ModerationActionType.REJECT)) {
      return ModerationActionType.REJECT;
    }
    return this.allowed().includes(ModerationActionType.REMOVE) ? ModerationActionType.REMOVE : null;
  });
  protected readonly refusalLabel = computed(() =>
    this.refusalAction() === ModerationActionType.REJECT ? 'Refuser' : 'Retirer l’annonce',
  );

  constructor() {
    // "Autre motif" says nothing alone: the note becomes required with it, as in the API.
    this.refusal.controls.reason.valueChanges.subscribe((reason) => {
      const required = reasonRequiresNote(reason as ModerationReason | '');
      this.noteRequired.set(required);
      this.refusal.controls.note.setValidators(
        required ? [requiredText(), Validators.maxLength(MAX_NOTE_LENGTH)] : [Validators.maxLength(MAX_NOTE_LENGTH)],
      );
      this.refusal.controls.note.updateValueAndValidity();
    });

    // A different listing in this place, or the same one after a decision: nothing typed
    // about the previous state should be waiting behind a button.
    effect(() => {
      this.item();
      this.cancel();
    });
  }

  protected approve(): void {
    this.decided.emit({ action: ModerationActionType.APPROVE });
  }

  protected openForm(form: 'refuse' | 'reinstate'): void {
    this.open.set(form);
  }

  protected cancel(): void {
    this.open.set(null);
    this.refusal.reset();
    this.reinstatement.reset();
  }

  protected submitRefusal(): void {
    const action = this.refusalAction();
    if (this.refusal.invalid || !action) {
      this.refusal.markAllAsTouched();
      return;
    }
    const { reason, note } = this.refusal.getRawValue();
    this.decided.emit({ action, reasonCode: reason as ModerationReason, note: note.trim() || undefined });
  }

  protected submitReinstatement(): void {
    if (this.reinstatement.invalid) {
      this.reinstatement.markAllAsTouched();
      return;
    }
    const note = this.reinstatement.getRawValue().note.trim();
    this.decided.emit({ action: ModerationActionType.REINSTATE, note: note || undefined });
  }
}
