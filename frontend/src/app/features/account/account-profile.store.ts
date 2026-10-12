import { Injectable, inject, signal } from '@angular/core';

import { ProfileService, UserProfileResponse } from '../../core/api';
import { AuthService, userSummaryOf } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';

/**
 * The signed-in account's profile, loaded once for the whole account section.
 *
 * <p><b>Provided by `AccountLayout`, not in the root injector</b>, so its lifetime is the
 * section's: entering the account loads the profile, leaving it discards it, and coming
 * back reads fresh data rather than whatever was true an hour ago.
 *
 * <p>It exists because the sidebar needs the account's role — "Entreprise" is a dealer
 * entry — and the layout renders before any page inside it. Without a shared load, going
 * straight to `/mon-compte/sessions` would leave the sidebar with no role to go on and
 * silently hide a dealer's own company screen; while on `/mon-compte` the layout and the
 * profile page would each ask for `GET /me` and the section would open with two identical
 * requests in flight.
 *
 * <p>{@link ensureLoaded} is therefore idempotent and called by everything that needs the
 * profile — the layout and each page that reads it. Whichever runs first fetches; the
 * rest attach to the result. That also keeps each page able to stand on its own, which is
 * what lets them be tested without the layout around them.
 */
@Injectable()
export class AccountProfileStore {
  private readonly api = inject(ProfileService);
  private readonly auth = inject(AuthService);
  private readonly errors = inject(ErrorMessageService);

  private readonly current = signal<UserProfileResponse | null>(null);
  private readonly failure = signal<string | null>(null);
  private requested = false;

  /** The profile, or `null` while it is loading or has failed to load. */
  readonly profile = this.current.asReadonly();

  /** A French sentence when the load failed, for a screen to show with a retry. */
  readonly loadFailure = this.failure.asReadonly();

  /** Loads the profile unless a load has already been started. */
  ensureLoaded(): void {
    if (this.requested) {
      return;
    }
    this.load();
  }

  /** Loads it again regardless — the retry button, and nothing else. */
  reload(): void {
    this.load();
  }

  /**
   * Adopts a profile the caller already has.
   *
   * <p>Both profile forms save with a PATCH that returns the updated profile, so there is
   * nothing to re-fetch. Going back to the API would also mean the screen briefly showing
   * stale values it had just replaced.
   */
  adopt(profile: UserProfileResponse): void {
    this.current.set(profile);
    this.failure.set(null);
    this.auth.setUser(userSummaryOf(profile));
  }

  private load(): void {
    this.requested = true;
    this.failure.set(null);

    this.api.getOwnProfile().subscribe({
      next: (profile) => this.adopt(profile),
      error: (error: unknown) => {
        // Left loadable again: the usual cause is a dropped connection, and a retry that
        // did nothing because a flag was still set would be worse than no retry at all.
        this.requested = false;
        this.failure.set(this.errors.messageFor(error));
      },
    });
  }
}
