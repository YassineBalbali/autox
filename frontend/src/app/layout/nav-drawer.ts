import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationStart, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';

import { UserRole } from '../core/api';
import { AuthService } from '../core/auth/auth.service';
import { UnreadMessages } from '../core/messaging/unread-messages';
import { OverlayEntry, OverlayHistory } from '../core/ui/overlay-history';
import { Logo } from '../shared/brand/logo';
import { Icon } from '../shared/ui/icon';
import { NavItem } from './primary-nav';

/**
 * The phone's navigation: a burger in the top bar, and behind it a drawer with the primary
 * navigation and everything the desktop's account menu holds.
 *
 * <p>Below the medium breakpoint only. Above it the header has room for the navigation and
 * the account menu themselves, and this renders nothing visible.
 *
 * <p>A native modal `<dialog>`, for what it does without code: focus stays inside while it
 * is open, the page behind is inert to a screen reader and to a tap, Escape closes it, and
 * focus goes back to the burger afterwards. A hand-made overlay has to reimplement each of
 * those, and is usually missing one.
 *
 * <p>Back closes it and does nothing else, as on any phone: see {@link OverlayHistory}. Its
 * links replace the history entry the drawer added rather than stacking a page on top of
 * it, so Back from where a link led returns to the page the drawer was opened on.
 *
 * <p>It also closes on any route change it did not start itself, so it is never left open
 * over a page it did not lead to.
 */
