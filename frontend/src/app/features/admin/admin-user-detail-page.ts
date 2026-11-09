import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import {
  AdminService,
  AdminUserActionResult,
  AdminUserActionType,
  AdminUserDetail,
  AdminUserListing,
  ListingStatus,
  UserRole,
  UserStatus,
} from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DEFAULT_MINOR_UNIT, ReferenceStore } from '../../core/reference/reference.store';
import { formatDateFr, formatDateTimeFr } from '../../shared/format/date';
import { formatIntegerFr } from '../../shared/format/number';
import { SelectField, SelectOption } from '../../shared/forms/select-field';
import { TextField } from '../../shared/forms/text-field';
import { requiredText } from '../../shared/forms/validation';
import { formatPrice } from '../../shared/listing/price';
import { labelOf } from '../../shared/listing/labels.fr';
import { MODERATION_ACTION_LABELS, MODERATION_REASON_LABELS } from '../../shared/listing/moderation.fr';
import { Alert } from '../../shared/ui/alert';
import { STATUS_LABELS } from '../seller/listing-status';
import { USER_ACTION_LABELS, USER_ROLES, USER_ROLE_LABELS, USER_STATUS_LABELS, userValueLabel } from './user-labels';

type LoadState = 'loading' | 'ready' | 'error' | 'missing';

/** Which confirmation is open. Changing a role takes two steps: choosing it, then confirming it. */
type Step = 'suspend' | 'reactivate' | 'choose-role' | 'confirm-role' | 'hide-page' | 'show-page' | null;

const MAX_REASON = 2000;

