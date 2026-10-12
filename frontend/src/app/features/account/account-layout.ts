import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { UserRole } from '../../core/api';
import { AccountProfileStore } from './account-profile.store';

/** One entry in the account sidebar. */
interface SidebarItem {
  readonly label: string;
  readonly path: string;
  /** Set for the two entries that are sections of the profile page, not routes. */
  readonly fragment?: string;
  readonly dealerOnly?: boolean;
}

const SIDEBAR: readonly SidebarItem[] = [
  // Identité and Mes informations are the two panels of the profile screen rather than
  // routes of their own. They are listed because the sidebar is a map of everything in the
  // account, and splitting a read-only block of four facts into its own page to justify a
  // nav entry would be the tail wagging the dog. Anchor scrolling is already on, so the
  // links behave: see `withInMemoryScrolling` in app.config.ts.
  { label: 'Identité', path: '/mon-compte', fragment: 'identite' },
  { label: 'Mes informations', path: '/mon-compte', fragment: 'mes-informations' },
  { label: 'Entreprise', path: '/mon-compte/entreprise', dealerOnly: true },
  { label: 'Mot de passe', path: '/mon-compte/mot-de-passe' },
  { label: 'Sessions', path: '/mon-compte/sessions' },
];

/**
 * The account section: a sidebar of everything on the account, and the page beside it.
 *
 * <p>A routed layout with the pages as children, rather than a sidebar each page imports.
 * The sidebar is then rendered once and stays put while the content changes — no flicker
 * between account screens, one `authGuard` on the parent instead of four, and one load of
 * the profile for the whole section.
 */
@Component({
  selector: 'app-account-layout',
  imports: [RouterLink, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AccountProfileStore],
  templateUrl: './account-layout.html',
  styleUrl: './account-layout.scss',
})
export class AccountLayout {
  private readonly router = inject(Router);
  private readonly store = inject(AccountProfileStore);

  /**
   * The current URL, as a signal.
   *
   * <p>`routerLinkActive` would cover the three real routes but not the two fragment
   * entries — it does not look at the fragment, so both would light up together on the
   * profile page, or neither would. Deriving the active entry from the URL handles all
   * five the same way and makes the rule a pure function worth testing.
   */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  protected readonly items = computed(() => {
    const dealer =
      this.store.profile()?.role === UserRole.PROFESSIONNEL ||
      this.store.profile()?.role === UserRole.ADMIN;
    return SIDEBAR.filter((item) => !item.dealerOnly || dealer);
  });

  protected readonly activePath = computed(() => activeKey(this.url()));

  constructor() {
    this.store.ensureLoaded();
  }

  protected keyOf(item: SidebarItem): string {
    return item.fragment ? `${item.path}#${item.fragment}` : item.path;
  }

  protected isActive(item: SidebarItem): boolean {
    return this.keyOf(item) === this.activePath();
  }
}

/**
 * Which sidebar entry the current URL corresponds to.
 *
 * <p>On the profile page the entry depends on the fragment, and no fragment means the top
 * of the page — which is Identité. Exported so the rule can be tested without a router.
 */
export function activeKey(url: string): string {
  const [pathAndQuery, fragment] = url.split('#');
  const path = pathAndQuery.split('?')[0];

  if (path === '/mon-compte') {
    return `/mon-compte#${fragment === 'mes-informations' ? 'mes-informations' : 'identite'}`;
  }
  return path;
}
