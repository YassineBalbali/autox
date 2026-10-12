import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ProfileService, UserProfileResponse, UserRole } from '../../core/api';
import { AuthService, userSummaryOf } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { TextField } from '../../shared/forms/text-field';
import { formatDateFr } from '../../shared/format/date';
import { MAX_LENGTHS, phoneValidators, requiredText } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/**
 * The account: what is on it, and the parts of it the user can change here.
 *
 * <p>One screen rather than a view mode and an edit mode. A separate "modifier" state buys
 * nothing on a form of four fields and costs an extra tap before every correction — and on
 * a phone the edit view and the read view would be the same list of values either way. What
 * genuinely cannot be edited here is shown as a read-only list instead, with the reason:
 * the email address and the account type both have consequences the API deliberately keeps
 * off a profile PATCH.
 *
 * <p>The dealer's company details are a separate route. They are nine more fields, they
 * apply to one role, and splitting them keeps this form the length it looks.
 */
@Component({
  selector: 'app-profile-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile-page.html',
  styleUrl: './account.scss',
})
export class ProfilePage {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly errors = inject(ErrorMessageService);

  protected readonly maxLengths = MAX_LENGTHS;
  protected readonly roles = UserRole;

  protected readonly profile = signal<UserProfileResponse | null>(null);
  protected readonly loadFailure = signal<string | null>(null);
  protected readonly saved = signal(false);

  protected readonly isProfessional = computed(
    () => this.profile()?.role === UserRole.PROFESSIONNEL,
  );

  /** The account type in words. Falls back to the raw value, which is then visibly wrong. */
  protected readonly accountTypeLabel = computed(() => {
    const role = this.profile()?.role;
    return role ? ROLE_LABELS_FR[role] : "";
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
    this.load();
  }

  protected load(): void {
    this.loadFailure.set(null);
    this.api.getOwnProfile().subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.form.reset({
          displayName: profile.displayName ?? '',
          firstName: profile.firstName ?? '',
          lastName: profile.lastName ?? '',
          phone: profile.phone ?? '',
        });
        // Keeps the rest of the application in step: the header greets the user by display
        // name, and a reload of this screen is the authority on what that name is.
        this.auth.setUser(userSummaryOf(profile));
      },
      error: (error: unknown) => this.loadFailure.set(this.errors.messageFor(error)),
    });
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
          this.profile.set(profile);
          this.auth.setUser(userSummaryOf(profile));
          // After valueChanges has fired from the reset, or it would clear this again.
          this.form.reset({
            displayName: profile.displayName ?? '',
            firstName: profile.firstName ?? '',
            lastName: profile.lastName ?? '',
            phone: profile.phone ?? '',
          });
          this.saved.set(true);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }

  protected signOut(): void {
    this.auth.logout('/connexion');
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
  [UserRole.PARTICULIER]: "Particulier",
  [UserRole.PROFESSIONNEL]: "Professionnel",
  [UserRole.ADMIN]: "Administrateur",
};
