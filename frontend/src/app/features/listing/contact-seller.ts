import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { MessagesService, PublicListingDetail } from '../../core/api';
import { RETURN_URL_PARAM, SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { MESSAGE_MAX_LENGTH, sendFailure } from '../messages/messaging';

/**
 * "Contacter le vendeur": the way a buyer reaches a seller. Through the platform only — the
 * seller's email and phone are never shown, and the buyer's are never passed on.
 *
 * <p>Four states: signed out, the link signs in and comes back here; the seller looking at
 * their own listing, a pointer to their messages; anyone else, a message form that opens in
 * place with a first line already written; and sent, a confirmation and the way to the
 * conversation. Writing again continues the same conversation, so there is no state for
 * "already contacted".
 */
@Component({
  selector: 'app-contact-seller',
  imports: [RouterLink, Alert, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './contact-seller.html',
  styleUrl: './contact-seller.scss',
})
export class ContactSeller {
  private readonly auth = inject(AuthService);
  private readonly api = inject(MessagesService);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);

  readonly listing = input.required<PublicListingDetail>();

  protected readonly maxLength = MESSAGE_MAX_LENGTH;
  protected readonly signInRoute = SIGN_IN_ROUTE;
  protected readonly signInQuery = computed(() => ({ [RETURN_URL_PARAM]: `/annonces/${this.listing().id}` }));

  protected readonly signedIn = this.auth.isAuthenticated;
  /** Known only once the profile has loaded; until then the form is offered, and the API refuses. */
  protected readonly ownListing = computed(() => {
    const me = this.auth.user()?.id;
    return !!me && me === this.listing().seller?.id;
  });

  /** The message form is open: someone is writing. */
  readonly open = signal(false);
  protected readonly body = signal('');
  protected readonly sending = signal(false);
  protected readonly failure = signal<string | null>(null);
  /** The conversation the message went to, once sent. */
  protected readonly sentTo = signal<string | null>(null);

  /** The form replaces the button, so focus follows it into the text rather than being lost. */
  private readonly message = viewChild<ElementRef<HTMLTextAreaElement>>('message');

  constructor() {
    effect(() => this.message()?.nativeElement.focus());
  }

  protected readonly trimmed = computed(() => this.body().trim());
  protected readonly canSend = computed(
    () => this.trimmed().length > 0 && this.body().length <= MESSAGE_MAX_LENGTH && !this.sending(),
  );

  /**
   * What the phone's bottom bar does: the same as this component's own button in its
   * current state. Signed out, sign in and come back; sent, the conversation; otherwise
   * the form, opened and focused — which brings it into view and, on a phone, the
   * keyboard up.
   */
  engage(): void {
    if (!this.signedIn()) {
      void this.router.navigate([this.signInRoute], { queryParams: this.signInQuery() });
    } else if (this.sentTo()) {
      void this.router.navigate(['/messages', this.sentTo()]);
    } else if (!this.ownListing()) {
      this.start();
    }
  }

  protected start(): void {
    if (!this.body()) {
      // A first line to edit, not a blank box: most buyers ask the same thing first.
      this.body.set(`Bonjour, votre annonce « ${this.listing().title} » m’intéresse. Est-elle toujours disponible ?`);
    }
    this.failure.set(null);
    this.sentTo.set(null);
    this.open.set(true);
  }

  protected cancel(): void {
    this.open.set(false);
    this.failure.set(null);
  }

  protected edit(event: Event): void {
    this.body.set((event.target as HTMLTextAreaElement).value);
  }

  protected send(event: Event): void {
    event.preventDefault();
    const id = this.listing().id;
    if (!this.canSend() || !id) {
      return;
    }
    this.sending.set(true);
    this.failure.set(null);
    this.api.contactSeller({ listingId: id, sendMessageRequest: { body: this.trimmed() } }).subscribe({
      next: (conversation) => {
        this.sending.set(false);
        this.open.set(false);
        this.body.set('');
        this.sentTo.set(conversation.id ?? null);
      },
      error: (error: unknown) => {
        this.sending.set(false);
        this.failure.set(sendFailure(error, this.errors));
      },
    });
  }
}