@Component({
  selector: 'app-nav-drawer',
  imports: [RouterLink, RouterLinkActive, Logo, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      class="burger"
      type="button"
      aria-haspopup="dialog"
      [attr.aria-expanded]="open()"
      (click)="show()"
    >
      <app-icon name="menu" [size]="24" />
      <span class="visually-hidden" i18n="@@drawerOpen">Menu</span>
    </button>

    <dialog
      #dialog
      class="drawer"
      aria-label="Menu"
      i18n-aria-label="@@drawerLabel"
      (close)="hide()"
    >
      <div class="drawer__panel">
        <div class="drawer__top">
          <a class="drawer__brand" routerLink="/" aria-label="AutoX — accueil" i18n-aria-label="@@brandHome" [replaceUrl]="true" (click)="leave()">
            <app-logo />
          </a>
          <button class="drawer__close" type="button" (click)="hide()">
            <app-icon name="x" [size]="24" />
            <span class="visually-hidden" i18n="@@drawerClose">Fermer le menu</span>
          </button>
        </div>

        <nav class="drawer__section" aria-label="Navigation principale" i18n-aria-label="@@navPrimaryLabel">
          @for (item of items(); track item.path) {
            <a
              class="drawer__link drawer__link--primary"
              [routerLink]="'/' + item.path"
              routerLinkActive="drawer__link--active"
              [routerLinkActiveOptions]="{ exact: item.path === '' }"
              [replaceUrl]="true"
              (click)="leave()"
            >
              {{ item.label }}
            </a>
          }
        </nav>

        @if (signedIn()) {
          <section class="drawer__section" aria-labelledby="drawer-account">
            <h2 id="drawer-account" class="drawer__heading">
              <app-icon name="user" [size]="16" />
              <span class="drawer__account-name">{{ accountName() }}</span>
            </h2>
            <a class="drawer__link" routerLink="/messages" routerLinkActive="drawer__link--active" [replaceUrl]="true" (click)="leave()">
              <span i18n="@@headerMessages">Messages</span>
              @if (unread() > 0) {
                <span class="count-badge" aria-hidden="true">{{ unread() > 99 ? '99+' : unread() }}</span>
                <span class="visually-hidden">{{ unreadLabel() }}</span>
              }
            </a>
            <a class="drawer__link" routerLink="/mes-annonces" routerLinkActive="drawer__link--active" [replaceUrl]="true" (click)="leave()" i18n="@@headerMyListings">
              Mes annonces
            </a>
            <a
              class="drawer__link"
              routerLink="/mon-compte"
              routerLinkActive="drawer__link--active"
              [routerLinkActiveOptions]="{ exact: true }"
              [replaceUrl]="true"
              (click)="leave()"
              i18n="@@headerAccount"
            >
              Mon compte
            </a>
            <a class="drawer__link" routerLink="/mon-compte/sessions" routerLinkActive="drawer__link--active" [replaceUrl]="true" (click)="leave()" i18n="@@navSessions">
              Appareils connectés
            </a>
            @if (isAdmin()) {
              <a class="drawer__link" routerLink="/admin" routerLinkActive="drawer__link--active" [replaceUrl]="true" (click)="leave()" i18n="@@headerAdmin">
                Administration
              </a>
            }
            <button class="drawer__link drawer__link--danger" type="button" (click)="signOut()">
              <app-icon name="log-out" [size]="18" />
              <span i18n="@@signOut">Se déconnecter</span>
            </button>
          </section>
        } @else {
          <div class="drawer__section drawer__auth">
            <a class="btn btn--primary drawer__cta" routerLink="/inscription" [replaceUrl]="true" (click)="leave()" i18n="@@headerRegister">
              Créer un compte
            </a>
            <a class="btn btn--quiet drawer__cta" routerLink="/connexion" [replaceUrl]="true" (click)="leave()" i18n="@@headerSignIn">
              Se connecter
            </a>
          </div>
        }
      </div>
    </dialog>
  `,
  styles: `
    @use '../../styles/tokens' as t;

    :host {
      display: contents;
    }

    .burger,
    .drawer__close {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      flex: none;
      width: 44px;
      height: 44px;
      padding: 0;
      border: 0;
      border-radius: var(--r-md);
      background: none;
      color: var(--c-ink);
    }

    .burger {
      // Flush with the gutter: the icon's own padding lines it up with the page edge.
      margin-left: calc(-1 * var(--s-2));

      @media (min-width: t.$bp-md) {
        display: none;
      }
    }

    // ---- the drawer itself ------------------------------------------------------------

    // The dialog is the full-screen layer; the panel is the white column on its left. A
    // tap on the dialog outside the panel is a tap on the backdrop, and closes it.
    .drawer {
      position: fixed;
      inset: 0;
      width: 100%;
      max-width: none;
      height: 100dvh;
      max-height: none;
      margin: 0;
      padding: 0;
      border: 0;
      background: transparent;
      color: var(--c-ink);

      &::backdrop {
        background: rgb(11 31 58 / 45%);
      }

      &[open] .drawer__panel {
        animation: drawer-in 180ms ease-out;
      }
    }

    @keyframes drawer-in {
      from {
        transform: translateX(-100%);
      }
    }

    .drawer__panel {
      display: flex;
      flex-direction: column;
      width: min(20rem, 85vw);
      height: 100%;
      overflow-y: auto;
      overscroll-behavior: contain;
      background: var(--c-surface);
      box-shadow: var(--shadow-overlay);
      // Clear of a phone's home indicator.
      padding-bottom: env(safe-area-inset-bottom);
    }

    .drawer__top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex: none;
      height: var(--h-header);
      padding-inline: var(--gutter) var(--s-2);
      border-bottom: 2px solid var(--c-navy);
    }

    .drawer__brand {
      display: flex;
      align-items: center;
      min-height: 44px;
    }

    .drawer__section {
      display: flex;
      flex-direction: column;
      padding-block: var(--s-2);
      border-bottom: var(--divider);

      &:last-child {
        border-bottom: 0;
      }
    }

    .drawer__heading {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      padding: var(--s-2) var(--gutter) var(--s-1);
      color: var(--c-ink-soft);
      font-size: var(--t-xs);
      font-weight: var(--fw-semibold);
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .drawer__account-name {
      // A display name is user-supplied and can be any length.
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      text-transform: none;
      letter-spacing: 0;
    }

    // One row per destination, 48px: bigger than the 44px floor, because these are the
    // rows a thumb hits from the far side of the screen.
    .drawer__link {
      display: flex;
      align-items: center;
      gap: var(--s-2);
      min-height: 48px;
      padding: 0 var(--gutter);
      border: 0;
      // The active marker's room, kept on every row so the labels line up.
      border-left: 3px solid transparent;
      background: none;
      color: var(--c-ink);
      font-size: var(--t-md);
      text-align: left;
      text-decoration: none;

      &:active {
        background: var(--c-surface-sunk);
      }
    }

    .drawer__link--primary {
      font-weight: var(--fw-bold);
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }

    // The same idiom as the desktop navigation's bar, turned on its side.
    .drawer__link--active {
      border-left-color: var(--c-accent);
      color: var(--c-accent);
    }

    .drawer__link--danger {
      color: var(--c-danger);
    }

    .drawer__auth {
      gap: var(--s-2);
      padding: var(--s-4) var(--gutter);
    }

    .drawer__cta {
      width: 100%;
      height: 44px;
      font-size: var(--t-md);
    }
  `,
})
export class NavDrawer {
  private readonly auth = inject(AuthService);

  /** The primary navigation, as the header resolved it. */
  readonly items = input.required<readonly NavItem[]>();

  protected readonly open = signal(false);
  protected readonly signedIn = this.auth.isAuthenticated;
  protected readonly isAdmin = computed(() => this.auth.role() === UserRole.ADMIN);
  protected readonly accountName = computed(
    () => this.auth.user()?.displayName || this.auth.user()?.email || 'Mon compte',
  );

  protected readonly unread = inject(UnreadMessages).count;
  protected readonly unreadLabel = computed(() => {
    const count = this.unread();
    return count === 1 ? ', 1 conversation non lue' : `, ${count} conversations non lues`;
  });

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly history = inject(OverlayHistory);
  private entry: OverlayEntry | null = null;

  constructor() {
    effect(() => {
      const dialog = this.dialog().nativeElement;
      if (this.open() && !dialog.open) {
        dialog.showModal();
      } else if (!this.open() && dialog.open) {
        dialog.close();
      }
    });

    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationStart),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.leave());

    // A tap on the backdrop closes it. Bound here rather than with (click) in the template,
    // where it would read as a mouse-only control on a non-focusable element: the keyboard's
    // way out is Escape, which the dialog handles itself.
    afterNextRender(() => {
      const dialog = this.dialog().nativeElement;
      dialog.addEventListener('click', (event) => {
        if (event.target === dialog) {
          this.hide();
        }
      });
    });
  }

  protected show(): void {
    this.open.set(true);
    this.entry = this.history.open(() => {
      this.entry = null;
      this.open.set(false);
    });
  }

  /** Closed from its own controls: the close button, the backdrop, Escape. */
  protected hide(): void {
    this.open.set(false);
    this.entry?.closed();
    this.entry = null;
  }

  /** Closed because a link was followed: the navigation takes over its history entry. */
  protected leave(): void {
    this.open.set(false);
    this.entry?.release();
    this.entry = null;
  }

  protected signOut(): void {
    this.leave();
    this.auth.logout('/connexion');
  }
}
