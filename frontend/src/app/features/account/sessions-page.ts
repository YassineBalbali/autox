import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ErrorCode, SessionResponse, SessionsService } from '../../core/api';
import { deviceLabel } from '../../core/auth/device-label';
import { asApiError } from '../../core/errors/api-error';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { formatDateTimeFr } from '../../shared/format/date';
import { Alert } from '../../shared/ui/alert';

/** One row, with everything the template needs already resolved. */
interface SessionRow {
  readonly id: string;
  readonly label: string;
  readonly userAgent: string | null;
  readonly ipAddress: string | null;
  readonly createdAt: string | null;
  readonly lastUsedAt: string | null;
  readonly expiresAt: string | null;
  /** True when this row is, as far as can be told, the browser being used right now. */
  readonly isThisDevice: boolean;
}

/**
 * Every live session on the account, and a way to end one.
 *
 * <p>This screen exists because a refresh token lives for ninety days on a device that can
 * be lost, sold or stolen, and until the backend grew this endpoint the only remedy was
 * changing the password — which ends every session including the one in use.
 *
 * <h2>Marking "this device"</h2>
 *
 * <p>The API cannot say which row belongs to the caller: an access token carries no
 * reference to the refresh family it was issued with, so there is no id to compare. Its own
 * documentation suggests matching on the `deviceLabel` the client chose at sign-in, which
 * is what happens here — the label is derived from the user agent, so it is reproducible
 * without storing anything.
 *
 * <p>It is a guess, and it is wrong in one specific case: two sessions opened from the same
 * browser on the same machine produce the same label. So when a label is not unique
 * <b>nothing is marked</b>, rather than the first match being marked and possibly being the
 * wrong one. Telling a user that a session is theirs when it is not is how a stolen session
 * survives this screen, and it is worse than telling them nothing.
 */
@Component({
  selector: 'app-sessions-page',
  imports: [Alert],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sessions-page.html',
  styleUrl: './account.scss',
})
export class SessionsPage {
  private readonly api = inject(SessionsService);
  private readonly errors = inject(ErrorMessageService);

  private readonly sessions = signal<SessionResponse[] | null>(null);

  protected readonly loadFailure = signal<string | null>(null);
  protected readonly actionFailure = signal<string | null>(null);

  /** The session currently being ended, so only its own button shows as busy. */
  protected readonly revoking = signal<string | null>(null);

  protected readonly rows = computed<SessionRow[] | null>(() => {
    const sessions = this.sessions();
    if (sessions === null) {
      return null;
    }

    const thisDevice = deviceLabel();
    const timesSeen = sessions.filter((session) => session.deviceLabel === thisDevice).length;

    return sessions
      .filter((session): session is SessionResponse & { id: string } => !!session.id)
      .map((session) => ({
        id: session.id,
        label: session.deviceLabel?.trim() || 'Appareil non identifié',
        userAgent: session.userAgent ?? null,
        ipAddress: session.ipAddress ?? null,
        createdAt: formatDateTimeFr(session.createdAt),
        lastUsedAt: formatDateTimeFr(session.lastUsedAt),
        expiresAt: formatDateTimeFr(session.expiresAt),
        isThisDevice: timesSeen === 1 && session.deviceLabel === thisDevice,
      }));
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loadFailure.set(null);
    this.api.listOwnSessions().subscribe({
      next: (sessions) => this.sessions.set(sessions),
      error: (error: unknown) => this.loadFailure.set(this.errors.messageFor(error)),
    });
  }

  protected revoke(row: SessionRow): void {
    this.actionFailure.set(null);
    this.revoking.set(row.id);

    this.api.revokeOwnSession({ sessionId: row.id }).subscribe({
      next: () => {
        this.revoking.set(null);
        // Removed locally rather than refetched. The list is a handful of rows and the
        // result is not in doubt — and re-reading it would send a request on the very
        // session that may have just been ended, producing a sign-out as the visible
        // outcome of a successful action.
        this.sessions.update((sessions) =>
          (sessions ?? []).filter((session) => session.id !== row.id),
        );
      },
      error: (error: unknown) => {
        this.revoking.set(null);

        // A 404 is the ordinary outcome, not a fault: the session had already expired, or
        // was ended from another device while this page was open. The user asked for it to
        // be gone and it is gone, so the row goes and nothing is reported — an error
        // message here would describe a success.
        if (asApiError(error)?.code === ErrorCode.NOT_FOUND) {
          this.sessions.update((sessions) =>
            (sessions ?? []).filter((session) => session.id !== row.id),
          );
          return;
        }

        // Anything else and the session may well still be live, so the row stays: removing
        // it would tell the user a device had been cut off when it had not.
        this.actionFailure.set(this.errors.messageFor(error));
      },
    });
  }
}
