import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { IsActiveMatchOptions, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { Icon } from '../../shared/ui/icon';
import { IconName } from '../../shared/ui/icons.data';

/** What makes an entry the current one: the path alone, whatever the list or the page in the query. */
const THIS_PAGE: IsActiveMatchOptions = {
  paths: 'exact',
  queryParams: 'ignored',
  matrixParams: 'ignored',
  fragment: 'ignored',
};
const THIS_SECTION: IsActiveMatchOptions = { ...THIS_PAGE, paths: 'subset' };

export interface AdminNavItem {
  readonly label: string;
  /** What the entry is about, where the label alone leaves it open. */
  readonly hint?: string;
  readonly icon: IconName;
  /** Relative to `/admin`, as declared in the route table. */
  readonly path: string;
  /** Whether pages under this one count as this one. */
  readonly section?: boolean;
}

/** Only pages that exist: `app.routes.spec.ts` fails on an entry with no route behind it. */
export const ADMIN_NAV: readonly AdminNavItem[] = [
  { label: 'Tableau de bord', icon: 'gauge', path: '' },
  { label: 'Modération', hint: 'Annonces', icon: 'shield-check', path: 'moderation' },
  { label: 'Signalements', hint: 'Messages', icon: 'flag', path: 'moderation/messages' },
  { label: 'Utilisateurs', icon: 'users', path: 'utilisateurs', section: true },
];

/** The entry a URL belongs to, for the collapsed menu's label. */
export function adminNavItemFor(url: string): AdminNavItem {
  const path = url.split(/[?#]/)[0].replace(/^\/admin\/?/, '');
  return (
    ADMIN_NAV.find((item) => item.path === path) ??
    ADMIN_NAV.find((item) => item.section && path.startsWith(`${item.path}/`)) ??
    ADMIN_NAV[0]
  );
}

/**
 * The back office's frame: a sidebar, and the page on its right.
 *
 * <p>On a phone the sidebar folds into one button naming the current page. A column beside the
 * content does not fit there, and the header's drawer is for the whole site rather than this
 * corner of it.
 */
@Component({
  selector: 'app-admin-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin">
      <aside class="admin__sidebar">
        <p class="admin__title" i18n="@@adminSection">Administration</p>

        <button
          class="admin__toggle"
          type="button"
          aria-controls="admin-menu"
          [attr.aria-expanded]="open()"
          (click)="open.set(!open())"
        >
          <span>{{ current().label }}</span>
          <app-icon name="chevron-down" [size]="18" />
        </button>

        <nav
          id="admin-menu"
          class="admin__menu"
          [class.admin__menu--open]="open()"
          aria-label="Administration"
          i18n-aria-label="@@adminSection"
        >
          @for (item of items; track item.path) {
            <a
              class="admin__link"
              [routerLink]="link(item)"
              routerLinkActive="admin__link--active"
              ariaCurrentWhenActive="page"
              [routerLinkActiveOptions]="item.section ? thisSection : thisPage"
              (click)="open.set(false)"
            >
              <app-icon [name]="item.icon" [size]="18" />
              <span class="admin__label">
                {{ item.label }}
                @if (item.hint) {
                  <span class="admin__hint">{{ item.hint }}</span>
                }
              </span>
            </a>
          }
        </nav>
      </aside>

      <div class="admin__content">
        <router-outlet />
      </div>
    </div>
  `,
  styleUrl: './admin-layout.scss',
})
export class AdminLayout {
  private readonly router = inject(Router);

  protected readonly items = ADMIN_NAV;
  protected readonly thisPage = THIS_PAGE;
  protected readonly thisSection = THIS_SECTION;
  protected readonly open = signal(false);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );
  protected readonly current = computed(() => adminNavItemFor(this.url()));

  protected link(item: AdminNavItem): string {
    return item.path ? `/admin/${item.path}` : '/admin';
  }
}
