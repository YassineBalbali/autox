import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { ProfileService } from '../../core/api';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { TextField } from '../../shared/forms/text-field';
import { requiredText } from '../../shared/forms/validation';
import { submitState } from '../../shared/forms/submit-state';
import { Alert } from '../../shared/ui/alert';

/** Where the browser saves the export: today's date, so two downloads do not overwrite each other. */
export function exportFileName(now = new Date()): string {
  const day = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')];
  return `autox-mes-donnees-${day.join('-')}.json`;
}

/** Hands the browser a JSON document to save. */
export function saveJson(document: unknown, fileName: string): void {
  const blob = new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = window.document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.click();
  URL.revokeObjectURL(url);
}

/**
 * Mes données: a copy of everything the account holds, and deleting the account.
 *
 * <p>Deleting says what will happen before it is asked for, and needs the password again: a
 * session left open on a shared computer must not be enough to erase an account. Once done, the
 * session is gone with the account, so the page clears it here and leaves for a page that says so.
 */
@Component({
  selector: 'app-my-data-page',
  imports: [ReactiveFormsModule, RouterLink, TextField, Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-data-page.html',
  styleUrl: './account.scss',
})
export class MyDataPage {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly errors = inject(ErrorMessageService);

  protected readonly exporting = signal(false);
  protected readonly exported = signal(false);
  protected readonly exportFailure = signal<string | null>(null);

  protected readonly deletion = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: [requiredText()] }),
  });
  protected readonly state = submitState(this.deletion);

  protected download(): void {
    if (this.exporting()) {
      return;
    }
    this.exporting.set(true);
    this.exported.set(false);
    this.exportFailure.set(null);
    this.api.exportData().subscribe({
      next: (document) => {
        saveJson(document, exportFileName());
        this.exporting.set(false);
        this.exported.set(true);
      },
      error: (error: unknown) => {
        this.exporting.set(false);
        this.exportFailure.set(this.errors.translate(error).message);
      },
    });
  }

  protected deleteAccount(): void {
    if (this.deletion.invalid) {
      this.deletion.markAllAsTouched();
      return;
    }
    this.state.begin();
    this.api.deleteAccount({ deleteAccountRequest: { password: this.deletion.getRawValue().password } }).subscribe({
      next: () => {
        this.state.succeed();
        // The server has already ended every session; this one's tokens are worthless now.
        this.auth.clearSession();
        void this.router.navigateByUrl('/compte-supprime');
      },
      error: (error: unknown) => this.state.fail(error),
    });
  }
}
