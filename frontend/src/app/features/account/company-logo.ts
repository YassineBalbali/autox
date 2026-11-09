import { HttpClient, HttpEventType, HttpHeaders, HttpRequest } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { filter, switchMap } from 'rxjs';

import { ProfileService } from '../../core/api';
import { skipAuth } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { DealerLogo } from '../../shared/dealer/dealer-logo';
import { Alert } from '../../shared/ui/alert';
import { PHOTO_RULES } from '../seller/photo-upload.service';
import { AccountProfileStore } from './account-profile.store';

/**
 * A dealer's logo, on their company page: the one there is, or their initials, and a file to
 * replace it with.
 *
 * <p>Through the photos' pipeline, in the same three steps: the API gives a slot, the file goes
 * straight to storage, the API confirms. The confirmation is where the image is decoded, turned
 * upright, stripped of its metadata (EXIF, GPS) and resized; what the page then shows is that, not
 * what was chosen.
 */
@Component({
  selector: 'app-company-logo',
  imports: [Alert, DealerLogo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="logo-field">
      <app-dealer-logo [name]="name()" [logoUrl]="logoUrl()" [large]="true" />
      <div class="stack stack--tight">
        <span class="logo-field__label" id="logo-label" i18n="@@companyLogo">Logo</span>
        <p class="meta" i18n="@@companyLogoHint">
          JPEG ou PNG, 10 Mo au plus. Sans logo, vos initiales s’affichent. Les métadonnées de l’image (EXIF,
          position GPS) sont supprimées.
        </p>
        <div class="logo-field__actions">
          <input
            #file
            class="visually-hidden"
            type="file"
            id="logo-file"
            accept="image/jpeg,image/png"
            aria-labelledby="logo-label"
            (change)="chosen($event)"
          />
          <button class="btn btn--quiet" type="button" data-action="choose-logo" [disabled]="busy()" (click)="file.click()">
            @if (busy()) {
              <ng-container i18n="@@companyLogoSending">Envoi…</ng-container>
            } @else if (logoUrl()) {
              <ng-container i18n="@@companyLogoReplace">Remplacer le logo</ng-container>
            } @else {
              <ng-container i18n="@@companyLogoAdd">Ajouter un logo</ng-container>
            }
          </button>
          @if (logoUrl() && !busy()) {
            <button class="btn btn--quiet" type="button" data-action="remove-logo" (click)="remove()" i18n="@@companyLogoRemove">
              Retirer
            </button>
          }
        </div>
        @if (failure()) {
          <app-alert kind="error" data-logo-failure>{{ failure() }}</app-alert>
        }
      </div>
    </div>
  `,
  styles: `
    .logo-field {
      display: flex;
      gap: var(--s-4);
      align-items: flex-start;
    }

    .logo-field__label {
      font-weight: var(--fw-semibold);
    }

    .logo-field__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--s-2);
    }

    p {
      margin: 0;
    }
  `,
})
export class CompanyLogo {
  private readonly api = inject(ProfileService);
  private readonly http = inject(HttpClient);
  private readonly store = inject(AccountProfileStore);
  private readonly errors = inject(ErrorMessageService);

  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('file');

  protected readonly name = computed(
    () => this.store.profile()?.displayName ?? this.store.profile()?.professional?.companyName ?? '',
  );
  protected readonly logoUrl = computed(() => this.store.profile()?.professional?.logoUrl ?? null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected chosen(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    this.input().nativeElement.value = '';
    if (!file) {
      return;
    }
    const refused = logoRejection(file);
    if (refused) {
      this.failure.set(refused);
      return;
    }
    this.failure.set(null);
    this.busy.set(true);
    this.api
      .requestLogoUpload({ imageUploadRequest: { filename: file.name, contentType: file.type, byteSize: file.size } })
      .pipe(
        switchMap((ticket) => {
          if (!ticket.imageId || !ticket.uploadUrl) {
            throw new Error('The upload ticket carried no id or URL.');
          }
          // Straight to storage, with exactly the signed headers and no bearer token: see PhotoUploadService.
          return this.http
            .request(
              new HttpRequest(ticket.method ?? 'PUT', ticket.uploadUrl, file, {
                headers: new HttpHeaders(ticket.requiredHeaders ?? {}),
                context: skipAuth(),
              }),
            )
            .pipe(
              // The upload's answer, after the last byte: confirming earlier finds nothing stored.
              filter((answer) => answer.type === HttpEventType.Response),
              switchMap(() => this.api.confirmLogoUpload({ uploadId: ticket.imageId! })),
            );
        }),
      )
      .subscribe({
        next: (profile) => {
          this.busy.set(false);
          this.store.adopt(profile);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(this.errors.messageFor(error));
        },
      });
  }

  protected remove(): void {
    this.failure.set(null);
    this.busy.set(true);
    this.api.removeLogo().subscribe({
      next: (profile) => {
        this.busy.set(false);
        this.store.adopt(profile);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(this.errors.messageFor(error));
      },
    });
  }
}

/** Why a file cannot be a logo, in French, or null. The API checks again. */
export function logoRejection(file: File): string | null {
  if (!(PHOTO_RULES.contentTypes as readonly string[]).includes(file.type)) {
    return `« ${file.name} » n’est pas une image JPEG ou PNG.`;
  }
  if (file.size === 0) {
    return `« ${file.name} » est un fichier vide.`;
  }
  if (file.size > PHOTO_RULES.maxBytes) {
    return `« ${file.name} » est trop volumineux : 10 Mo au plus.`;
  }
  return null;
}
