import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { ProfileService, ProProfileView } from '../../core/api';
import { TextField } from '../../shared/forms/text-field';
import { MAX_LENGTHS, phoneValidators, requiredText } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';
import { AccountProfileStore } from './account-profile.store';

/**
 * A dealer's company details.
 *
 * <p>Its own route, behind `roleGuard(PROFESSIONNEL, ADMIN)`. The guard is a courtesy and
 * not a boundary — the API refuses a professional PATCH from a private account whatever the
 * client thinks — but a private seller following a stale link should see an honest refusal
 * rather than a form of nine fields that cannot be saved.
 *
 * <p>Company name and registry number are required here, matching registration: they were
 * mandatory to create the account and emptying them afterwards would leave exactly the
 * unverifiable dealer that requirement exists to prevent. The other seven are optional, and
 * blanking one clears it.
 */
@Component({
  selector: 'app-company-page',
  imports: [ReactiveFormsModule, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './company-page.html',
  styleUrl: './account.scss',
})
export class CompanyPage {
  private readonly api = inject(ProfileService);
  private readonly store = inject(AccountProfileStore);

  protected readonly maxLengths = MAX_LENGTHS;

  protected readonly profile = this.store.profile;
  protected readonly loadFailure = this.store.loadFailure;
  protected readonly saved = signal(false);

  protected readonly form = new FormGroup({
    companyName: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), Validators.maxLength(MAX_LENGTHS.companyName)],
    }),
    registrationNumber: new FormControl('', {
      nonNullable: true,
      validators: [requiredText(), Validators.maxLength(MAX_LENGTHS.registrationNumber)],
    }),
    legalForm: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.legalForm)],
    }),
    vatNumber: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.vatNumber)],
    }),
    websiteUrl: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.websiteUrl)],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.description)],
    }),
    addressLine1: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.addressLine)],
    }),
    addressLine2: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(MAX_LENGTHS.addressLine)],
    }),
    contactPhone: new FormControl('', { nonNullable: true, validators: phoneValidators }),
  });

  protected readonly state = submitState(this.form);

  constructor() {
    this.form.valueChanges.subscribe(() => this.saved.set(false));

    effect(() => {
      const profile = this.profile();
      if (profile) {
        this.fill(profile.professional);
      }
    });

    // Idempotent — the layout has almost certainly asked already.
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
          // Nested under `professional`, which is also how the API reports a field error on
          // one of these — hence `professional.vatNumber` in the rejected() calls below.
          professional: {
            companyName: value.companyName.trim(),
            registrationNumber: value.registrationNumber.trim(),
            legalForm: value.legalForm.trim(),
            vatNumber: value.vatNumber.trim(),
            websiteUrl: value.websiteUrl.trim(),
            description: value.description.trim(),
            addressLine1: value.addressLine1.trim(),
            addressLine2: value.addressLine2.trim(),
            contactPhone: value.contactPhone.trim(),
          },
        },
      })
      .subscribe({
        next: (profile) => {
          this.state.succeed();
          // Refills through the effect above, and keeps the sidebar and header in step.
          this.store.adopt(profile);
          this.saved.set(true);
        },
        error: (error: unknown) => this.state.fail(error),
      });
  }

  /** `emitEvent: false` — see the note on ProfilePage.fill for why this matters. */
  private fill(professional: ProProfileView | undefined): void {
    this.form.reset(
      {
        companyName: professional?.companyName ?? '',
        registrationNumber: professional?.registrationNumber ?? '',
        legalForm: professional?.legalForm ?? '',
        vatNumber: professional?.vatNumber ?? '',
        websiteUrl: professional?.websiteUrl ?? '',
        description: professional?.description ?? '',
        addressLine1: professional?.addressLine1 ?? '',
        addressLine2: professional?.addressLine2 ?? '',
        contactPhone: professional?.contactPhone ?? '',
      },
      { emitEvent: false },
    );
  }
}