/** "15 minutes", from the lifetime the API reports. */
export function minutesFr(seconds: number): string {
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes > 1 ? 's' : ''}`;
}

/**
 * One account: who it is, what it has done, what was decided about its listings, and what
 * administrators have done to it. The actions confirm first.
 *
 * <p>The rules (not yourself, not the last administrator) are the API's, and any refusal is
 * shown as it comes. This page only spares an administrator the buttons for their own account.
 */
@Component({
  selector: 'app-admin-user-detail-page',
  imports: [ReactiveFormsModule, RouterLink, Alert, SelectField, TextField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-user-detail-page.html',
  styleUrl: './admin-user-detail-page.scss',
})
export class AdminUserDetailPage {
  private readonly api = inject(AdminService);
  private readonly auth = inject(AuthService);
  private readonly errors = inject(ErrorMessageService);

  /** The route's `:id`. */
  readonly id = input.required<string>();

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly user = signal<AdminUserDetail | null>(null);
  protected readonly step = signal<Step>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly maxReason = MAX_REASON;
  protected readonly reason = new FormControl('', {
    nonNullable: true,
    validators: [requiredText(), Validators.maxLength(MAX_REASON)],
  });
  protected readonly note = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(MAX_REASON)] });
  protected readonly newRole = new FormControl('', { nonNullable: true, validators: [Validators.required] });

  protected readonly roleLabels = USER_ROLE_LABELS;
  protected readonly statusLabels = USER_STATUS_LABELS;
  protected readonly listingLabels = STATUS_LABELS;
  protected readonly date = formatDateFr;
  protected readonly dateTime = formatDateTimeFr;
  protected readonly number = formatIntegerFr;
  protected readonly value = userValueLabel;

  protected readonly isSelf = computed(() => this.user()?.id === this.auth.user()?.id);
  protected readonly isSuspended = computed(() => this.user()?.status === UserStatus.SUSPENDED);
  /** Up to 15 minutes, from the API, so that this cannot say something the server does not do. */
  protected readonly tokenLifetime = computed(() => minutesFr(this.user()?.accessTokenTtlSeconds ?? 900));

  /** The roles it can move to. Not its own, and not ADMIN for an account nobody can sign in to. */
  protected readonly roleOptions = computed<SelectOption[]>(() => {
    const user = this.user();
    return USER_ROLES.filter((role) => role !== user?.role && !(user?.demo && role === UserRole.ADMIN)).map((role) => ({
      value: role,
      label: USER_ROLE_LABELS[role],
    }));
  });
  protected readonly chosenRole = signal<UserRole | null>(null);

  /** A professional's public page, and whether it is hidden. Null for anyone else. */
  protected readonly dealerPage = computed(() => this.user()?.dealerPage ?? null);

  /** The suspension in force, for saying why at the top of the page. */
  protected readonly suspension = computed(() =>
    this.isSuspended() ? this.user()?.actions?.find((action) => action.action === AdminUserActionType.SUSPEND) : undefined,
  );

  protected readonly listings = computed(() => this.user()?.listings ?? []);
  protected readonly history = computed(() =>
    (this.user()?.moderationHistory ?? []).map((entry) => ({
      ...entry,
      what: labelOf(MODERATION_ACTION_LABELS, entry.action),
      why: entry.reasonCode ? labelOf(MODERATION_REASON_LABELS, entry.reasonCode) : null,
    })),
  );
  protected readonly actions = computed(() =>
    (this.user()?.actions ?? []).map((entry) => ({
      ...entry,
      what: labelOf(USER_ACTION_LABELS, entry.action),
    })),
  );
  /** Only the newest are carried; the count says when there are more. */
  protected readonly moreListings = computed(() => {
    const total = this.user()?.activity?.listings ?? 0;
    return total > this.listings().length ? total : 0;
  });

  private readonly minorUnits = toSignal(inject(ReferenceStore).minorUnits(), {
    initialValue: {} as Readonly<Record<string, number>>,
  });

  /** Guards against an answer for an account the administrator has already left. */
  private generation = 0;

  constructor() {
    effect(() => {
      this.id();
      this.notice.set(null);
      this.failure.set(null);
      this.close();
      this.load();
    });
  }

  protected load(): void {
    const generation = ++this.generation;
    this.state.set('loading');
    this.api.userDetail({ id: this.id() }).subscribe({
      next: (user) => {
        if (generation === this.generation) {
          this.user.set(user);
          this.state.set('ready');
        }
      },
      error: (error: unknown) => {
        if (generation !== this.generation) {
          return;
        }
        if ((error as { status?: number }).status === 404) {
          this.state.set('missing');
          return;
        }
        this.loadFailure.set(this.errors.messageFor(error));
        this.state.set('error');
      },
    });
  }

  /** Plain text: in a table of an account's listings, the price is one column among others. */
  protected price(listing: AdminUserListing): string {
    const currency = listing.currencyCode ?? 'EUR';
    return formatPrice(listing.priceAmount ?? 0, currency, this.minorUnits()[currency] ?? DEFAULT_MINOR_UNIT);
  }

  /** Why a listing a visitor would otherwise see is not on the site. */
  protected listingState(listing: AdminUserListing): string {
    return this.isSuspended() && listing.status === ListingStatus.ACTIVE
      ? 'Masquée (compte suspendu)'
      : labelOf(STATUS_LABELS, listing.status);
  }

  protected openStep(step: Step): void {
    this.reason.reset();
    this.note.reset();
    this.newRole.reset();
    this.notice.set(null);
    this.failure.set(null);
    this.step.set(step);
  }

  protected close(): void {
    this.step.set(null);
    this.chosenRole.set(null);
  }

  protected confirmSuspension(): void {
    if (this.reason.invalid) {
      this.reason.markAsTouched();
      return;
    }
    this.run(
      this.api.suspendUser({ id: this.id(), suspendUserRequest: { reason: this.reason.value.trim() } }),
      (result) => {
        const sessions = result.revokedSessions ?? 0;
        const closed = sessions === 1 ? '1 session fermée' : `${sessions} sessions fermées`;
        return `Compte suspendu : ${closed}, annonces masquées, déconnecté sur tous ses appareils.`;
      },
    );
  }

  protected confirmReactivation(): void {
    if (this.note.invalid) {
      this.note.markAsTouched();
      return;
    }
    this.run(
      this.api.reactivateUser({ id: this.id(), reactivateUserRequest: { note: this.note.value.trim() || undefined } }),
      () => 'Compte réactivé : il peut se reconnecter et ses annonces sont de retour en ligne.',
    );
  }

  /** The first of the two steps of a role change. */
  protected chooseRole(): void {
    if (this.newRole.invalid) {
      this.newRole.markAsTouched();
      return;
    }
    this.chosenRole.set(this.newRole.value as UserRole);
    this.step.set('confirm-role');
  }

  protected confirmRole(): void {
    const role = this.chosenRole();
    if (!role || this.note.invalid) {
      this.note.markAsTouched();
      return;
    }
    const lifetime = this.tokenLifetime();
    this.run(
      this.api.changeUserRole({
        id: this.id(),
        changeUserRoleRequest: { role, note: this.note.value.trim() || undefined },
      }),
      () => `Rôle modifié : ${USER_ROLE_LABELS[role]}. Effectif à la prochaine connexion, ou d’ici ${lifetime} au plus.`,
    );
  }

  protected confirmHidePage(): void {
    if (this.reason.invalid) {
      this.reason.markAsTouched();
      return;
    }
    this.run(
      this.api.hideDealerPage({ id: this.id(), hideDealerPageRequest: { reason: this.reason.value.trim() } }),
      () => 'Page professionnelle masquée : elle n’est plus dans l’annuaire et répond « introuvable » au public. Ses annonces restent en ligne.',
    );
  }

  protected confirmShowPage(): void {
    if (this.note.invalid) {
      this.note.markAsTouched();
      return;
    }
    this.run(
      this.api.showDealerPage({ id: this.id(), showDealerPageRequest: { note: this.note.value.trim() || undefined } }),
      () => 'Page professionnelle rétablie : elle est de retour dans l’annuaire.',
    );
  }

  private run(request: Observable<AdminUserActionResult>, done: (result: AdminUserActionResult) => string): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    request.subscribe({
      next: (result) => {
        this.busy.set(false);
        if (result.user) {
          this.user.set(result.user);
        }
        this.notice.set(done(result));
        this.close();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(this.errors.messageFor(error));
      },
    });
  }
}
