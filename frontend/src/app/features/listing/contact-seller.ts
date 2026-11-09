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

import { Observable } from 'rxjs';

import { ConversationDetail, DealerPage, DealersService, MessagesService, PublicListingDetail } from '../../core/api';
import { RETURN_URL_PARAM, SIGN_IN_ROUTE } from '../../core/auth/auth.routes';
import { AuthService } from '../../core/auth/auth.service';
import { ErrorMessageService } from '../../core/errors/error-message.service';
import { EmailVerificationNotice } from '../../shared/account/email-verification-notice';
import { Alert } from '../../shared/ui/alert';
import { Icon } from '../../shared/ui/icon';
import { MESSAGE_MAX_LENGTH, sendFailure } from '../messages/messaging';

/**
 * "Contacter le vendeur": the way a buyer reaches a seller. Through the platform only — the
 * seller's email and phone are never shown, and the buyer's are never passed on.
 *
 * <p>Five states: signed out, the link signs in and comes back here; the seller looking at
 * their own listing, a pointer to their messages; an account that has to verify its address
 * first, what to do about it; anyone else, a message form that opens in place with a first line
 * already written; and sent, a confirmation and the way to the conversation. Writing again continues the same conversation, so there is no state for
 * "already contacted".
 *
 * <p>Two things can be written to: a listing's seller, about the listing, and a dealer from their
 * page, about nothing in particular. Give one of `listing` and `dealer`. The form, the rules and
 * the states are the same; only the first line, the way back after signing in, and where the
 * message goes differ.
 */
@Component({
  selector: 'app-contact-seller',
  imports: [RouterLink, Alert, Icon, EmailVerificationNotice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './contact-seller.html',
  styleUrl: './contact-seller.scss',
})
export class ContactSeller {
  private readonly auth = inject(AuthService);
  private readonly api = inject(MessagesService);
  private readonly dealers = inject(DealersService);
  private readonly errors = inject(ErrorMessageService);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly listing = input<PublicListingDetail | null>(null);
  /** A dealer's page: the message goes to the dealer, from there. */
  readonly dealer = input<DealerPage | null>(null);

  /** Who is written to, whichever of the two was given. */
  protected readonly target = computed(() => {
    const dealer = this.dealer();
    if (dealer) {
      return {
        sellerId: dealer.sellerId,
        name: dealer.name,
        demo: dealer.demo === true,
        back: `/professionnels/${dealer.slug}`,
        greeting: `Bonjour, j’ai vu votre page sur AutoX. `,
      };
    }
    const listing = this.listing();
    return {
      sellerId: listing?.seller?.id,
      name: listing?.seller?.displayName,
      demo: listing?.seller?.demo === true,
      back: `/annonces/${listing?.id}`,
      greeting: `Bonjour, votre annonce « ${listing?.title} » m’intéresse. Est-elle toujours disponible ?`,
    };
  });

  protected readonly maxLength = MESSAGE_MAX_LENGTH;
  protected readonly signInRoute = SIGN_IN_ROUTE;
  protected readonly signInQuery = computed(() => ({ [RETURN_URL_PARAM]: this.target().back }));

  protected readonly signedIn = this.auth.isAuthenticated;
  /** Known only once the profile has loaded; until then the form is offered, and the API refuses. */
  protected readonly ownListing = computed(() => {
    const me = this.auth.user()?.id;
    return !!me && me === this.target().sellerId;
  });
  protected readonly dealerPage = computed(() => this.dealer() !== null);

  /** This account cannot write yet. Known once the profile has loaded, as above. */
  protected readonly mustVerify = computed(() => this.auth.user()?.emailVerificationPending === true);

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
    } else if (this.mustVerify()) {
      // Nothing to open: the notice says what to do, and the bar's button has to lead to it.
      this.host.nativeElement.scrollIntoView?.({ block: 'center' });
    } else if (!this.ownListing()) {
      this.start();
    }
  }

  protected start(): void {
    if (!this.body()) {
      // A first line to edit, not a blank box: most buyers ask the same thing first.
      this.body.set(this.target().greeting);
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
    const request = this.request(this.trimmed());
    if (!this.canSend() || !request) {
      return;
    }
    this.sending.set(true);
    this.failure.set(null);
    request.subscribe({
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

  /** The dealer's own endpoint from their page, the listing's otherwise. */
  private request(body: string): Observable<ConversationDetail> | null {
    const dealer = this.dealer();
    if (dealer?.slug) {
      return this.dealers.contactDealer({ slug: dealer.slug, sendMessageRequest: { body } });
    }
    const id = this.listing()?.id;
    return id ? this.api.contactSeller({ listingId: id, sendMessageRequest: { body } }) : null;
  }
}
