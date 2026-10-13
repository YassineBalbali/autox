import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import {
  ListingStatus,
  ModeratedListing,
  ModerationActionType,
  ModerationReason,
  PublicListingDetail,
} from '../../core/api';
import { formatDateFr, formatDateTimeFr } from '../../shared/format/date';
import { SelectField, SelectOption } from '../../shared/forms/select-field';
import { TextField } from '../../shared/forms/text-field';
import { requiredText } from '../../shared/forms/validation';
import { FUEL_LABELS, GEARBOX_LABELS, SELLER_ROLE_LABELS, labelOf } from '../../shared/listing/labels.fr';
import {
  MODERATION_ACTION_LABELS,
  MODERATION_REASONS,
  MODERATION_REASON_LABELS,
  MODERATION_STATUS_LABELS,
  reasonRequiresNote,
} from '../../shared/listing/moderation.fr';
import { PhotoGallery } from '../../shared/listing/photo-gallery';
import { Price } from '../../shared/listing/price';
import { SpecLine } from '../../shared/listing/spec-line';
import { Alert } from '../../shared/ui/alert';
import { galleryImages, specificationRows } from '../listing/listing-view';
import { STATUS_LABELS } from '../seller/listing-status';

/** Mirrors `ModerationDecisionRequest.MAX_NOTE_LENGTH`. */
export const MAX_NOTE_LENGTH = 2000;

/** What a moderator decided about one listing. The page turns it into the API call. */
export interface ModerationDecision {
  readonly action: ModerationActionType;
  /** Present for REJECT and REMOVE. */
  readonly reasonCode?: ModerationReason;
  readonly note?: string;
}

/** One line of the trail, ready to print. */
interface TrailLine {
  readonly id: string;
  readonly when: string;
  readonly what: string;
  readonly who: string;
  readonly reason: string | null;
  readonly note: string | null;
}

/**
 * One listing, as a moderator judges it.
 *
 * <p>Everything needed to decide is on the card: every photo, the whole description, the
 * figures a buyer would check, who is selling, and what has already been decided about it
 * and by whom. A moderator who has to open another page to read the description will stop
 * reading descriptions.
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
  selector: 'app-moderation-card',
  imports: [ReactiveFormsModule, RouterLink, PhotoGallery, Price, SpecLine, SelectField, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './moderation-card.html',
  styleUrl: './moderation-card.scss',
})
export class ModerationCard {
  readonly item = input.required<ModeratedListing>();
  /** True while a decision on this listing is on its way to the API. */
  readonly busy = input(false);
  /** Why the last decision on this listing was refused, in French. */
  readonly failure = input<string | null>(null);
  readonly minorUnit = input(2);

  readonly decided = output<ModerationDecision>();

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

  protected readonly listing = computed(() => this.item().listing ?? {});
  protected readonly seller = computed(() => this.item().seller ?? {});

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

  protected readonly online = computed(() => this.listing().status === ListingStatus.ACTIVE);
  protected readonly statusLabel = computed(() => labelOf(STATUS_LABELS, this.listing().status));
  protected readonly verdictLabel = computed(() => labelOf(MODERATION_STATUS_LABELS, this.listing().moderationStatus));

  // The moderator's view carries the same fields the public page displays, and more.
  private readonly asDetail = computed(() => this.listing() as unknown as PublicListingDetail);
  protected readonly images = computed(() => galleryImages(this.asDetail()));
  protected readonly specs = computed(() => specificationRows(this.asDetail()));
  protected readonly fuel = computed(() => labelOf(FUEL_LABELS, this.listing().vehicle?.fuelType));
  protected readonly gearbox = computed(() => labelOf(GEARBOX_LABELS, this.listing().vehicle?.gearbox));
  protected readonly place = computed(() =>
    [this.listing().location?.postalCode, this.listing().location?.city].filter(Boolean).join(' '),
  );
  protected readonly publishedOn = computed(() => formatDateFr(this.listing().publishedAt));
  protected readonly sellerKind = computed(() => labelOf(SELLER_ROLE_LABELS, this.seller().role));
  protected readonly memberSince = computed(() => formatDateFr(this.seller().memberSince));

  protected readonly trail = computed<TrailLine[]>(() =>
    (this.item().trail ?? []).map((entry, index) => ({
      id: entry.id ?? String(index),
      when: formatDateTimeFr(entry.at) ?? '',
      what: labelOf(MODERATION_ACTION_LABELS, entry.action),
      who: entry.moderatorName ?? 'un modérateur',
      reason: entry.reasonCode ? labelOf(MODERATION_REASON_LABELS, entry.reasonCode) : null,
      note: entry.note ?? null,
    })),
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

    // A different listing in this card's place, or the same one after a decision: nothing
    // typed about the previous state should be waiting behind a button.
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
