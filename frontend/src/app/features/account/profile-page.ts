import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { ProfileService, UserProfileResponse, UserRole } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { TextField } from '../../shared/forms/text-field';
import { formatDateFr } from '../../shared/format/date';
import { MAX_LENGTHS, phoneValidators, requiredText } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';
import { AccountProfileStore } from './account-profile.store';

/**
 * The account: what is on it, and the parts of it the user can change here.
 *
 * <p>Two sections, both named in the sidebar: <b>Identité</b>, the facts that cannot be
 * changed here and why, and <b>Mes informations</b>, the four fields that can. Their
 * `id`s are the sidebar's anchor targets, so the two entries actually go somewhere.
 *
 * <p>One form rather than a view mode and an edit mode. A separate "modifier" state buys
 * nothing on a form of four fields and costs a click before every correction. What
 * genuinely cannot be edited is shown as a read-only list instead, with the reason: the
 * email address and the account type both have consequences the API deliberately keeps
 * off a profile PATCH.
 *
 * <p>The dealer's company details are their own route. Nine more fields that apply to one
 * role, which would otherwise double the length of this page for half the accounts.
 */
@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile-page.html',
  styleUrl: './account.scss',
})
export class ProfilePage {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly store = inject(AccountProfileStore);

  protected readonly maxLengths = MAX_LENGTHS;
  protected readonly roles = UserRole;

  protected readonly profile = this.store.profile;
  protected readonly loadFailure = this.store.loadFailure;
  protected readonly saved = signal(false);

  protected readonly accountTypeLabel = computed(() => {
    const role = this.profile()?.role;
    return role ? ROLE_LABELS_FR[role] : '';
  });

  protected readonly memberSince = computed(() => formatDateFr(this.profile()?.createdAt));

  protected readonly form = new FormGroup({
    displayName: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), Validators.maxLength(MAX_LENGTHS.displayName)],
    }),
    firstName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.firstName)],
    }),
    lastName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.lastName)],
    }),
    phone: new FormControl('', { nonNullable: true, validators: phoneValidators }),
  });

  protected readonly state = submitState(this.form);

  constructor() {
    // Clears the confirmation as soon as the user starts editing again, so "enregistré"
    // never sits above a form with unsaved changes in it.
    this.form.valueChanges.subscribe(() => this.saved.set(false));

    // Refills whenever the store changes — on first load, and after a save, which returns
    // the updated profile. Only those two things write to the store, so this cannot
    // overwrite something the user is in the middle of typing.
    effect(() => {
      const profile = this.profile();
      if (profile) {
        this.fill(profile);
      }
    });

    // Idempotent: the layout has almost certainly asked already. Called anyway so the
    // screen still works when it is rendered on its own, as it is under test.
    this.store.ensureLoaded();
  }

  protected retry(): void {
    this.store.reload();
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const value = this.form.getRawValue();
    this.state.begin();

    this.api
      .updateOwnProfile({
        updateProfileRequest: {
          displayName: value.displayName.trim(),
          // Trimmed and sent as-is, including empty. The API reads a blank string on an
          // optional field as "clear it", which is exactly what emptying the field means —
          // whereas omitting the member means "leave unchanged", so a user who deleted
          // their phone number would find it still there.
          firstName: value.firstName.trim(),
          lastName: value.lastName.trim(),
          phone: value.phone.trim(),
        },
      })
      .subscribe({
        next: (profile) => {
          this.state.succeed();
          // Refills the form through the effect above, and updates the name in the header.
          this.store.adopt(profile);
          this.saved.set(true);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }

  protected signOut(): void {
    this.auth.logout('/connexion');
  }

  /**
   * `emitEvent: false` because this is the application writing to the form, not the user.
   *
   * <p>Without it the refill after a save would fire `valueChanges` and clear the
   * confirmation that the save had just set — and effects are scheduled rather than
   * synchronous, so it would do it a tick *after* `saved` was set to true. The banner
   * would never appear, and the cause would not be anywhere near the banner.
   */
  private fill(profile: UserProfileResponse): void {
    this.form.reset(
      {
        displayName: profile.displayName ?? '',
        firstName: profile.firstName ?? '',
        lastName: profile.lastName ?? '',
        phone: profile.phone ?? '',
      },
      { emitEvent: false },
    );
  }
}

/**
 * Role to French label.
 *
 * <p>Three entries and a `Record<UserRole, string>`, so a role added to the API stops this
 * file compiling rather than rendering a raw `PROFESSIONNEL` to a user. The enum values
 * stay server-side and the words stay here, which is the agreed split — this is not a
 * general enum translation layer, and the next enum that needs labels gets its own three
 * lines next to the screen that shows it.
 */
const ROLE_LABELS_FR: Record<UserRole, string> = {
  [UserRole.PARTICULIER]: 'Particulier',
  [UserRole.PROFESSIONNEL]: 'Professionnel',
  [UserRole.ADMIN]: 'Administrateur',
};
