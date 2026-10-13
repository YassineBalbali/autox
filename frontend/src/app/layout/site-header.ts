import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { routes } from '../app.routes';
import { ProfileService, UserRole } from '../core/api';
import { AuthService, userSummaryOf } from '../core/auth/auth.service';
import { Logo } from '../shared/brand/logo';
import { availableNav } from './primary-nav';

/**
 * The site header: wordmark, primary navigation, account menu.
 *
 * <p>Everything sits inside `.page`, the same container `main` and the footer use, so the
 * wordmark lines up with the page title underneath it. A header that spans the viewport
 * while the content below is capped at 1200px is the detail that makes a wide screen look
 * like a phone layout someone stretched.
 *
 * <p>The account menu is a disclosure rather than a row of links because on a signed-in
 * desktop header the right-hand side is the account, not four competing destinations.
 * Below the medium breakpoint the primary navigation is hidden: this is the desktop pass,
 * and a row of links wrapping under the wordmark is the kind of not-broken-but-unpolished
 * the mobile pass will deal with properly with a drawer.
 */
@Component({
  selector: 'app-site-header',
  imports: [RouterLink, RouterLinkActive, Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './site-header.html',
  styleUrl: './site-header.scss',
})
export class SiteHeader {
  private readonly auth = inject(AuthService);
  private readonly profile = inject(ProfileService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  protected readonly signedIn = this.auth.isAuthenticated;

  /** Falls back to the email, and then to a neutral word, so the button is never blank. */
  protected readonly accountName = computed(
    () => this.auth.user()?.displayName || this.auth.user()?.email || 'Mon compte',
  );

  /**
   * Read from the application's own route table rather than from `Router.config`, so the
   * header renders the same links under test as it does in the browser — a test that
   * provides an empty router would otherwise see an empty navigation and prove nothing.
   */
  /**
   * Whether to offer the administration entry.
   *
   * <p>True only when the role is <em>known</em> to be ADMIN. After a reload the session
   * is restored from storage with no profile, and until `GET /me` answers the role is
   * unknown — in which case the entry is absent, never guessed. Hiding it is a matter of
   * tidiness, not of security: the route is guarded, and the API behind it is what
   * actually refuses.
   */
  protected readonly isAdmin = computed(() => this.auth.role() === UserRole.ADMIN);

  protected readonly navItems = availableNav(routes);

  protected readonly menuOpen = signal(false);

  constructor() {
    // A session restored from storage knows its tokens and nothing else. The header is
    // on every page and shows who is signed in, so it is the one to ask — once, and only
    // when nobody else has. A failure changes nothing here: the interceptor deals with a
    // dead session, and the menu simply keeps its generic label.
    effect(() => {
      if (this.signedIn() && this.auth.user() === null && !this.asked) {
        this.asked = true;
        this.profile.getOwnProfile().subscribe({
          next: (me) => this.auth.setUser(userSummaryOf(me)),
          error: () => undefined,
        });
      }
      if (!this.signedIn()) {
        this.asked = false;
      }
    });
  }

  private asked = false;

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  protected signOut(): void {
    this.closeMenu();
    this.auth.logout('/connexion');
  }

  /**
   * Closes the menu on a click anywhere else.
   *
   * <p>A dropdown that stays open until its own button is pressed again is the most
   * reliably irritating control on a desktop page: the user clicks the thing they wanted,
   * the menu stays over it, and they click twice more to get rid of it.
   */
  @HostListener('document:click', ['$event.target'])
  protected onDocumentClick(target: EventTarget | null): void {
    if (this.menuOpen() && target instanceof Node && !this.host.nativeElement.contains(target)) {
      this.closeMenu();
    }
  }

  /** Escape closes it, and focus goes back to the button that opened it. */
  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.menuOpen()) {
      this.closeMenu();
      this.host.nativeElement.querySelector<HTMLElement>('.account-menu__button')?.focus();
    }
  }
}
