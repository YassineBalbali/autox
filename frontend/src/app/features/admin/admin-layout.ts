import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { IsActiveMatchOptions, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { catchError, distinctUntilChanged, filter, forkJoin, map, of, startWith, switchMap } from 'rxjs';

import { AdminService, ModerationView } from '../../core/api';
import { countFr } from '../../shared/format/plural';
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

/** What a tab's badge counts. */
type Waiting = 'listings' | 'reports';

export interface AdminNavItem {
  readonly label: string;
  /** The tab bar's label, where the full one does not fit under an icon. */
  readonly short?: string;
  /** What the entry is about, where the label alone leaves it open. */
  readonly hint?: string;
  readonly icon: IconName;
  /** Relative to `/admin`, as declared in the route table. */
  readonly path: string;
  /** Whether pages under this one count as this one. */
  readonly section?: boolean;
  /** What waits behind it, shown on the tab bar's icon. */
  readonly waiting?: Waiting;
}

/** Only pages that exist: `app.routes.spec.ts` fails on an entry with no route behind it. */
export const ADMIN_NAV: readonly AdminNavItem[] = [
  { label: 'Tableau de bord', short: 'Tableau', icon: 'gauge', path: '' },
  { label: 'Modération', hint: 'Annonces', icon: 'shield-check', path: 'moderation', waiting: 'listings' },
  { label: 'Signalements', hint: 'Messages', icon: 'flag', path: 'moderation/messages', waiting: 'reports' },
  { label: 'Utilisateurs', icon: 'users', path: 'utilisateurs', section: true },
];

type Counts = Record<Waiting, number>;

/** Past this, the badge stops counting: it says there is work, the page says how much. */
const BADGE_MAX = 99;

/**
 * The back office's frame: a sidebar down the left edge of the window from 1024px, a tab bar
 * fixed to the foot of the screen below that, and the page.
 *
 * <p>The tab bar's badges are the queue and the open reports, counted by the two lists' own
 * endpoints. They are fetched again on moving to another admin page, not on every change of
 * filter or page within one.
 */
@Component({
  selector: 'app-admin-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin">
      <aside class="admin__sidebar">
        <p class="admin__title" i18n="@@adminSection">Administration</p>

        <nav class="admin__menu" aria-label="Administration" i18n-aria-label="@@adminSection">
          @for (item of items; track item.path) {
            <a
              class="admin__link"
              [routerLink]="link(item)"
              routerLinkActive="admin__link--active"
              ariaCurrentWhenActive="page"
              [routerLinkActiveOptions]="item.section ? thisSection : thisPage"
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

      <nav class="admin__tabs" aria-label="Administration" i18n-aria-label="@@adminSection">
        @for (item of items; track item.path) {
          <a
            class="admin__tab"
            [routerLink]="link(item)"
            routerLinkActive="admin__tab--active"
            ariaCurrentWhenActive="page"
            [routerLinkActiveOptions]="item.section ? thisSection : thisPage"
          >
            <span class="admin__tab-icon">
              <app-icon [name]="item.icon" [size]="22" />
              @if (badge(item); as count) {
                <span class="admin__badge" aria-hidden="true">{{ count }}</span>
              }
            </span>
            <span class="admin__tab-label">{{ item.short ?? item.label }}</span>
            @if (waitingText(item); as text) {
              <span class="visually-hidden">, {{ text }}</span>
            }
          </a>
        }
      </nav>
    </div>
  `,
  styleUrl: './admin-layout.scss',
})
export class AdminLayout {
  private readonly router = inject(Router);
  private readonly api = inject(AdminService);

  protected readonly items = ADMIN_NAV;
  protected readonly thisPage = THIS_PAGE;
  protected readonly thisSection = THIS_SECTION;

  /** Null until both counts are in, and if either fails: no badge rather than a wrong one. */
  private readonly counts = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => pathOf(this.router.url)),
      startWith(pathOf(this.router.url)),
      distinctUntilChanged(),
      switchMap(() =>
        forkJoin({
          listings: this.api.moderationList({ view: ModerationView.PENDING, page: 0, size: 1 }),
          reports: this.api.openReports({ page: 0, size: 1 }),
        }).pipe(
          map((answer): Counts => ({
            listings: answer.listings.totalElements ?? 0,
            reports: answer.reports.totalElements ?? 0,
          })),
          catchError(() => of(null)),
        ),
      ),
    ),
    { initialValue: null },
  );

  protected link(item: AdminNavItem): string {
    return item.path ? `/admin/${item.path}` : '/admin';
  }

  private waitingFor(item: AdminNavItem): number {
    return item.waiting ? (this.counts()?.[item.waiting] ?? 0) : 0;
  }

  /** The badge's figure, or nothing when nothing waits. */
  protected badge(item: AdminNavItem): string | null {
    const count = this.waitingFor(item);
    return count === 0 ? null : count > BADGE_MAX ? `${BADGE_MAX}+` : String(count);
  }

  /** What the badge says, for a screen reader, which hears the tab's name first. */
  protected waitingText(item: AdminNavItem): string | null {
    const count = this.waitingFor(item);
    if (count === 0) {
      return null;
    }
    return item.waiting === 'listings'
      ? countFr(count, 'annonce à vérifier', 'annonces à vérifier')
      : countFr(count, 'signalement ouvert', 'signalements ouverts');
  }
}

function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}
